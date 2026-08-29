(ns frontend.components.groom-settings
  "The 'schedule the deep groom' control — rendered in the Coop status panel
  (frontend.components.wiki-status), under 'Schedule'. Backed by the
  :getGroomSchedule / :setGroomSchedule IPC (electron.groom-scheduler, main
  process). The schedule is global, not per-graph."
  (:require [cljs-bean.core :as bean]
            [electron.ipc :as ipc]
            [frontend.util :as util]
            [promesa.core :as p]
            [rum.core :as rum]))

(def ^:private days ["Sunday" "Monday" "Tuesday" "Wednesday" "Thursday" "Friday" "Saturday"])

(defn- load! [*state]
  (-> (ipc/ipc "getGroomSchedule")
      (p/then (fn [r] (reset! *state (bean/->clj r))))
      (p/catch (fn [_] nil))))

(defn- save! [*state patch]
  (let [{:keys [enabled day time]} (merge @*state patch)]
    (swap! *state merge patch)
    (-> (ipc/ipc "setGroomSchedule" (clj->js {:enabled enabled :day day :time time}))
        (p/then (fn [r] (reset! *state (bean/->clj r))))
        (p/catch (fn [_] nil)))))

(defn- when-str [ms]
  (when (number? ms)
    (try (.toLocaleString (js/Date. ms) js/undefined
                          #js {:weekday "short" :month "short" :day "numeric"
                               :hour "2-digit" :minute "2-digit"})
         (catch :default _ nil))))

(rum/defcs schedule-row
  < rum/reactive
    (rum/local nil ::s)
    {:will-mount (fn [state] (load! (::s state)) state)}
  [state]
  (let [*s (::s state)
        {:keys [enabled day time lastRun nextRun]} @*s]
    (when @*s
      [:div.it.my-2
       [:label.flex.items-center.gap-2.text-sm.opacity-80.my-1.cursor-pointer
        [:input {:type "checkbox" :checked (boolean enabled)
                 :on-change #(save! *s {:enabled (not enabled)})}]
        "Run the deep groom on a schedule"]
       (when enabled
         [:div.ml-6.mt-1.flex.flex-wrap.items-center.gap-2.text-sm
          [:span.opacity-70 "Every"]
          [:select.form-select.is-small
           {:value (str (or day 3))
            :on-change #(save! *s {:day (js/parseInt (util/evalue %) 10)})}
           (for [[i d] (map-indexed vector days)]
             [:option {:key i :value i} d])]
          [:span.opacity-70 "at"]
          [:input.form-input.is-small {:type "time" :value (or time "03:00")
                                       :style {:width "7rem"}
                                       :on-change #(save! *s {:time (util/evalue %)})}]])
       (when enabled
         [:div.ml-6.text-xs.opacity-60.mt-1
          "Next: " (or (when-str nextRun) "—")
          (when lastRun (str "  ·  last run " (when-str lastRun)))
          ". Runs on the next launch if Kip was closed at that time."])])))
