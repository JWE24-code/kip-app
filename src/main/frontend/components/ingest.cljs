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
            [frontend.components.coop-glossary :as glossary]
            [frontend.components.drop-source :as drop-source]
            [frontend.components.llm-banner :as llm-banner]
            [frontend.components.paste-source :as paste-source]
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

;; --- "Review before writing" mode ----------------------------------------
;; One file at a time: propose its pages (LLM), let the user keep/skip each,
;; then commit. hatch-all.js --propose-next / --commit-next; the plan is
;; stashed in .roost/hatch-plan.json between the two. `skip` steps past files
;; that failed to propose (a committed file just drops out of the pending
;; scan, so it usually stays 0).

(declare review-next!)

(defn- review-record! [*done proposal res]
  (let [{:keys [source results error keptNone skipped]} res]
    (swap! *done (fn [d]
                   (cond
                     error   (update d :failed conj {:source source :error error})
                     keptNone d
                     :else   (update d :hatched conj {:source source :kind (:kind proposal)
                                                      :results results :skipped (or skipped [])}))))))

(defn- review-commit! [{:keys [*rp *done] :as ctx} keep-all?]
  (let [{:keys [proposal keeps]} @*rp
        keep-slugs (when-not keep-all?
                     (vec (filter keeps (map :slug (:plan proposal)))))]
    (swap! *rp assoc :phase :committing)
    (-> (ipc/ipc "wikiIngestCommitNext" (vault-root)
                 (when-not keep-all? (clj->js (or keep-slugs []))))
        (p/then (fn [r]
                  (review-record! *done proposal (bean/->clj r))
                  (coop/refresh-counts!)))
        (p/catch (fn [e]
                   (swap! *done update :failed conj {:source (:source proposal) :error (str e)})))
        (p/finally (fn [] (review-next! ctx))))))

(defn- review-next! [{:keys [*rp *classic? *preview *error *busy?] :as ctx}]
  (swap! *rp assoc :phase :proposing :proposal nil :error nil)
  (-> (ipc/ipc "wikiIngestProposeNext" (vault-root) batch-size (get @*rp :skip 0) (boolean @*classic?))
      (p/then (fn [r]
                (let [{:keys [done whiteboard plan] :as res} (bean/->clj r)]
                  (cond
                    done       (do (swap! *rp assoc :phase :done)
                                   (load-preview! *preview *error *busy?))
                    whiteboard (do (swap! *rp assoc :proposal res) (review-commit! ctx true))
                    :else      (swap! *rp assoc
                                      :phase :reviewing
                                      :proposal res
                                      :keeps (set (map :slug plan)))))))
      (p/catch (fn [e]
                 (swap! *rp assoc :phase :reviewing :proposal nil :error (str e))))))

(defn- review-start! [ctx]
  (reset! (:*done ctx) {:hatched [] :failed []})
  (reset! (:*rp ctx) {:skip 0})
  (review-next! ctx))

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

