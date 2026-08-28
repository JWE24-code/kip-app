(ns frontend.components.ingest
  "The Hatch workflow's UI: turns new-or-changed files in eggs/, journals/
  and pages/ into nest pages, in batches, with no per-file review. Backed by
  scripts/hatch-all.js via the :wikiIngestPreview / :wikiIngestBatch /
  :wikiIngestProgress IPC channels (electron.wiki shells out — same
  one-code-path approach as the Coop status and Peck panels).

  The modal also surfaces the run's telemetry — a live activity feed and a
  Performance breakdown — via the shared helpers in
  frontend.components.telemetry (which also backs the right-sidebar
  \"Hatch telemetry\" pane). Tick \"Record LLM activity\" to also stream each
  call's response/reasoning text to <coop>/.roost/hatch-trace.jsonl."
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            [electron.ipc :as ipc]
            [frontend.components.drop-source :as drop-source]
            [frontend.components.llm-banner :as llm-banner]
            [frontend.components.telemetry :as telemetry]
            [frontend.config :as config]
            [frontend.handler.coop :as coop]
            [frontend.handler.llm :as llm-handler]
            [frontend.handler.notification :as notification]
            [frontend.state :as state]
            [frontend.util :as util]
            [promesa.core :as p]
            [rum.core :as rum]
            [logseq.shui.ui :as ui]))

(def ^:private batch-size 10)
(def ^:private poll-ms 1000)

(defn- vault-root [] (config/get-repo-dir (state/get-current-repo)))

(defn- load-preview! [*preview *error *busy?]
  (reset! *busy? true)
  (reset! *error nil)
  (-> (ipc/ipc "wikiIngestPreview" (vault-root))
      (p/then (fn [r] (reset! *preview (bean/->clj r))))
      (p/catch (fn [e] (reset! *error (str e))))
      (p/finally (fn [] (reset! *busy? false)))))

(defn- pick-eggs! [*preview *error *busy?]
  (-> (ipc/ipc "wikiPickEggs" (vault-root))
      (p/then (fn [r]
                (let [{:keys [canceled added duplicates rejected]} (bean/->clj r)]
                  (when-not canceled
                    (when (seq added)
                      (notification/show!
                       (str "Added " (string/join ", " added) " to eggs/.") :success true))
                    (when (seq duplicates)
                      (notification/show!
                       (str (string/join ", " duplicates) " already in your coop.") :info true))
                    (when (seq rejected)
                      (notification/show!
                       (str "Skipped " (count rejected) " file(s) — Kip reads Markdown and text only.")
                       :warning true))
                    (when (or (seq added) (seq duplicates))
                      (coop/refresh-counts!)
                      (load-preview! *preview *error *busy?))))))
      (p/catch (fn [e] (notification/show! (str "Couldn't add sources: " e) :error true)))))

(defn- start-poll! [*progress *poll-id]
  (let [tick #(-> (ipc/ipc "wikiIngestProgress" (vault-root))
                  (p/then (fn [r] (reset! *progress (bean/->clj r))))
                  (p/catch (fn [_] nil)))]
    (tick)
    (reset! *poll-id (js/setInterval tick poll-ms))))

(defn- stop-poll! [*progress *poll-id]
  (when-let [id @*poll-id] (js/clearInterval id))
  (reset! *poll-id nil)
  (reset! *progress nil))

(defn- run-batch! [{:keys [*done *remaining *error *busy? *progress *poll-id *metrics *trace? *classic?]}]
  (reset! *busy? true)
  (reset! *error nil)
  (reset! *progress nil)
  (start-poll! *progress *poll-id)
  (-> (ipc/ipc "wikiIngestBatch" (vault-root) batch-size (boolean @*trace?) (boolean @*classic?))
      (p/then (fn [r]
                (let [{:keys [hatched failed remaining metrics]} (bean/->clj r)]
                  (swap! *done (fn [d] {:hatched (into (:hatched d) hatched)
                                        :failed  (into (:failed d) failed)}))
                  (reset! *remaining remaining)
                  (reset! *metrics metrics)
                  (coop/refresh-counts!))))
      (p/catch (fn [e] (reset! *error (str e))))
      (p/finally (fn []
                   (stop-poll! *progress *poll-id)
                   (reset! *busy? false)))))

