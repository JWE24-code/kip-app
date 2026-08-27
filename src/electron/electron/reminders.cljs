(ns electron.reminders
  "The reminders scheduler — the one long-lived piece of the reminders feature.
  Every 60s (and once ~5s after launch, for catch-up) it asks
  scripts/reminders.js --due which pending reminders have reached their lead
  time, for every open graph. reminders.js retrieves nest context, drafts a
  short prep brief, marks each reminder notified, and returns them; this ns
  turns each into an OS notification + a push to the renderer so the Reminders
  panel opens.

  Mirrors electron.git/configure-auto-commit! — a plain setInterval in the
  main process; nothing fires while Kip is closed (a reminder that came due
  meanwhile fires on the next launch's catch-up tick)."
  (:require ["electron" :refer [Notification]]
            [clojure.string :as string]
            [electron.logger :as logger]
            [electron.state :as state]
            [electron.utils :as utils]
            [electron.window :as win]
            [electron.wiki :as wiki]
            [promesa.core :as p]))

(def log-error (partial logger/error "[Reminders]"))

(defonce ^:private *interval (atom nil))

(defn- first-line [s]
  (some-> s str string/split-lines (->> (map string/trim) (remove string/blank?) first)))

(defn- notif-body [{:keys [eventAt context relatedSlugs]}]
  (let [when-str (try (let [d (js/Date. eventAt)]
                        (.toLocaleString d js/undefined #js {:weekday "short" :hour "2-digit" :minute "2-digit"}))
                      (catch :default _ nil))
        gist (or (first-line context)
                 (when (seq relatedSlugs) (str "Related: " (string/join ", " (take 3 relatedSlugs)))))]
    (->> [when-str gist] (remove string/blank?) (string/join " · "))))

(defn- fire! [{:keys [id title sound] :as reminder}]
  (let [ding? (not (false? sound))]      ; default on; only an explicit false mutes
    (logger/info "[Reminders]" (str "firing #" id " — " title (when-not ding? " (silent)")))
    ;; in-app toast (visible when a window is open)
    (utils/send-to-renderer "notification" {:type "info" :payload (str "⏰ " title " — " (notif-body reminder))})
    ;; OS notification — `silent` controls the system notification sound
    (when (and Notification (.-isSupported Notification))
      (let [n (Notification. #js {:title (str "⏰ " title) :body (notif-body reminder) :silent (not ding?)})]
        (.on n "click" (fn []
                         (when-let [w @utils/*win] (win/switch-to-window! w))
                         (utils/send-to-renderer "reminder-fired" {:id id :sound ding?})))
        (.show n)))
    ;; always tell the renderer — opens the panel and (when ding?) plays a chime
    (utils/send-to-renderer "reminder-fired" {:id id :sound ding?})))

(defn- tick! []
  (doseq [graph-path (state/get-all-graph-paths)]
    (when (string? graph-path)
      (-> (wiki/reminders-due! graph-path)
          (p/then (fn [^js result]
                    (doseq [r (js->clj (or (.-fired result) #js []) :keywordize-keys true)]
                      (fire! r))))
          (p/catch (fn [err] (log-error (str "due check failed for " graph-path ": " err))))))))

(defn start-scheduler!
  "Idempotent — safe to call again on graph switch."
  []
  (when @*interval (js/clearInterval @*interval))
  (js/setTimeout tick! 5000)
  (reset! *interval (js/setInterval tick! 60000))
  (logger/info "[Reminders]" "scheduler started (60s)"))

(defn stop-scheduler! []
  (when @*interval (js/clearInterval @*interval))
  (reset! *interval nil))
