(ns frontend.handler.whiteboard
  "Whiteboard related handlers"
  (:require [datascript.core :as d]
            [frontend.db :as db]
            [frontend.db.utils :as db-utils]
            [frontend.handler.route :as route-handler]
            [frontend.fs :as fs]
            [frontend.state :as state]
            [frontend.config :as config]
            [frontend.util :as util]
            [promesa.core :as p]
            [clojure.string :as string]))

(defn get-default-new-whiteboard-tx
  [page-name file-path]
  [#:block{:name (util/page-name-sanity-lc page-name),
           :original-name page-name
           :type "whiteboard",
           :file {:file/path file-path}
           :journal? false
           :updated-at (util/time-ms),
           :created-at (util/time-ms)}])

(defn get-whiteboard-entity [page-name]
  (db-utils/entity [:block/name (util/page-name-sanity-lc page-name)]))

(defonce default-whiteboard-content
  (util/format
   "{\n  \"type\": \"excalidraw\",\n  \"version\": 2,\n  \"source\": \"%s\",\n  \"elements\": [],\n  \"appState\": {\n    \"viewBackgroundColor\": \"transparent\",\n    \"gridSize\": null\n  }\n}"
   config/website))

(defn- whiteboard-paths
  "Conventional file paths for a whiteboard page name. Only used as a fallback
   and at creation time; afterwards the page's :block/file link wins so that
   renames keep pointing at the same file."
  [page-name]
  (let [dir (config/get-whiteboards-directory)
        base (str dir "/" (util/page-name-sanity-lc page-name))]
    {:json (str base ".excalidraw")
     :svg (str base ".svg")}))

(defn svg-path-for
  "Path of the scene-embedded svg sibling of an .excalidraw file."
  [json-path]
  (if (string/ends-with? json-path ".excalidraw")
    (str (subs json-path 0 (- (count json-path) (count ".excalidraw"))) ".svg")
    (str json-path ".svg")))

(defn whiteboard-file-path
  "Resolves the .excalidraw file path of a whiteboard page. Prefers the page's
  :block/file link (rename-safe), falls back to the conventional path."
  [page-name]
  (or (when-let [page (get-whiteboard-entity page-name)]
        (:file/path (:block/file page)))
      (:json (whiteboard-paths page-name))))

(defn save-whiteboard!
  "Writes the .excalidraw json (source of truth) and, when there is content,
  a sibling svg with the scene embedded (used for embeds and previews)."
  ([json-path json-text]
   (save-whiteboard! json-path json-text nil))
  ([json-path json-text svg-text]
   (when-let [repo (state/get-current-repo)]
     (let [dir (config/get-repo-dir repo)]
       (p/do!
        (util/p-handle
         (fs/mkdir! (str dir "/" (config/get-whiteboards-directory)))
         (fn [_result] nil)
         (fn [_error] nil))
        (fs/write-plain-text-file! repo dir json-path json-text nil)
        (when svg-text
          (fs/write-plain-text-file! repo dir (svg-path-for json-path) svg-text nil)))))))

(defn create-new-whiteboard-page!
  ([]
   (create-new-whiteboard-page! nil))
  ([name]
   (let [uuid (or (and name (parse-uuid name)) (d/squuid))
         name (or name (str uuid))
         {:keys [json] :as paths} (whiteboard-paths name)]
     (db/transact! (get-default-new-whiteboard-tx name json))
     (let [entity (get-whiteboard-entity name)
           tx (assoc (select-keys entity [:db/id])
                     :block/uuid uuid)]
       (db-utils/transact! [tx]))
     (save-whiteboard! json default-whiteboard-content))))

(defn create-new-whiteboard-and-redirect!
  ([]
   (create-new-whiteboard-and-redirect! (str (d/squuid))))
  ([name]
   (when-not config/publishing?
     (create-new-whiteboard-page! name)
     (route-handler/redirect-to-whiteboard! name {:new-whiteboard? true}))))
