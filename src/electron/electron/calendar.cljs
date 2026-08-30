(ns electron.calendar
  "Calendar-subscription scheduler (kip-app#70). A slow timer (every 20 min,
  and once ~12s after launch for catch-up) shells scripts/calendar.js sync
  for every open graph: fetch each subscribed ICS feed, expand the upcoming
  window, and reconcile it into <coop>/reminders.json as `source: \"calendar\"`
  rows.

  It does no notifying of its own — the ordinary reminders scheduler
  (electron.reminders, 60s) fires the calendar-sourced reminders exactly like
  hand-typed ones. This ns only keeps reminders.json current.

  Same shape as electron.reminders / electron.groom-scheduler: a plain
  setInterval in the main process; nothing runs while Kip is closed and the
  next launch's catch-up tick brings the window up to date."
  (:require [electron.logger :as logger]
            [electron.state :as state]
            [electron.wiki :as wiki]
            [promesa.core :as p]))

(def ^:private log-error (partial logger/error "[Calendar]"))

(defonce ^:private *interval (atom nil))

(def ^:private sync-interval-ms (* 20 60 1000))
(def ^:private catch-up-delay-ms 12000)

(defn- tick! []
  (doseq [graph-path (state/get-all-graph-paths)]
    (when (string? graph-path)
      (-> (wiki/calendar-sync! graph-path)
          (p/then (fn [^js result]
                    (let [r (js->clj result :keywordize-keys true)
                          {:keys [added updated removed]} (:reconciled r)]
                      (when (some pos? [(or added 0) (or updated 0) (or removed 0)])
                        (logger/info "[Calendar]"
                                     (str graph-path " — reminders +" added " ~" updated " -" removed)))
                      (doseq [e (:errors r)]
                        (logger/warn "[Calendar]" (str graph-path " — " e))))))
          (p/catch (fn [err] (log-error (str "sync failed for " graph-path ": " err))))))))

(defn start-scheduler!
  "Idempotent — safe to call again on graph switch."
  []
  (when @*interval (js/clearInterval @*interval))
  (js/setTimeout tick! catch-up-delay-ms)
  (reset! *interval (js/setInterval tick! sync-interval-ms))
  (logger/info "[Calendar]" "scheduler started (20m)"))

(defn stop-scheduler! []
  (when @*interval (js/clearInterval @*interval))
  (reset! *interval nil))

;; A one-shot sync for the settings panel — after adding/removing a feed the
;; user shouldn't wait 20 min to see events land.
(defn sync-now! [graph-path]
  (wiki/calendar-sync! graph-path))
