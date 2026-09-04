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

   A due-dated todo can project into the reminder engine (electron-only) on
   demand: a bell on the row creates a reminder at a chosen time, and its id
   is stored on the block as a :reminder property, so checking the todo off
   cancels it. Reminders are opt-in — they are no longer created automatically.

   A *follow-up* is a \"check back with X on <date>\" item: a TODO block with a
   :followup property (and, normally, a due date). Follow-ups live in their own
   section, sorted by due date, and project into the reminder engine exactly
   like a due-dated todo. The subject (X) is captured in the block text as a
   [[...]] link, so it stays attached to the page/person it names."
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
            [frontend.handler.editor.property :as editor-property]
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

(defn- due->iso
  "The due date as an ISO timestamp. `time` is \"HH:MM\" (default 09:00)."
  ([s] (due->iso s "09:00"))
  ([s time]
   (when-let [[_ y m d] (re-matches #"(\d{4})-(\d{2})-(\d{2})" (or s ""))]
     (let [[hh mm] (if-let [[_ h mn] (re-matches #"(\d{2}):(\d{2})" (or time ""))]
                     [(js/parseInt h 10) (js/parseInt mn 10)]
                     [9 0])]
       (.toISOString
        (js/Date. (js/parseInt y 10) (dec (js/parseInt m 10)) (js/parseInt d 10) hh mm 0))))))

(defn- pad2 [n]
  (if (< n 10) (str "0" n) (str n)))

(defn- today-str []
  (let [{:keys [year month day]} (date/get-local-date)]
    (str year "-" (pad2 month) "-" (pad2 day))))

(defn- vault-root []
  (config/get-repo-dir (state/get-current-repo)))

;; --- reminder projection ----------------------------------------------------

(defn- add-reminder! [text due-date time cb]
  (let [iso (due->iso due-date time)]
    (if (or (not iso) (<= (.getTime (js/Date. iso)) (js/Date.now)))
      ;; A reminder in the past is useless — skip it rather than schedule noise.
      (cb nil)
      (-> (ipc/ipc "wikiRemindersAddTodo" (vault-root) {:title text :event-at iso})
          (p/then (fn [r] (cb (get-in (bean/->clj r) [:reminder :id]))))
          (p/catch (fn [_] (cb nil)))))))

(defn- cancel-reminder! [id]
  (-> (ipc/ipc "wikiRemindersCancel" (vault-root) (str id))
      (p/catch (fn [_] nil))))

(defn- now-plus-1h []
  (let [d (js/Date.)]
    (str (pad2 (mod (inc (.getHours d)) 24)) ":" (pad2 (.getMinutes d)))))

(defn- default-reminder-time [due-date]
  (if (= due-date (today-str)) (now-plus-1h) "09:00"))

(defn- create-reminder! [block time]
  (let [text (text-of block)
        due (get-in block [:block/properties :due])]
    (add-reminder! text due time
      (fn [reminder-id]
        (when reminder-id
          (editor-property/set-block-property! (:block/uuid block) :reminder (str reminder-id)))))))

(defn- clear-reminder! [block]
  (when (util/electron?)
    (when-let [id (get-in block [:block/properties :reminder])]
      (cancel-reminder! id)))
  (editor-property/remove-block-property! (:block/uuid block) :reminder))

;; --- actions ----------------------------------------------------------------

(defn- insert-todo! [linked due-date followup?]
  (editor-handler/api-insert-new-block!
   (str "TODO " linked)
   {:page (date/journal-name)
    :edit-block? false
    :properties (cond-> {}
                  (some? due-date) (assoc :due due-date)
                  followup? (assoc :followup true))}))

(defn- add! [*input *due *followup]
  (let [text (string/trim @*input)
        due-date (some-> @*due string/trim not-empty)
        followup? (boolean @*followup)]
    (when-not (string/blank? text)
      (reset! *input "")
      (reset! *due "")
      (reset! *followup false)
      (let [repo (state/get-current-repo)
            linked (auto-link-text repo text)
            body (if followup? (str "Check back with " linked) linked)]
        (insert-todo! body due-date followup?)))))

(defn- check! [block]
  (editor-handler/set-marker block "DONE")
  (when-let [reminder-id (get-in block [:block/properties :reminder])]
    (when (util/electron?)
      (cancel-reminder! reminder-id))))

;; The day-end acknowledgment: stamp each carried-over item `carried:: <today>`
;; so it re-buckets to Today. The block stays on its original journal day (the
;; record), but now shows it was consciously carried forward.
(defn- carry-over! [items]
  (editor-property/batch-add-block-property! (mapv :block/uuid items) :carried (today-str)))

;; --- rendering --------------------------------------------------------------

(defn- bucket [today-int b]
  (let [due (due->int (get-in b [:block/properties :due]))
        carried (due->int (get-in b [:block/properties :carried]))
        followup? (contains? (:block/properties b) :followup)
        jd (get-in b [:block/page :block/journal-day])]
    (cond
      followup? :followups
      (and due (<= due today-int)) :due
      (and due (> due today-int)) :upcoming
      (and carried (= carried today-int)) :today
      (and jd (< jd today-int)) :carried
      (and jd (= jd today-int)) :today
      :else :backlog)))

(def ^:private section-order
  [[:due "Due"]
   [:followups "Follow-ups"]
   [:today "Today"]
   [:carried "Carried over"]
   [:upcoming "Upcoming"]
   [:backlog "Backlog"]])

(rum/defc todo-row < rum/reactive
  (rum/local false ::picking)
  (rum/local "09:00" ::time)
  [state block]
  (let [page-name (or (get-in block [:block/page :block/original-name])
                      (get-in block [:block/page :block/name])
                      "")
        text (text-of block)
        due (get-in block [:block/properties :due])
        carried? (contains? (:block/properties block) :carried)
        reminder? (contains? (:block/properties block) :reminder)
        *picking (::picking state)
        *time (::time state)]
    [:div.py-1.px-1.rounded.group.flex.items-start.gap-2
     {:class "hover:bg-gray-03"}
     [:div.flex-1.min-w-0
      [:div.text-sm.leading-snug
       (block/inline-text {} :markdown text)]
      [:div.text-xs.opacity-40.truncate.pt-0.5
       page-name
       (when carried? [:span " · carried"])
       (when due [:span (str " · due " due)])]
      (when (and due @*picking)
        [:div.flex.items-center.gap-1.pt-1
         [:input.form-input.is-small.text-sm
          {:type "time"
           :value @*time
           :on-change #(reset! *time (.. % -target -value))}]
         (ui/button "Set" {:size :xs
                           :on-click #(do (create-reminder! block @*time)
                                          (reset! *picking false))})
         (ui/button "✕" {:size :xs :variant :ghost :title "Cancel"
                          :on-click #(reset! *picking false)})])]
     (when due
       (ui/button {:icon "bell" :icon-props {:size 14} :variant :ghost :size :xs
                   :class (if reminder? "" "opacity-40")
                   :title (if reminder? "Cancel reminder" "Set a reminder")
                   :on-click (if reminder?
                               #(clear-reminder! block)
                               #(do (reset! *time (default-reminder-time due))
                                    (reset! *picking true)))}))
     (ui/button {:icon "check" :icon-props {:size 14} :variant :ghost :size :xs
                 :class "opacity-0 group-hover:opacity-100 shrink-0"
                 :title "Done"
                 :on-click #(check! block)})]))

(rum/defcs todos-panel < rum/reactive db-mixins/query
  (rum/local "" ::input)
  (rum/local "" ::due)
  (rum/local false ::followup)
  (rum/local nil ::flash)
  [state]
  (let [*input (::input state)
        *due (::due state)
        *followup (::followup state)
        *flash (::flash state)
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
        :placeholder (if @*followup
                       "Follow up with … (page or person)"
                       "Add a todo — mention a page to link it")
        :value @*input
        :on-change #(reset! *input (.. % -target -value))
        :on-key-down (fn [e] (when (= "Enter" (.-key e)) (add! *input *due *followup)))}]
      [:input.form-input.is-small.text-sm
       {:type "date"
        :style {:max-width "9em"}
        :value @*due
        :on-change #(reset! *due (.. % -target -value))}]
      (ui/button "Follow-up" {:size :xs :variant (if @*followup :default :ghost)
                              :title "Toggle follow-up — check back with someone on a date"
                              :on-click #(swap! *followup not)})
      (ui/button "Add" {:size :xs :on-click #(add! *input *due *followup)})]
     [:div.flex-1.overflow-y-auto {:class "overflow-x-hidden"}
      (if (empty? todos)
         [:div.text-sm.opacity-50.px-2.py-6.leading-relaxed
          "No open todos. Add one — it lands in today's journal as a "
          [:code "TODO"] " block. Give it a due date, then tap the bell to set a "
          "reminder, or toggle " [:code "Follow-up"] " to schedule a check-back."]
        [:div
         [:div.text-xs.opacity-50.px-1.pb-1
          (str (or done-today 0) " done · " (count todos) " open"
               (when (seq (:carried groups))
                 (str " · " (count (:carried groups)) " carried")))]
         (when @*flash
           [:div.text-xs.opacity-70.px-1.pb-1 @*flash])
         (for [[k label] section-order
               :let [items (cond
                             (= k :due) (sort-by #(due->int (get-in % [:block/properties :due])) (get groups k))
                             (= k :followups) (sort-by #(or (due->int (get-in % [:block/properties :due])) 99999999) (get groups k))
                             :else (get groups k))]
               :when (seq items)]
           [:div {:key (name k)}
            [:div.flex.items-center.justify-between.px-1.pt-1.pb-1
             [:div.text-xs.uppercase.tracking-wide.opacity-40 (str label " (" (count items) ")")]
             (when (= k :carried)
               (ui/button {:size :xs
                           :on-click #(do (carry-over! items)
                                          (reset! *flash (str "Carried " (count items) " over to today"))
                                          (js/setTimeout (fn [] (reset! *flash nil)) 2500))}
                          "Carry over →"))]
            [:div.space-y-0.5
             (for [b items]
               (rum/with-key (todo-row b) (str (:block/uuid b))))]])])]]))

