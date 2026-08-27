(ns frontend.components.reminders
  "The Reminders panel (right-sidebar entry, see :reminders in
  frontend.components.right-sidebar). Lists upcoming and recently-fired
  reminders from <coop>/reminders.json; a fired reminder carries the prep
  brief Kip generated (related nest pages + a short LLM note), rendered here
  with real [[citations]]. Reminders are normally created through Peck (the
  reminders skill); the add field here is a convenience. Backed by the
  :wikiReminders* IPC channels (electron.wiki -> scripts/reminders.js).

  Firing happens in the main process — electron.reminders polls
  `reminders.js --due` every minute and pushes a \"reminder-fired\" event that
  opens this panel (frontend.electron.listener)."
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            [electron.ipc :as ipc]
            [frontend.components.block :as block]
            [frontend.config :as config]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [promesa.core :as p]
            [rum.core :as rum]))

(def ^:private poll-ms 30000)

(defn- vault-root [] (config/get-repo-dir (state/get-current-repo)))

(defn ding!
  "A short two-note chime for a firing reminder — synthesized (no asset), so it
  works regardless of OS notification-sound settings. Called from
  electron.listener on a \"reminder-fired\" push whose :sound is true."
  []
  (try
    (let [Ctx (or (.-AudioContext js/window) (.-webkitAudioContext js/window))
          ctx (Ctx.)
          t0 (.-currentTime ctx)
          note (fn [freq start dur]
                 (let [osc (.createOscillator ctx)
                       g (.createGain ctx)]
                   (set! (.-type osc) "sine")
                   (.setValueAtTime (.-frequency osc) freq (+ t0 start))
                   (.setValueAtTime (.-gain g) 0.0001 (+ t0 start))
                   (.exponentialRampToValueAtTime (.-gain g) 0.25 (+ t0 start 0.015))
                   (.exponentialRampToValueAtTime (.-gain g) 0.0001 (+ t0 start dur))
                   (.connect osc g)
                   (.connect g (.-destination ctx))
                   (.start osc (+ t0 start))
                   (.stop osc (+ t0 start dur 0.02))))]
      (note 880 0 0.18)
      (note 1174 0.16 0.34)
      (js/setTimeout #(.close ctx) 900))
    (catch :default _ nil)))

(defn- when-label [iso]
  (let [d (js/Date. iso)
        now (js/Date.)
        diff-min (js/Math.round (/ (- (.getTime d) (.getTime now)) 60000))
        abs (.toLocaleString d js/undefined #js {:weekday "short" :day "numeric" :month "short"
                                                 :hour "2-digit" :minute "2-digit"})]
    (cond
      (< diff-min -1) abs
      (<= diff-min 0) "now"
      (< diff-min 60) (str "in " diff-min " min")
      (< diff-min 1440) (str "in " (js/Math.round (/ diff-min 60)) "h")
      :else abs)))

(defn- lead-label [min]
  (cond
    (and (pos? min) (zero? (mod min 1440))) (str (quot min 1440) "d")
    (and (pos? min) (zero? (mod min 60))) (str (quot min 60) "h")
    :else (str min "m")))

(defn- fetch! [*rows *loading?]
  (reset! *loading? true)
  (-> (ipc/ipc "wikiRemindersList" (vault-root))
      (p/then (fn [r] (reset! *rows (vec (:reminders (bean/->clj r))))))
      (p/catch (fn [_] (reset! *rows [])))
      (p/finally (fn [] (reset! *loading? false)))))

(defn- add! [*input refresh]
  (let [text (string/trim @*input)]
    (when-not (string/blank? text)
      (reset! *input "")
      (-> (ipc/ipc "wikiRemindersAdd" (vault-root) text)
          (p/finally refresh)))))

(defn- cancel! [id refresh]
  (-> (ipc/ipc "wikiRemindersCancel" (vault-root) id)
      (p/finally refresh)))

(defn- set-sound! [id on? refresh]
  (-> (ipc/ipc "wikiRemindersMute" (vault-root) id on?)
      (p/finally refresh)))

(rum/defc reminder-row < rum/static
  [{:keys [id title eventAt leadMin status context sound]} refresh]
  (let [muted? (false? sound)]
    [:div.py-2.px-1.rounded.group {:class "hover:bg-gray-03"}
     [:div.flex.items-baseline.gap-2
      [:div.flex-1.min-w-0
       [:div.text-sm.font-medium.truncate title]
       [:div.text-xs.opacity-50.tabular-nums
        (when-label eventAt) "  ·  remind " (lead-label leadMin) " before"
        (when muted? "  ·  silent")
        (when (= status "notified") "  ·  notified")]]
      (ui/button {:icon (if muted? "bell-off" "bell") :icon-props {:size 14} :variant :ghost :size :xs
                  :class (if muted? "opacity-60" "opacity-0 group-hover:opacity-100")
                  :title (if muted? "Unmute" "Mute") :on-click #(set-sound! id muted? refresh)})
      (when (= status "pending")
        (ui/button {:icon "x" :icon-props {:size 14} :variant :ghost :size :xs
                    :class "opacity-0 group-hover:opacity-100"
                    :title "Cancel" :on-click #(cancel! id refresh)}))]
     (when (and (= status "notified") (not (string/blank? context)))
       [:div.mt-1.prose.prose-sm.max-w-none.text-sm
        (block/inline-text {} :markdown context)])]))

(rum/defcs reminders-panel
  < rum/reactive
  (rum/local nil ::rows)
  (rum/local false ::loading?)
  (rum/local "" ::input)
  (rum/local nil ::poll-id)
  {:will-mount (fn [state]
                 (fetch! (::rows state) (::loading? state))
                 (reset! (::poll-id state)
                         (js/setInterval #(fetch! (::rows state) (::loading? state)) poll-ms))
                 state)
   :will-unmount (fn [state]
                   (when-let [id @(::poll-id state)] (js/clearInterval id))
                   state)}
  [state]
  (let [*rows (::rows state)
        *loading? (::loading? state)
        *input (::input state)
        rows @*rows
        refresh #(fetch! *rows *loading?)
        now (.getTime (js/Date.))
        pending (->> rows (filter #(= (:status %) "pending"))
                     (sort-by #(js/Date.parse (:eventAt %))))
        recent (->> rows (filter #(= (:status %) "notified"))
                    (filter #(> (js/Date.parse (or (:notifiedAt %) (:eventAt %))) (- now (* 3 86400000))))
                    (sort-by #(- (js/Date.parse (or (:notifiedAt %) (:eventAt %))))))]
    [:div.flex.flex-col {:style {:height "100%"}}
     [:div.flex.gap-2.px-1.pb-2
      [:input.form-input.is-small.flex-1.text-sm
       {:type "text" :placeholder "e.g. call with Sam Friday 15h"
        :value @*input
        :on-change #(reset! *input (.. % -target -value))
        :on-key-down (fn [e] (when (= "Enter" (.-key e)) (add! *input refresh)))}]
      (ui/button {:size :xs :on-click #(add! *input refresh)} "Add")]
     [:div.flex-1.overflow-y-auto {:class "overflow-x-hidden"}
      (cond
        (and (nil? rows) @*loading?) [:div.text-xs.opacity-50.px-2.py-4 "Loading…"]

        (and (some? rows) (empty? pending) (empty? recent))
        [:div.text-sm.opacity-50.px-2.py-6.leading-relaxed
         "No reminders. Tell Peck about something coming up — "
         [:span.italic "\"I have a meeting with Acme on Friday at 15h\""] " — and Kip "
         "will remind you beforehand with context from your nest."]

        :else
        [:<>
         (when (seq pending)
           [:div
            [:div.text-xs.uppercase.tracking-wide.opacity-40.px-1.pt-1.pb-1 "Upcoming"]
            (for [r pending] (rum/with-key (reminder-row r refresh) (:id r)))])
         (when (seq recent)
           [:div.mt-3
            [:div.text-xs.uppercase.tracking-wide.opacity-40.px-1.pt-1.pb-1 "Recent"]
            (for [r recent] (rum/with-key (reminder-row r refresh) (:id r)))])])]]))
