(ns frontend.components.telemetry
  "Hatch telemetry: a live activity feed + a performance breakdown of the
  last (or in-progress) Hatch run. `telemetry-panel` is the right-sidebar
  entry (:telemetry in frontend.components.right-sidebar); the shared render
  helpers (activity-feed, perf-report, progress-bar) are reused by the Hatch
  modal (frontend.components.ingest).

  Data comes from scripts/hatch-all.js via electron.wiki: :wikiIngestProgress
  (<coop>/.roost/hatch-progress.json, written continuously during a run) and
  :wikiIngestMetrics (<coop>/.roost/hatch-metrics.json, written when a run
  finishes). Both are content-free — timing and token counts only, never
  prompt/response text — unless the run was started with \"Record LLM
  activity\", which adds short text previews to the feed."
  (:require [cljs-bean.core :as bean]
            [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.state :as state]
            [promesa.core :as p]
            [rum.core :as rum]
            [logseq.shui.ui :as ui]))

(def ^:private poll-ms 3000)

(defn- vault-root [] (config/get-repo-dir (state/get-current-repo)))

(defn- secs
  "Milliseconds -> seconds, one decimal place."
  [ms]
  (/ (js/Math.round (/ (or ms 0) 100)) 10))

(defn ago
  "Rough 'N minutes ago' for a millisecond epoch timestamp."
  [at]
  (when at
    (let [s (js/Math.round (/ (- (js/Date.now) at) 1000))]
      (cond
        (< s 60)   (str s "s ago")
        (< s 3600) (str (js/Math.round (/ s 60)) "m ago")
        (< s 86400) (str (js/Math.round (/ s 3600)) "h ago")
        :else      (str (js/Math.round (/ s 86400)) "d ago")))))

(rum/defc progress-bar
  < rum/static
  [{:keys [done total current]}]
  (let [total (or total 0)
        done  (or done 0)
        pct   (if (pos? total) (js/Math.round (* 100 (/ done total))) 0)]
    [:div.my-2
     [:div.text-sm.opacity-70
      (str "Hatching " done "/" (if (pos? total) total "…")
           (when (seq current) (str " — " current)))]
     [:div.mt-1.h-1.rounded {:class "bg-gray-06"}
      [:div.h-1.rounded {:class "bg-gray-11" :style {:width (str pct "%") :transition "width .3s"}}]]]))

(rum/defc activity-feed
  "The rolling per-LLM-call feed from hatch-progress.json. Pass it newest-first."
  < rum/static
  [rows]
  [:div.mt-2.rounded.p-2 {:class "bg-gray-02 max-h-52 overflow-y-auto text-xs font-mono"}
   (for [[i {:keys [phase label ms ok inTok outTok error preview reasoning]}] (map-indexed vector rows)]
     [:div.py-0.5 {:key i :class (when-not ok "text-red-500")}
      [:span.opacity-70 (or label phase)]
      [:span.opacity-40 (str "  " (secs ms) "s")]
      (when (or (pos? (or inTok 0)) (pos? (or outTok 0)))
        [:span.opacity-40 (str "  ↑" (or inTok 0) " ↓" (or outTok 0))])
      (when error [:span.text-red-500 (str "  " error)])
      (when reasoning [:div.opacity-40.italic.pl-3.whitespace-pre-wrap reasoning])
      (when preview [:div.opacity-70.pl-3.whitespace-pre-wrap preview])])])

