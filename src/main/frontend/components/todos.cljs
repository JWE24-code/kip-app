(ns frontend.components.todos
  "The Todos panel (right-sidebar entry, see :todos in
   frontend.components.right-sidebar). A view over the graph's open task
   blocks (TODO / DOING / NOW / LATER markers) with quick capture into today's
   journal. Todos are plain Logseq blocks — no separate store; checking off
   here flips the block's marker to DONE, durable and queryable like any other
   block."
  (:require [clojure.string :as string]
            [frontend.components.block :as block]
            [frontend.date :as date]
            [frontend.db :as db]
            [frontend.db-mixins :as db-mixins]
            [frontend.db.model :as db-model]
            [frontend.handler.editor :as editor-handler]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [logseq.graph-parser.util.page-ref :as page-ref]
            [rum.core :as rum]))

(def ^:private open-markers #{"TODO" "DOING" "NOW" "LATER"})

(def ^:private marker-prefix-re
  #"^(NOW|LATER|TODO|DOING|DONE|WAITING|WAIT|CANCELED|CANCELLED|IN-PROGRESS)\s+")

(def ^:private marker-pages
  #{"TODO" "DONE" "DOING" "NOW" "LATER" "WAITING" "WAIT" "CANCELED" "CANCELLED" "IN-PROGRESS"})

(defn- strip-marker [content]
  (string/replace (or content "") marker-prefix-re ""))

;; --- intelligent linking ----------------------------------------------------
;; On capture, wrap the most specific existing page name that appears in the
;; text (whole-word, case-insensitive) in [[...]]. v1 links a single page and
;; stops; multi-link is a follow-up.

(defn- alnum? [ch]
  (boolean (re-find #"[A-Za-z0-9]" (str ch))))

(defn- boundary-match-index [text needle]
  (let [lt (string/lower-case text)
        ln (string/lower-case needle)
        len (count needle)]
    (loop [i 0]
      (let [idx (string/index-of lt ln i)]
        (when idx
          (let [before-ok (or (zero? idx) (not (alnum? (subs text (dec idx) idx))))
                after-ok (or (>= (+ idx len) (count text))
                             (not (alnum? (subs text (+ idx len) (+ idx len 1)))))]
            (if (and before-ok after-ok)
              idx
              (recur (inc idx)))))))))

(defn- auto-link-text [repo text]
  (let [candidates (->> (db-model/get-pages repo)
                        (map str)
                        (remove #(contains? marker-pages (string/upper-case %)))
                        (filter #(>= (count %) 4))
                        (sort-by (comp - count))
                        (take 400))]
    (reduce (fn [s page-name]
              (if (string/includes? s "[[")
                s
                (if-let [idx (boundary-match-index s page-name)]
                  (str (subs s 0 idx)
                       (page-ref/->page-ref page-name)
                       (subs s (+ idx (count page-name))))
                  s)))
            text
            candidates)))

;; --- actions ----------------------------------------------------------------

(defn- add! [*input]
  (let [text (string/trim @*input)]
    (when-not (string/blank? text)
      (reset! *input "")
      (let [repo (state/get-current-repo)
            linked (auto-link-text repo text)]
        (editor-handler/api-insert-new-block!
         (str "TODO " linked)
         {:page (date/journal-name)
          :edit-block? false})))))

(defn- check! [block]
  (editor-handler/set-marker block "DONE"))

;; --- rendering --------------------------------------------------------------

(rum/defc todo-row < rum/static
  [block]
  (let [page-name (or (get-in block [:block/page :block/original-name])
                      (get-in block [:block/page :block/name])
                      "")
        text (strip-marker (:block/content block))]
    [:div.py-1.px-1.rounded.group.flex.items-start.gap-2
     {:class "hover:bg-gray-03"}
     [:div.flex-1.min-w-0
      [:div.text-sm.leading-snug
       (block/inline-text {} :markdown text)]
      [:div.text-xs.opacity-40.truncate.pt-0.5 page-name]]
     (ui/button {:icon "check" :icon-props {:size 14} :variant :ghost :size :xs
                 :class "opacity-0 group-hover:opacity-100 shrink-0"
                 :title "Done"
                 :on-click #(check! block)})]))

(rum/defcs todos-panel < rum/reactive db-mixins/query
  (rum/local "" ::input)
  [state]
  (let [*input (::input state)
        repo (state/sub :git/current-repo)
        todos (when repo
                (util/react
                 (db/q repo [:custom :todos] {:use-cache? false}
                       '[:find [(pull ?b [:db/id :block/uuid :block/content :block/marker :block/format
                                          :block/properties :block/repeated?
                                          {:block/page [:db/id :block/name :block/original-name :block/journal-day]}]) ...]
                         :where
                         (or
                          [?b :block/marker "TODO"]
                          [?b :block/marker "DOING"]
                          [?b :block/marker "NOW"]
                          [?b :block/marker "LATER"])])))
        todos (->> todos
                   (sort-by (fn [b] [(get-in b [:block/page :block/original-name] "")
                                     (get-in b [:block/page :block/name] "")
                                     (or (:block/content b) "")])))]
    [:div.flex.flex-col {:style {:height "100%"}}
     [:div.flex.gap-2.px-1.pb-2
      [:input.form-input.is-small.flex-1.text-sm
       {:type "text"
        :placeholder "Add a todo — mention a page to link it"
        :value @*input
        :on-change #(reset! *input (.. % -target -value))
        :on-key-down (fn [e] (when (= "Enter" (.-key e)) (add! *input)))}]
      (ui/button {:size :xs :on-click #(add! *input)} "Add")]
     [:div.flex-1.overflow-y-auto {:class "overflow-x-hidden"}
      (if (empty? todos)
        [:div.text-sm.opacity-50.px-2.py-6.leading-relaxed
         "No open todos. Add one — it lands in today's journal as a "
         [:code "TODO"] " block, and lives in your graph as a plain file."]
        [:div.space-y-0.5
         (for [b todos]
           (rum/with-key (todo-row b) (str (:block/uuid b))))])]]))
