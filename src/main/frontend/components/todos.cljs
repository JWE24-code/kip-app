(ns frontend.components.todos
  "The Tasks panel (right-sidebar entry, see :todos in
   frontend.components.right-sidebar). A view over the graph's open task
   blocks (TODO / DOING / NOW / LATER markers) with quick capture into today's
   journal. Todos are plain Logseq blocks — no separate store; checking off
   here flips the block's marker to DONE, durable and queryable like any other
   block.

   The panel is the forward-looking day plan: sections for what's due,
   today's captures, unfinished items carried over from earlier days, and the
   rest of the backlog. The journal itself is the backward-looking record — a
   captured todo stays on its journal day as TODO, then DONE once checked off.

   A captured todo with a due date also projects into the reminder engine
   (electron-only): a reminder is created at 09:00 on the due date and its id
   is stored on the block as a :reminder property, so checking the todo off
   cancels the reminder."
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            [electron.ipc :as ipc]
            [frontend.components.block :as block]
            [frontend.config :as config]
            [frontend.date :as date]
            [frontend.db :as db]
            [frontend.db-mixins :as db-mixins]
            [frontend.db.model :as db-model]
            [frontend.handler.editor :as editor-handler]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [logseq.graph-parser.util.db :as db-util]
            [logseq.graph-parser.util.page-ref :as page-ref]
            [promesa.core :as p]
            [rum.core :as rum]))