(rum/defc perf-report
  "Timing/token breakdown from a run's :summary plus its per-file wall times.
  `per-file` is a seq of {:source :ms :ok}."
  < rum/static
  [summary per-file]
  (when summary
    (let [{:keys [totalCalls okCalls failedCalls wallLlmMs byPhase slowestCalls]} summary
          per-file (sort-by #(or (:ms %) 0) > per-file)]
      [:div.space-y-3
       [:div.opacity-70.text-sm
        (str (secs wallLlmMs) "s of LLM calls · " (or totalCalls 0) " call(s)"
             (when (and okCalls (pos? okCalls)) (str " · " okCalls " ok"))
             (when (and failedCalls (pos? failedCalls)) (str " · " failedCalls " failed")))]

       (when (seq byPhase)
         [:table.w-full.text-xs
          [:thead
           [:tr.opacity-50
            [:th.text-left.font-normal "phase"]
            [:th.text-right.font-normal "calls"]
            [:th.text-right.font-normal "total s"]
            [:th.text-right.font-normal "avg s"]
            [:th.text-right.font-normal "↑tok"]
            [:th.text-right.font-normal "↓tok"]]]
          [:tbody
           (for [[k {:keys [calls ms avgMs inputTokens outputTokens failures]}]
                 (sort-by (fn [[_ v]] (- (or (:ms v) 0))) byPhase)]
             [:tr {:key (str k)}
              [:td (name k)
               (when (and failures (pos? failures)) [:span.text-red-500 (str " (" failures " failed)")])]
              [:td.text-right calls]
              [:td.text-right (secs ms)]
              [:td.text-right (secs avgMs)]
              [:td.text-right (or inputTokens 0)]
              [:td.text-right (or outputTokens 0)]])]])

       (when (seq per-file)
         [:div
          [:div.opacity-50.text-xs.mb-1 "Per file — slowest first"]
          [:ul.list-none.pl-0.text-xs.m-0
           (for [[i {:keys [source ms ok]}] (map-indexed vector per-file)]
             [:li.py-0.5 {:key i :class (when-not ok "text-red-500")}
              [:span.opacity-50 (str (secs ms) "s  ")] source])]])

       (when (seq slowestCalls)
         [:div
          [:div.opacity-50.text-xs.mb-1 "Slowest calls"]
          [:ul.list-none.pl-0.text-xs.m-0
           (for [[i {:keys [label ms model]}] (map-indexed vector slowestCalls)]
             [:li.py-0.5 {:key i}
              [:span.opacity-50 (str (secs ms) "s  ")] label
              (when model [:span.opacity-40 (str "  " model)])])]])])))

(defn- load-metrics! [*metrics *loading?]
  (reset! *loading? true)
  (-> (ipc/ipc "wikiIngestMetrics" (vault-root))
      (p/then (fn [r] (reset! *metrics (bean/->clj r))))
      (p/catch (fn [_] nil))
      (p/finally (fn [] (reset! *loading? false)))))

(rum/defcs telemetry-panel
  < rum/reactive
  (rum/local nil ::metrics)
  (rum/local nil ::progress)
  (rum/local false ::loading?)
  (rum/local false ::was-running?)
  (rum/local nil ::poll-id)
  {:will-mount
   (fn [state]
     (load-metrics! (get state ::metrics) (get state ::loading?))
     (let [tick (fn []
                  (-> (ipc/ipc "wikiIngestProgress" (vault-root))
                      (p/then (fn [r]
                                (let [prog (bean/->clj r)
                                      *was (get state ::was-running?)]
                                  (reset! (get state ::progress) prog)
                                  ;; on the running -> idle edge, refresh the saved metrics
                                  (when (and @*was (not (:running prog)))
                                    (load-metrics! (get state ::metrics) (get state ::loading?)))
                                  (reset! *was (boolean (:running prog))))))
                      (p/catch (fn [_] nil))))]
       (tick)
       (reset! (get state ::poll-id) (js/setInterval tick poll-ms)))
     state)
   :will-unmount
   (fn [state]
     (when-let [id @(get state ::poll-id)] (js/clearInterval id))
     state)}
  [state]
  (let [*metrics  (get state ::metrics)
        *progress (get state ::progress)
        *loading? (get state ::loading?)
        metrics   @*metrics
        progress  @*progress
        running?  (:running progress)]
    [:div.flex.flex-col.px-2 {:style {:height "100%"}}
     [:div.flex-1.overflow-y-auto
      (cond
        running?
        [:div
         [:div.text-sm.opacity-70.mb-1 "Hatch running…"]
         (progress-bar progress)
         (when (seq (:activity progress))
           (activity-feed (reverse (:activity progress))))
         (when (:metrics progress)
           [:div.mt-3 (perf-report (:metrics progress) nil)])]

        metrics
        [:div
         [:div.text-xs.opacity-50.mb-2
          (str "Last run " (or (ago (:at metrics)) "—"))]
         (perf-report (:summary metrics) (:perFile metrics))]

        @*loading?
        [:div.text-sm.opacity-60.py-2 "Loading…"]

        :else
        [:div.text-sm.opacity-60.py-2
         "No hatch run recorded yet. Run "
         [:span.font-medium "Hatch sources"]
         " and its performance breakdown shows up here."])]

     [:div.flex.gap-2.p-2.border-t.border-gray-06
      (ui/button {:variant :ghost
                  :size :sm
                  :disabled @*loading?
                  :on-click #(load-metrics! *metrics *loading?)}
                 "Refresh")]]))
