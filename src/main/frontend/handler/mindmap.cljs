(ns frontend.handler.mindmap
  "Mindmap related handlers.

   Structure edits mutate the underlying outline blocks (the single source of
   truth) through the same outliner operations the outline editor uses, so every
   change round-trips through the page's Markdown and is covered by undo/redo."
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [frontend.config :as config]
            [frontend.db :as db]
            [frontend.db.model :as model]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.editor.property :as editor-property]
            [frontend.handler.page :as page-handler]
            [frontend.handler.route :as route-handler]
            [frontend.modules.outliner.core :as outliner-core]
            [frontend.modules.outliner.transaction :as outliner-tx]
            [frontend.state :as state]
            [frontend.util :as util]
            [frontend.util.page-property :as page-property]
            [frontend.util.property :as property-util]
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

(defn editable-text
  "The node text a user edits on the map: the block content with its property
   drawer stripped off (so editing the title never clobbers `key:: value`
   lines), everything else — marker, priority — kept."
  [block]
  ;; `remove-properties` is [format content]
  (-> (property-util/remove-properties (:block/format block)
                                       (str (:block/content block "")))
      string/trim))

(defn set-topic-content!
  "Saves edited node text back to the block, re-attaching the block's existing
   properties so per-topic styling / notes survive a text edit. Undoable, and a
   no-op when nothing changed."
  [block-uuid content]
  (when-let [block (entity block-uuid)]
    (let [format (:block/format block)
          props (:block/properties block)
          order (or (seq (:block/properties-order block)) (keys props))
          text-values (:block/properties-text-values block)
          title (string/trim (str (or content "")))
          new-content (if (seq props)
                        (property-util/insert-properties
                         format title
                         (for [k order]
                           [k (or (get text-values k) (get props k))]))
                        title)]
      (editor-handler/save-block! (state/get-current-repo) block-uuid new-content))))

(defn rename-central-topic!
  "Renames the mindmap page from its central topic, then stays on the map view.
   Blank input is ignored (a mindmap always keeps its current name); a no-op
   when the name is unchanged."
  [page-name new-name]
  (let [new-name (string/trim (str (or new-name "")))
        page (db/entity [:block/name (util/page-name-sanity-lc page-name)])
        cur (or (get-in page [:block/properties :title])
                (:block/original-name page))]
    (when (and (seq new-name) (not= new-name cur))
      (page-handler/rename! (or (:block/original-name page) page-name) new-name)
      (route-handler/redirect-to-mindmap! (util/page-name-sanity-lc new-name)))))

;; --- styling, markers, notes ---------------------------------------------

(defn set-topic-property!
  "Sets (or, with a blank/nil value, clears) a block property on a topic. Round-
   trips to Markdown as a `key:: value` line; undoable."
  [block-uuid key value]
  (when (entity block-uuid)
    (if (or (nil? value) (and (string? value) (string/blank? value)))
      (editor-property/remove-block-property! block-uuid key)
      (editor-property/set-block-property! block-uuid key value))))

(defn cycle-topic-marker!
  "Cycles the topic's task marker TODO -> DOING -> DONE -> (none). Undoable."
  [block-uuid]
  (when-let [block (db/pull [:block/uuid block-uuid])]
    (when (seq (editable-text block))
      (editor-handler/set-marker block))))

(defn set-topic-marker!
  "Forces a specific task marker on the topic (\"TODO\", \"DONE\", ...)."
  [block-uuid marker]
  (when-let [block (db/pull [:block/uuid block-uuid])]
    (when (seq (editable-text block))
      (editor-handler/set-marker block marker))))

(defn set-map-theme!
  "Stores the map-wide theme as a `mindmap-theme::` property on the mindmap
   page's properties block (so it round-trips through the file). `\"default\"`
   clears the property. Undoable."
  [page-name theme]
  (let [pn (util/page-name-sanity-lc page-name)
        repo (state/get-current-repo)]
    (when-let [page (db/entity [:block/name pn])]
      ;; a few older mindmaps carry `:block/type` but no properties block —
      ;; materialise one (re-stating `type::`, which is already there, so no
      ;; data is lost) before we can hang a property off it
      (when-not (model/get-pre-block repo (:db/id page))
        (page-property/add-property! pn :type "mindmap"))
      (when-let [pre (model/get-pre-block repo (:db/id page))]
        (set-topic-property! (:block/uuid pre) :mindmap-theme
                             (when (not= theme "default") theme))))))

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

;; --- detached trees -----------------------------------------------------------

(defn add-detached-topic!
  "Adds a new top-level topic that renders as its own tree, unconnected to the
   central topic. The `mindmap-detached::` property is baked into the block's
   content on creation (one transaction) so the inline editor keeps focus."
  [page-name]
  (some-> (editor-handler/api-insert-new-block!
           (property-util/insert-properties :markdown "" [[:mindmap-detached "true"]])
           {:page page-name
            :edit-block? false})
          :block/uuid))

(defn detach-topic!
  "Promotes `block-uuid` (with its subtree) to a top-level block and tags it
   `mindmap-detached:: true` so it renders as its own tree. Undoable."
  [block-uuid page-uuid]
  (when (entity block-uuid)
    (reparent-topic! block-uuid page-uuid page-uuid)
    (set-topic-property! block-uuid :mindmap-detached true)))
