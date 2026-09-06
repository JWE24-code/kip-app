(ns frontend.handler.mindmap
  "Mindmap related handlers"
  (:require [datascript.core :as d]
            [frontend.config :as config]
            [frontend.handler.page :as page-handler]
            [frontend.handler.route :as route-handler]
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