(defn- per-file
  "The {:source :ms :ok} rows perf-report wants, from the modal's done state."
  [done]
  (concat (map (fn [h] {:source (:source h) :ms (:ms h) :ok true}) (:hatched done))
          (map (fn [f] {:source (:source f) :ms (:ms f) :ok false}) (:failed done))))

(rum/defc source-line
  < rum/static
  [{:keys [source kind results]}]
  [:li.text-sm.py-1
   (when kind [:span.text-xs.opacity-50.mr-1 (str "[" kind "]")])
   [:span.font-medium source]
   (when (seq results)
     (str " — " (string/join ", "
                             (for [{:keys [action slug]} results]
                               (str (if (= action "create") "created " "updated ") slug)))))])

(rum/defc checkbox-row
  < rum/static
  [label checked? on-change]
  [:label.flex.items-center.gap-2.text-sm.opacity-80.my-1.cursor-pointer
   [:input {:type "checkbox" :checked checked? :on-change on-change}]
   label])

(rum/defcs hatch-modal
  < rum/reactive
  (rum/local nil ::preview)
  (rum/local {:hatched [] :failed []} ::done)
  (rum/local nil ::remaining)
  (rum/local nil ::error)
  (rum/local false ::busy?)
  (rum/local nil ::progress)
  (rum/local nil ::poll-id)
  (rum/local nil ::metrics)
  (rum/local false ::trace?)
  (rum/local false ::classic?)
  {:will-mount   (fn [state]
                   (load-preview! (get state ::preview) (get state ::error) (get state ::busy?))
                   (llm-handler/refresh!)
                   state)
   :will-unmount (fn [state]
                   (stop-poll! (get state ::progress) (get state ::poll-id))
                   state)}
  [state _close-fn]
  (let [*preview   (get state ::preview)
        *done      (get state ::done)
        *remaining (get state ::remaining)
        *error     (get state ::error)
        *busy?     (get state ::busy?)
        *progress  (get state ::progress)
        *metrics   (get state ::metrics)
        *trace?    (get state ::trace?)
        *classic?  (get state ::classic?)
        ctx        {:*done *done :*remaining *remaining :*error *error :*busy? *busy?
                    :*progress *progress :*poll-id (get state ::poll-id)
                    :*metrics *metrics :*trace? *trace? :*classic? *classic?}
        preview    @*preview
        done       @*done
        remaining  @*remaining
        started?   (or (seq (:hatched done)) (seq (:failed done)) (some? remaining))
        pending-n  (if started? (or remaining 0) (count (:pending preview)))]
    (drop-source/drop-zone
     {:on-added (fn [_] (load-preview! *preview *error *busy?))}
     [:div.w-full.mx-auto {:class "md:max-w-[600px]"}
      [:h2#modal-headline.text-xl.mb-3 "Hatch sources"]
     [:p.text-sm.opacity-70.mb-3
      "Turns new or changed files in " [:code "eggs/"] ", " [:code "journals/"] " and "
      [:code "pages/"] " into nest pages — no per-file review. Runs in batches of "
      (str batch-size) "."]

     (when-not @*busy?
       [:div.mb-3
        (ui/button {:variant :outline :size :sm
                    :on-click #(pick-eggs! *preview *error *busy?)}
                   "Add source…")
        [:span.text-xs.opacity-50.ml-2 "or drop a file here"]])

     (llm-banner/provider-banner)

     (when @*error
       [:div.my-2 (llm-banner/error-view @*error)])

     (cond
       (and @*busy? (nil? preview))
       [:div.text-sm.opacity-60.my-2 "Scanning sources…"]

       @*busy?
       (if-let [prog (when (:running @*progress) @*progress)]
         [:div
          (telemetry/progress-bar prog)
          (when (seq (:activity prog))
            (telemetry/activity-feed (reverse (:activity prog))))]
         [:div.text-sm.opacity-60.my-2 "Starting…"])

       (nil? preview)
       (when @*error
         (ui/button {:on-click #(load-preview! *preview *error *busy?)} "Retry"))

       :else
       [:div
        (when (seq (:hatched done))
          (let [new-slugs (->> (:hatched done)
                               (mapcat :results)
                               (filter #(= "create" (:action %)))
                               (map :slug)
                               distinct)]
            [:div.mb-3
             [:h3.text-lg.font-medium.mb-1 (str "Hatched " (count (:hatched done)))]
             [:ul.list-disc.pl-5
              (for [[i it] (map-indexed vector (:hatched done))]
                (rum/with-key (source-line it) i))]
             (when (and (not @*busy?) (seq new-slugs))
               [:div.mt-2
                (ui/button
                 {:variant :outline :size :sm
                  :on-click #(state/pub-event! [:peck/prefill
                                                (str "What's in [[" (first new-slugs) "]]?")])}
                 "Ask Kip about them →")])]))

        (when (seq (:failed done))
          [:div.mb-3
           [:h3.text-lg.font-medium.mb-1.text-red-500 (str "Failed " (count (:failed done)))]
           [:ul.list-disc.pl-5
            (for [[i {:keys [source error]}] (map-indexed vector (:failed done))]
              [:li.text-sm.py-1 {:key i} [:span.font-medium source] " — " error])]])

        (cond
          (and started? (zero? pending-n))
          [:div.text-sm.opacity-70.my-2 "All caught up — nothing left to hatch."]

          (zero? pending-n)
          [:div.text-sm.opacity-60.my-2 "Nothing new to hatch."]

          :else
          [:div.my-2
           (when-not started?
             [:div.text-sm.opacity-70.mb-2
              (str (count (:pending preview)) " file(s) pending, ~" (:totalKb preview) " KB total.")
              [:ul.list-disc.pl-5.mt-1 {:class "max-h-40 overflow-y-auto"}
               (for [[i {:keys [source kind kb]}] (map-indexed vector (:pending preview))]
                 [:li.text-xs.opacity-70 {:key i} (str "[" kind "] " source " (" kb " KB)")])]])
           (checkbox-row "Record LLM activity (thinking + timings)" @*trace? #(swap! *trace? not))
           (checkbox-row "Classic mode — one LLM call per page (slower; for comparison)" @*classic? #(swap! *classic? not))
           (ui/button
            {:on-click #(run-batch! ctx)
             :disabled @*busy?}
            (str (if started? "Hatch next " "Start — hatch ")
                 (min batch-size pending-n)
                 (when started? (str " (" pending-n " left)"))))])

        (when (and (not @*busy?) @*metrics)
          [:details.mt-3.text-sm
           [:summary.cursor-pointer.opacity-70 "Performance"]
           [:div.mt-2 (telemetry/perf-report @*metrics (per-file done))]])

        (when (seq (:oversized preview))
          [:details.mt-3.text-sm.opacity-60
           [:summary (str (count (:oversized preview)) " file(s) skipped — too large for the model's context (~1 MB+)")]
           [:ul.list-disc.pl-5.mt-1
            (for [[i {:keys [source kb]}] (map-indexed vector (:oversized preview))]
              [:li.text-xs {:key i} (str source " (" kb " KB)")])]])

        (when (seq (:empty preview))
          [:div.text-xs.opacity-50.mt-2
           (str (count (:empty preview)) " near-empty file(s) skipped.")])])])))

(defn show-hatch-modal! [e]
  (state/set-modal! hatch-modal)
  (when e (util/stop e)))
