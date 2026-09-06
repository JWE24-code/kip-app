(ns frontend.handler.mindmap
  "Mindmap related handlers.

   Structure edits mutate the underlying outline blocks (the single source of
   truth) through the same outliner operations the outline editor uses, so every
   change round-trips through the page's Markdown and is covered by undo/redo."
  (:require [datascript.core :as d]
            [frontend.config :as config]
            [frontend.db :as db]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.page :as page-handler]
            [frontend.handler.route :as route-handler]
            [frontend.modules.outliner.core :as outliner-core]
            [frontend.modules.outliner.transaction :as outliner-tx]
            [frontend.state :as state]
            [frontend.util :as util]
            [promesa.core :as p]))

(defn create-new-mindmap-page!
  "Creates a `:block/type \"mindmap\"` page. The page is a plain Markdown page
   (single source of truth) whose `type:: mindmap` property marks it as a
   mindmap so the type round-trips through the file."
  ([]
   (create-new-mindmap-page! nil))
  ([name]
   (let [uuid (or (and name (parse-uuid name)) (d/squuid))
         name (or name (str uuid))]
     (page-handler/create! name {:redirect? false
                                 :create-first-block? false
                                 :uuid uuid
                                 :mindmap? true
                                 :properties {:type "mindmap"}}))))

(defn create-new-mindmap-and-redirect!
  ([]
   (create-new-mindmap-and-redirect! (str (d/squuid))))
  ([name]
   (when-not config/publishing?
     (p/do!
      (create-new-mindmap-page! name)
      (route-handler/redirect-to-mindmap! name)))))

;; --- structure editing -----------------------------------------------------

(defn- entity [block-uuid]
  (when block-uuid (db/entity [:block/uuid block-uuid])))

(defn add-topic!
  "Inserts a new empty topic relative to `block-uuid`. With `:sibling? true` it
   is added right after `block-uuid`; otherwise it is appended as the last child
   (expanding `block-uuid` first when it is collapsed). Returns the new topic's
   uuid, or nil when nothing was inserted."
  [block-uuid {:keys [sibling?]}]
  (when-let [block (entity block-uuid)]
    (when (and (not sibling?) (util/collapsed? block))
      (editor-handler/expand-block! block-uuid))
    (some-> (editor-handler/api-insert-new-block!
             ""
             {:block-uuid block-uuid
              :sibling? (boolean sibling?)
              :edit-block? false})
            :block/uuid)))

(defn add-root-topic!
  "Appends a new empty top-level topic to the mindmap `page` (used when the
   central topic is selected). Returns the new topic's uuid."
  [page-name]
  (some-> (editor-handler/api-insert-new-block!
           ""
           {:page page-name
            :edit-block? false})
          :block/uuid))

(defn delete-topic!
  "Deletes `block-uuid` and its whole subtree. Undoable."
  [block-uuid]
  (when-let [block (db/pull [:block/uuid block-uuid])]
    (editor-handler/delete-block-aux! block true)))

(defn set-topic-content!
  "Saves edited node text back to the block. Undoable, and a no-op when the text
   is unchanged."
  [block-uuid content]
  (when (entity block-uuid)
    (editor-handler/save-block! (state/get-current-repo) block-uuid (or content ""))))

(defn outdent-topic!
  "Moves `block-uuid` up one level (after its current parent). No-op when the
   topic is already a top-level branch. Undoable."
  [block-uuid]
  (when-let [block (entity block-uuid)]
    (outliner-tx/transact!
     {:outliner-op :move-blocks :real-outliner-op :indent-outdent}
     (outliner-core/indent-outdent-blocks! [block] false))))

(defn indent-topic!
  "Moves `block-uuid` down one level (under its previous sibling). No-op when the
   topic has no previous sibling. Undoable."
  [block-uuid]
  (when-let [block (entity block-uuid)]
    (outliner-tx/transact!
     {:outliner-op :move-blocks :real-outliner-op :indent-outdent}
     (outliner-core/indent-outdent-blocks! [block] true))))

(defn reparent-topic!
  "Moves `block-uuid` to become the last child of `target-uuid`, or a top-level
   branch when `target-uuid` equals `page-uuid`. Cycle-safe: `move-blocks`
   itself refuses to move a node into its own subtree, so this is a no-op when
   the target is the node or one of its descendants. Undoable."
  [block-uuid target-uuid page-uuid]
  (when-let [block (entity block-uuid)]
    (let [top-level? (or (nil? target-uuid) (= target-uuid page-uuid))
          target (entity (if top-level? page-uuid target-uuid))]
      (when (and target (not= (:db/id target) (:db/id block)))
        (outliner-tx/transact!
         {:outliner-op :move-blocks}
         (outliner-core/move-blocks! [block] target false))))))