(def ^:private open-markers #{"TODO" "DOING" "NOW" "LATER"})

(def ^:private marker-prefix-re
  #"^(NOW|LATER|TODO|DOING|DONE|WAITING|WAIT|CANCELED|CANCELLED|IN-PROGRESS)\s+")

(def ^:private marker-pages
  #{"TODO" "DONE" "DOING" "NOW" "LATER" "WAITING" "WAIT" "CANCELED" "CANCELLED" "IN-PROGRESS"})

(defn- strip-marker [content]
  (string/replace (or content "") marker-prefix-re ""))

;; Block properties are stored as trailing `key:: value` lines; strip them so
;; the panel renders just the todo text.
(defn- strip-property-lines [content]
  (->> (string/split-lines (or content ""))
       (remove #(re-find #"^\s*[\w-]+::\s" %))
       (string/join "\n")))

(defn- text-of [block]
  (-> (:block/content block)
      strip-marker
      strip-property-lines
      string/trim))

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

;; --- dates ------------------------------------------------------------------
;; due dates are stored as `due:: YYYY-MM-DD` block properties and compared as
;; yyyyMMdd integers against the journal-day of the todo's page.

(defn- due->int [s]
  (when (string? s)
    (when-let [[_ y m d] (re-matches #"(\d{4})-(\d{2})-(\d{2})" (string/trim s))]
      (+ (* (js/parseInt y 10) 10000)
         (* (js/parseInt m 10) 100)
         (js/parseInt d 10)))))

(defn- due->iso [s]
  (when-let [[_ y m d] (re-matches #"(\d{4})-(\d{2})-(\d{2})" (or s ""))]
    (.toISOString
     (js/Date. (js/parseInt y 10) (dec (js/parseInt m 10)) (js/parseInt d 10) 9 0 0))))

(defn- vault-root []
  (config/get-repo-dir (state/get-current-repo)))

;; --- reminder projection ----------------------------------------------------

(defn- add-reminder! [text due-date cb]
  (let [iso (due->iso due-date)]
    (if-not iso
      (cb nil)
      (-> (ipc/ipc "wikiRemindersAddTodo" (vault-root) {:title text :event-at iso})
          (p/then (fn [r] (cb (get-in (bean/->clj r) [:reminder :id]))))
          (p/catch (fn [_] (cb nil)))))))

(defn- cancel-reminder! [id]
  (-> (ipc/ipc "wikiRemindersCancel" (vault-root) (str id))
      (p/catch (fn [_] nil))))

;; --- actions ----------------------------------------------------------------

(defn- insert-todo! [linked due-date reminder-id]
  (editor-handler/api-insert-new-block!
   (str "TODO " linked)
   {:page (date/journal-name)
    :edit-block? false
    :properties (cond-> {}
                  (some? due-date) (assoc :due due-date)
                  (some? reminder-id) (assoc :reminder (str reminder-id)))}))

(defn- add! [*input *due]
  (let [text (string/trim @*input)
        due-date (some-> @*due string/trim not-empty)]
    (when-not (string/blank? text)
      (reset! *input "")
      (reset! *due "")
      (let [repo (state/get-current-repo)
            linked (auto-link-text repo text)]
        (if (and (util/electron?) due-date)
          (add-reminder! text due-date #(insert-todo! linked due-date %))
          (insert-todo! linked due-date nil))))))

(defn- check! [block]
  (editor-handler/set-marker block "DONE")
  (when-let [reminder-id (get-in block [:block/properties :reminder])]
    (when (util/electron?)
      (cancel-reminder! reminder-id))))

;; --- rendering --------------------------------------------------------------

(defn- bucket [today-int b]
  (let [d (due->int (get-in b [:block/properties :due]))
        jd (get-in b [:block/page :block/journal-day])]
    (cond
      (and d (<= d today-int)) :due
      (and d (> d today-int)) :upcoming
      (and jd (< jd today-int)) :carried
      (and jd (= jd today-int)) :today
      :else :backlog)))

(def ^:private section-order
  [[:due "Due"]
   [:today "Today"]
   [:carried "Carried over"]
   [:upcoming "Upcoming"]
   [:backlog "Backlog"]])

(rum/defc todo-row < rum/static
  [block]
  (let [page-name (or (get-in block [:block/page :block/original-name])
                      (get-in block [:block/page :block/name])
                      "")
        text (text-of block)
        due (get-in block [:block/properties :due])
        reminder? (contains? (:block/properties block) :reminder)]
    [:div.py-1.px-1.rounded.group.flex.items-start.gap-2
     {:class "hover:bg-gray-03"}
     [:div.flex-1.min-w-0
      [:div.text-sm.leading-snug
       (block/inline-text {} :markdown text)]
      [:div.text-xs.opacity-40.truncate.pt-0.5
       page-name
       (when due [:span (str " · due " due)])
       (when reminder? [:span.inline-flex.align-middle.ml-1 (ui/icon "bell" {:size 11 :class "opacity-40"})])]]
     (ui/button {:icon "check" :icon-props {:size 14} :variant :ghost :size :xs
                 :class "opacity-0 group-hover:opacity-100 shrink-0"
                 :title "Done"
                 :on-click #(check! block)})]))

(rum/defcs todos-panel < rum/reactive db-mixins/query
  (rum/local "" ::input)
  (rum/local "" ::due)
  [state]
  (let [*input (::input state)
        *due (::due state)
        repo (state/sub :git/current-repo)
        today-int (db-util/date->int (js/Date.))
        todos (when repo
                (some-> (db/q repo [:custom :todos] {:use-cache? false}
                              '[:find [(pull ?b [:db/id :block/uuid :block/content :block/marker :block/format
                                                :block/properties :block/repeated?
                                                {:block/page [:db/id :block/name :block/original-name :block/journal-day]}]) ...]
                                :where
                                (or
                                 [?b :block/marker "TODO"]
                                 [?b :block/marker "DOING"]
                                 [?b :block/marker "NOW"]
                                 [?b :block/marker "LATER"])])
                        util/react))
        done-today (when repo
                     (some-> (db/q repo [:custom :todos-done-today] {:use-cache? false}
                                   '[:find (count ?b) .
                                     :in $ ?today
                                     :where
                                     [?b :block/marker "DONE"]
                                     [?b :block/page ?p]
                                     [?p :block/journal-day ?today]]
                                   today-int)
                             util/react))
        todos (->> (or todos [])
                   (sort-by (fn [b] [(get-in b [:block/page :block/original-name] "")
                                     (or (:block/content b) "")])))
        groups (group-by #(bucket today-int %) todos)]
    [:div.flex.flex-col {:style {:height "100%"}}
     [:div.flex.gap-2.px-1.pb-2
      [:input.form-input.is-small.flex-1.text-sm
       {:type "text"
        :placeholder "Add a todo — mention a page to link it"
        :value @*input
        :on-change #(reset! *input (.. % -target -value))
        :on-key-down (fn [e] (when (= "Enter" (.-key e)) (add! *input *due)))}]
      [:input.form-input.is-small.text-sm
       {:type "date"
        :style {:max-width "9em"}
        :value @*due
        :on-change #(reset! *due (.. % -target -value))}]
      (ui/button {:size :xs :on-click #(add! *input *due)} "Add")]
     [:div.flex-1.overflow-y-auto {:class "overflow-x-hidden"}
      (if (empty? todos)
        [:div.text-sm.opacity-50.px-2.py-6.leading-relaxed
         "No open todos. Add one — it lands in today's journal as a "
         [:code "TODO"] " block. Give it a due date to get a reminder."]
        [:div
         [:div.text-xs.opacity-50.px-1.pb-1
          (str (or done-today 0) " done · " (count todos) " open"
               (when (seq (:carried groups))
                 (str " · " (count (:carried groups)) " carried")))]
         (for [[k label] section-order
               :let [items (if (= k :due)
                             (sort-by #(due->int (get-in % [:block/properties :due])) (get groups k))
                             (get groups k))]
               :when (seq items)]
           [:div {:key (name k)}
            [:div.text-xs.uppercase.tracking-wide.opacity-40.px-1.pt-1.pb-1
             (str label " (" (count items) ")")]
            [:div.space-y-0.5
             (for [b items]
               (rum/with-key (todo-row b) (str (:block/uuid b))))]])])]]))

