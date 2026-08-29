(ns electron.groom-scheduler
  "Runs the deep groom (scripts/groom.js --deep) on a weekly schedule the user
  sets in Settings — a day of the week and a time. Like electron.reminders it's
  a plain setInterval in the main process: nothing fires while Kip is closed,
  but the next tick after launch runs a slot that came due meanwhile (\"as soon
  as possible after\").

  Config (electron.configs, global — the schedule isn't per-graph):
    :groom/schedule  {:enabled bool :day 0..6 (Sun..Sat) :time \"HH:MM\"}
    :groom/last-run  epoch ms of the last run we started"
  (:require ["electron" :refer [Notification]]
            [electron.configs :as cfgs]
            [electron.logger :as logger]
            [electron.state :as state]
            [electron.utils :as utils]
            [electron.wiki :as wiki]
            [promesa.core :as p]))

(def ^:private log-error (partial logger/error "[GroomSchedule]"))

(defonce ^:private *interval (atom nil))
(defonce ^:private *running? (atom false))

(def ^:private week-ms (* 7 24 60 60 1000))

(defn- parse-hhmm [s]
  (when-let [[_ h m] (re-matches #"(\d{1,2}):(\d{2})" (str s))]
    (let [h (js/parseInt h 10) m (js/parseInt m 10)]
      (when (and (<= 0 h 23) (<= 0 m 59)) [h m]))))

(defn- prev-occurrence
  "Epoch ms of the most recent `day`@`time` at or before `now`, or nil if the
  schedule is malformed."
  [day time-str now]
  (when-let [[h m] (parse-hhmm time-str)]
    (let [d (js/Date. now)
          _ (.setHours d h m 0 0)
          diff (mod (- (.getDay d) day) 7)        ; days to step back to `day`
          cand (- (.getTime d) (* diff 24 60 60 1000))]
      (if (<= cand now) cand (- cand (* 7 24 60 60 1000))))))

(defn next-occurrence [day time-str now]
  (some-> (prev-occurrence day time-str now) (+ week-ms)))

(defn- fire! []
  (reset! *running? true)
  (cfgs/set-item! :groom/last-run (js/Date.now))
  (let [graphs (filter string? (state/get-all-graph-paths))]
    (logger/info "[GroomSchedule]" (str "deep groom for " (count graphs) " graph(s)"))
    (-> (p/all (for [g graphs]
                 (-> (wiki/groom-deep! g)
                     (p/catch (fn [err] (log-error (str "deep groom failed for " g ": " err)) nil)))))
        (p/finally
          (fn [_]
            (reset! *running? false)
            (utils/send-to-renderer "notification"
                                    {:type "info"
                                     :payload "Scheduled deep groom finished — see Coop status."})
            (when (and Notification (.-isSupported Notification))
              (.show (Notification. #js {:title "Kip — deep groom"
                                         :body "The scheduled deep groom finished. Open Coop status for the report."
                                         :silent true}))))))))

(defn- tick! []
  (let [{:keys [enabled day time]} (some-> (cfgs/get-item :groom/schedule)
                                           (js->clj :keywordize-keys true))]
    (when (and enabled (not @*running?) (number? day) time)
      (when-let [prev (prev-occurrence day time (js/Date.now))]
        (when (> prev (or (cfgs/get-item :groom/last-run) 0))
          (fire!))))))

(defn schedule-info
  "The renderer's view: current schedule + last/next run (epoch ms or nil)."
  []
  (let [{:keys [enabled day time] :as sched} (some-> (cfgs/get-item :groom/schedule)
                                                     (js->clj :keywordize-keys true))]
    #js {:enabled (boolean enabled)
         :day     (if (number? day) day 3)          ; default Wednesday
         :time    (or time "03:00")
         :lastRun (or (cfgs/get-item :groom/last-run) nil)
         :nextRun (when (and enabled (number? day) time)
                    (next-occurrence day time (js/Date.now)))
         :configured (some? sched)}))

(defn set-schedule!
  "Persist a schedule from the renderer. Marks now as the last run when the
  schedule is first turned on, so an already-passed slot this week doesn't
  fire immediately."
  [{:strs [enabled day time]}]
  (let [enabled (boolean enabled)
        day (if (number? day) (int day) 3)
        time (or (and (parse-hhmm time) time) "03:00")
        was (cfgs/get-item :groom/schedule)]
    (cfgs/set-item! :groom/schedule {:enabled enabled :day day :time time})
    (when (and enabled (not (:enabled (some-> was (js->clj :keywordize-keys true)))))
      (cfgs/set-item! :groom/last-run (js/Date.now)))
    (schedule-info)))

(defn start-scheduler! []
  (when @*interval (js/clearInterval @*interval))
  (js/setTimeout tick! 8000)
  (reset! *interval (js/setInterval tick! 60000))
  (logger/info "[GroomSchedule]" "scheduler started (60s)"))

(defn stop-scheduler! []
  (when @*interval (js/clearInterval @*interval))
  (reset! *interval nil))