(rum/defc review-panel
  "The per-file plan review shown while ::rp is active. `rp` is its state map."
  [rp {:keys [*rp] :as ctx}]
  (let [{:keys [phase proposal keeps error]} rp
        {:keys [source relPath plan remaining]} proposal]
    [:div.my-2
     (case phase
       :proposing  [:div.text-sm.opacity-70 "Proposing pages for " [:span.font-medium (or source "the next file")] "…"]
       :committing [:div.text-sm.opacity-70 "Writing…"]
       :reviewing
       [:div
        (if error
          [:div
           [:div.my-1 (llm-banner/error-view error)]
           [:div.flex.gap-2.mt-2
            (ui/button {:variant :outline :size :sm
                        :on-click #(do (swap! *rp update :skip inc) (review-next! ctx))}
                       "Skip this file →")
            (ui/button {:variant :ghost :size :sm :on-click #(reset! *rp {:skip 0 :phase :done})}
                       "Stop review")]]
          [:div
           [:div.text-sm.mb-1
            [:span.font-medium source]
            (when relPath [:span.text-xs.opacity-50.ml-1 (str "(" relPath ")")])
            (when (and (number? remaining) (pos? remaining))
              [:span.text-xs.opacity-50.ml-1 (str "· " remaining " more after this")])]
           (if (empty? plan)
             [:div.text-sm.opacity-70.my-2 "No pages proposed for this file."]
             [:ul.my-1
              (for [{:keys [slug title type action summary]} plan]
                [:li.text-sm.py-1 {:key slug}
                 [:label.flex.items-start.gap-2.cursor-pointer
                  [:input.mt-1 {:type "checkbox"
                                :checked (boolean (keeps slug))
                                :on-change #(swap! *rp update :keeps
                                                   (fn [ks] ((if (keeps slug) disj conj) (or ks #{}) slug)))}]
                  [:span
                   [:span.font-medium title]
                   [:span.text-xs.opacity-50.ml-1 (str "[" type " · " action "]")]
                   (when-not (string/blank? summary)
                     [:div.text-xs.opacity-60 summary])]]])])
           [:div.flex.gap-2.mt-2
            (ui/button {:size :sm :disabled (or (empty? plan) (empty? keeps))
                        :on-click #(review-commit! ctx false)}
                       (str "Write " (count keeps) (if (= 1 (count keeps)) " page" " pages") " →"))
            (ui/button {:variant :outline :size :sm
                        :on-click #(do (swap! *rp assoc :keeps #{}) (review-commit! ctx false))}
                       "Skip this file")
            (ui/button {:variant :ghost :size :sm :on-click #(reset! *rp {:skip 0 :phase :done})}
                       "Stop")]])]
       nil)]))

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
  (rum/local false ::paste?)
  (rum/local false ::review?)
  (rum/local nil ::rp)
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
        *paste?    (get state ::paste?)
        *review?   (get state ::review?)
        *rp        (get state ::rp)
        ctx        {:*preview *preview :*done *done :*remaining *remaining :*error *error :*busy? *busy?
                    :*progress *progress :*poll-id (get state ::poll-id)
                    :*metrics *metrics :*trace? *trace? :*classic? *classic? :*rp *rp}
        preview    @*preview
        done       @*done
        remaining  @*remaining
        rp         @*rp
        reviewing? (and rp (not= :done (:phase rp)))
        started?   (or (seq (:hatched done)) (seq (:failed done)) (some? remaining))
        pending-n  (if started? (or remaining 0) (count (:pending preview)))]
    (drop-source/drop-zone
     {:on-added (fn [_] (load-preview! *preview *error *busy?))}
     [:div.w-full.mx-auto {:class "md:max-w-[600px]"}
      [:h2#modal-headline.text-xl.mb-3 "Hatch sources"]
     [:p.text-sm.opacity-70.mb-3
      "Turns new or changed files in " (glossary/term "eggs/") ", " [:code "journals/"] " and "
      [:code "pages/"] " into nest pages — no per-file review. Runs in batches of "
      (str batch-size) "."]

     (when-not @*busy?
       [:div.mb-3
        (ui/button {:variant :outline :size :sm
                    :on-click #(pick-eggs! *preview *error *busy?)}
                   "Add source…")
        (ui/button {:variant :outline :size :sm :class "ml-2"
                    :on-click #(swap! *paste? not)}
                   "Paste text…")
        [:span.text-xs.opacity-50.ml-2 "or drop a file here"]])

     (when (and @*paste? (not @*busy?))
       (paste-source/paste-panel
        {:on-cancel #(reset! *paste? false)
         :on-saved  (fn [_name]
                      (reset! *paste? false)
                      (load-preview! *preview *error *busy?))}))

     (llm-banner/provider-banner)

     (when @*error
       [:div.my-2 (llm-banner/error-view @*error)])

     (cond
       reviewing?
       (review-panel rp ctx)

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
           (checkbox-row "Review each source's pages before writing" @*review? #(swap! *review? not))
           (checkbox-row "Record LLM activity (thinking + timings)" @*trace? #(swap! *trace? not))
           (checkbox-row "Classic mode — one LLM call per page (slower; for comparison)" @*classic? #(swap! *classic? not))
           (ui/button
            {:on-click #(if @*review? (review-start! ctx) (run-batch! ctx))
             :disabled @*busy?}
            (if @*review?
              (str "Review " (min batch-size pending-n) " file" (when (not= 1 (min batch-size pending-n)) "s") " →")
              (str (if started? "Hatch next " "Start — hatch ")
                   (min batch-size pending-n)
                   (when started? (str " (" pending-n " left)")))))])

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
