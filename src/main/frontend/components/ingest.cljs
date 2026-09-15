(ns frontend.components.ingest
  "The Hatch workflow's UI: turns new-or-changed files in pages/ and
  journals/ into nest pages, in batches, with no per-file review. Backed by
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

(defn- load-preview! [*preview *error *busy? *force?]
  (reset! *busy? true)
  (reset! *error nil)
  (-> (ipc/ipc "wikiIngestPreview" (vault-root) (boolean @*force?))
      (p/then (fn [r] (reset! *preview (bean/->clj r))))
      (p/catch (fn [e] (reset! *error (str e))))
      (p/finally (fn [] (reset! *busy? false)))))

(defn- pick-sources! [*preview *error *busy? *force?]
  (-> (ipc/ipc "wikiPickSources" (vault-root))
      (p/then (fn [r]
                (let [{:keys [canceled added duplicates rejected]} (bean/->clj r)]
                  (when-not canceled
                    (when (seq added)
                      (notification/show!
                       (str "Added " (string/join ", " added) " to pages/.") :success true))
                    (when (seq duplicates)
                      (notification/show!
                       (str (string/join ", " duplicates) " already in your coop.") :info true))
                    (when (seq rejected)
                      (notification/show!
                       (str "Skipped " (count rejected) " file(s) — Kip takes Markdown / text and Office / PDF (.docx, .xlsx, .pptx, .pdf).")
                       :warning true))
                    (when (or (seq added) (seq duplicates))
                      (coop/refresh-counts!)
                      (load-preview! *preview *error *busy? *force?))))))
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

(defn- check-recovery!
  "Read the last hatch-progress.json; if a run stopped without finishing (an
  error, or fewer done than total), stash {:done :total :error} in *recovery
  and put the error where the modal already shows it. What finished is on disk
  — a re-run skips it by content hash. `from-error` is the rejection string
  when this is called right after a failed batch."
  [*recovery *error from-error]
  (-> (ipc/ipc "wikiIngestProgress" (vault-root))
      (p/then (fn [r]
                (let [{:keys [done total running] :as p} (bean/->clj r)
                      err (or from-error (:error p))]
                  (when (and p (not running) (or err (and (number? total) (< (or done 0) total))))
                    (reset! *recovery {:done (or done 0) :total total})
                    (when (and err (nil? @*error)) (reset! *error err))))))
      (p/catch (fn [_] (when from-error (reset! *recovery {})))))) ;; no progress file — still flag it

(def ^:private no-graph-msg
  "Open a folder first (File → Open a folder) — Kip hatches the sources inside a graph's folder.")

(defn- run-batch! [{:keys [*preview *done *remaining *error *busy? *progress *poll-id *metrics *trace? *classic? *force? *recovery]}]
  (if (config/demo-graph?)
    (reset! *error no-graph-msg)
    (do
      (reset! *busy? true)
      (reset! *error nil)
      (reset! *recovery nil)
      (reset! *progress nil)
      (start-poll! *progress *poll-id)
      (-> (ipc/ipc "wikiIngestBatch" (vault-root) batch-size (boolean @*trace?) (boolean @*classic?) (boolean @*force?))
          (p/then (fn [r]
                    (let [{:keys [hatched failed remaining metrics]} (bean/->clj r)]
                      (swap! *done (fn [d] {:hatched (into (:hatched d) hatched)
                                            :failed  (into (:failed d) failed)}))
                      (reset! *remaining remaining)
                      (reset! *metrics metrics)
                      (coop/refresh-counts!))))
          (p/catch (fn [e]
                     (reset! *error (str e))
                     (check-recovery! *recovery *error (str e))
                     (load-preview! *preview *error *busy? *force?)))
          (p/finally (fn []
                       (stop-poll! *progress *poll-id)
                       (reset! *busy? false)))))))

;; --- "Review before writing" mode ----------------------------------------
;; A *group* at a time: propose the next `review-group-size` pending files'
;; pages in one combined LLM call (kip#112's proposeNextPendingGroup), let the
;; user keep/skip pages independently per file, then commit the whole group in
;; one pass. hatch-all.js --propose-next / --commit-next --group-size N; the
;; plans are stashed in .roost/hatch-plan.json between the two. `skip` steps
;; past files that failed to propose (a committed file just drops out of the
;; pending scan, so it usually stays 0).
;;
;; The IPC shapes are group-first but tolerate the older single-file shape (a
;; bundled hatch-all.js without kip#112): normalize-propose and commit-results
;; fold either into the same state, and review-commit! sends a flat keep vector
;; in that case — so an un-upgraded coop degrades to reviewing one file at a
;; time rather than breaking.

(def ^:private review-group-size 3)

(declare review-next!)

(defn- file-key
  "Stable per-file identity for a proposal (the keys of the :keeps map)."
  [{:keys [relPath source]}]
  (or relPath source))

(defn- proposal-keeps
  "The slugs the user kept for `proposal` (empty when skipped / none picked)."
  [{:keys [plan] :as proposal} keeps]
  (filterv (get keeps (file-key proposal)) (map :slug plan)))

(defn- initial-keeps
  "Start every file that proposed pages with all of its pages checked."
  [proposals]
  (into {}
        (for [p proposals
              :when (seq (:plan p))]
          [(file-key p) (set (map :slug (:plan p)))])))

(defn- normalize-propose
  "Fold either IPC propose shape into {:done? :grouped? :proposals :remaining
  :whiteboard?}. Grouped (kip#112): {:proposals [...]} / {:plans [...]} / a
  bare array. Singular (pre-kip#112): one {:source ... :plan [...]} map, or
  {:whiteboard true}."
  [res]
  (let [grouped (when-let [ps (or (:proposals res) (:plans res) (when (vector? res) res))]
                  (vec ps))]
    (cond
      (true? (:done res)) {:done? true}
      (seq grouped)       {:grouped? true :proposals grouped :remaining (:remaining res)}
      (and grouped (empty? grouped)) {:done? true}
      (or (:source res) (:whiteboard res))
      {:proposals [res] :remaining (:remaining res) :whiteboard? (boolean (:whiteboard res))}
      :else {:done? true})))

(defn- commit-results
  "A commit result is one map (single-file) or a vector of per-file maps (group)."
  [res]
  (cond
    (sequential? res) (vec res)
    (map? res)        [res]
    :else             []))

(defn- review-record! [*done proposals res]
  (let [by-source (into {} (map (fn [p] [(:source p) p]) proposals))]
    (doseq [{:keys [source results error keptNone skipped]} (commit-results res)]
      (let [proposal (get by-source source)]
        (swap! *done
               (fn [d]
                 (cond
                   error   (update d :failed conj {:source source :error error})
                   keptNone d
                   :else   (update d :hatched conj {:source source :kind (:kind proposal)
                                                    :results results :skipped (or skipped [])}))))))))

(defn- review-commit!
  "Commit the group's kept pages in one IPC call. `keep-all?` is the
  whiteboard / single-file path (nil keep map = keep every proposed page)."
  [{:keys [*rp *done] :as ctx} keep-all?]
  (let [{:keys [proposals keeps grouped? group-size]} @*rp
        keep-map (into {}
                       (for [p proposals
                             :when (seq (:plan p))]
                         [(file-key p) (proposal-keeps p keeps)]))
        flat-keeps (vec (mapcat val (sort-by key keep-map)))]
    (swap! *rp assoc :phase :committing)
    (-> (ipc/ipc "wikiIngestCommitNext" (vault-root)
                 (cond
                   keep-all? nil
                   grouped?  (clj->js keep-map)
                   :else     (clj->js flat-keeps))
                 (when (and grouped? (not keep-all?)) group-size))
        (p/then (fn [r]
                  (review-record! *done proposals (bean/->clj r))
                  (coop/refresh-counts!)))
        (p/catch (fn [e]
                   (swap! *done update :failed conj {:source (or (:source (first proposals)) "group")
                                                     :error (str e)})))
        (p/finally (fn [] (review-next! ctx))))))

(defn- review-next! [{:keys [*rp *classic? *force? *preview *error *busy?] :as ctx}]
  (swap! *rp assoc :phase :proposing :proposals nil :error nil)
  (-> (ipc/ipc "wikiIngestProposeNext" (vault-root) batch-size (get @*rp :skip 0)
               review-group-size (boolean @*classic?) (boolean @*force?))
      (p/then (fn [r]
                (let [{:keys [done? grouped? proposals remaining whiteboard?]}
                      (normalize-propose (bean/->clj r))]
                  (cond
                    done?       (do (swap! *rp assoc :phase :done)
                                    (load-preview! *preview *error *busy? *force?))
                    whiteboard? (do (swap! *rp assoc :proposals proposals :keeps {}
                                            :group-size review-group-size)
                                    (review-commit! ctx true))
                    :else       (swap! *rp assoc
                                       :phase :reviewing
                                       :proposals proposals
                                       :keeps (initial-keeps proposals)
                                       :grouped? grouped?
                                       :group-size review-group-size
                                       :remaining remaining)))))
      (p/catch (fn [e]
                 (swap! *rp assoc :phase :reviewing :proposals nil :error (str e))))))

(defn- review-start! [ctx]
  (if (config/demo-graph?)
    (reset! (:*error ctx) no-graph-msg)
    (do
      (reset! (:*done ctx) {:hatched [] :failed []})
      (reset! (:*rp ctx) {:skip 0})
      (review-next! ctx))))

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

(defn- toggle-keep! [*rp key slug]
  (swap! *rp update-in [:keeps key]
         (fn [ks]
           (let [ks (or ks #{})]
             ((if (contains? ks slug) disj conj) ks slug)))))

(defn- group-stats
  "Button counts: pages kept, files that will be written, and whiteboards."
  [proposals keeps]
  (let [page-counts (for [p proposals
                          :let [kept (proposal-keeps p keeps)]
                          :when (seq kept)]
                      (count kept))]
    {:pages  (reduce + 0 page-counts)
     :files  (count page-counts)
     :boards (count (filter :whiteboard proposals))}))

(defn- review-button-label [{:keys [pages files boards]}]
  (let [plural (fn [n w] (str n " " w (when (not= 1 n) "s")))]
    (cond
      (and (zero? pages) (zero? boards)) "Skip these files →"
      (zero? pages) (str "Convert " (plural boards "whiteboard") " →")
      :else (str "Write " (plural pages "page") " across " (plural files "file")
                 (when (pos? boards) (str " + " (plural boards "whiteboard"))) " →"))))

(rum/defc review-card
  < rum/static
  [{:keys [source relPath kind plan whiteboard] :as proposal} keeps *rp]
  (let [k (file-key proposal)]
    [:div.p-3.mb-2.rounded.border
     {:class "border-gray-200 dark:border-gray-700"}
     [:div.text-sm.mb-1
      [:span.font-medium source]
      (when relPath [:span.text-xs.opacity-50.ml-1 (str "(" relPath ")")])
      (when kind [:span.text-xs.opacity-50.ml-1 (str "[" kind "]")])]
     (cond
       whiteboard
       [:div.text-sm.opacity-70.my-1 "Whiteboard — converted as-is, nothing to pick."]

       (empty? plan)
       [:div.text-sm.opacity-70.my-1 "No pages proposed for this file."]

       :else
       [:ul.my-1
        (for [{:keys [slug title type action summary]} plan]
          [:li.py-1 {:key slug}
           [:label.flex.items-start.gap-2.cursor-pointer
            [:input.mt-1 {:type "checkbox"
                          :checked (boolean (contains? (get keeps k) slug))
                          :on-change #(toggle-keep! *rp k slug)}]
            [:span
             [:span.font-medium title]
             [:span.text-xs.opacity-50.ml-1 (str "[" type " · " action "]")]
             (when-not (string/blank? summary)
               [:div.text-xs.opacity-60 summary])]]])])
     [:div.mt-1
      (ui/button {:variant :outline :size :sm
                  :on-click #(swap! *rp update :keeps dissoc k)}
                 "Skip this file")]]))

(rum/defc review-panel
  "The group plan review shown while ::rp is active. `rp` is its state map."
  [rp {:keys [*rp] :as ctx}]
  (let [{:keys [phase proposals keeps group-size remaining error]} rp
        stats (group-stats proposals keeps)]
    [:div.my-2
     (case phase
       :proposing  [:div.text-sm.opacity-70 "Proposing pages for " (str (or group-size review-group-size)) " files…"]
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
           (when (and (number? remaining) (pos? remaining))
             [:div.text-xs.opacity-50.mb-1 (str remaining " more after this group")])
           [:div {:class "max-h-80 overflow-y-auto"}
            (for [[i p] (map-indexed vector proposals)]
              (rum/with-key (review-card p keeps *rp) i))]
           [:div.flex.gap-2.items-center.mt-2
            (ui/button {:size :sm :on-click #(review-commit! ctx false)}
                       (review-button-label stats))
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
  (rum/local false ::force?)
  (rum/local false ::paste?)
  (rum/local nil ::recovery)
  (rum/local false ::review?)
  (rum/local nil ::rp)
  {:will-mount   (fn [state]
                   (when-not (config/demo-graph?)
                     (load-preview! (get state ::preview) (get state ::error) (get state ::busy?) (get state ::force?))
                     (check-recovery! (get state ::recovery) (get state ::error) nil))
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
        *force?    (get state ::force?)
        *paste?    (get state ::paste?)
        *recovery  (get state ::recovery)
        *review?   (get state ::review?)
        *rp        (get state ::rp)
        ctx        {:*preview *preview :*done *done :*remaining *remaining :*error *error :*busy? *busy?
                    :*progress *progress :*poll-id (get state ::poll-id)
                    :*metrics *metrics :*trace? *trace? :*classic? *classic? :*force? *force?
                    :*recovery *recovery :*rp *rp}
        preview    @*preview
        recovery   @*recovery
        done       @*done
        remaining  @*remaining
        demo?      (config/demo-graph?)
        rp         @*rp
        reviewing? (and rp (not= :done (:phase rp)))
        started?   (or (seq (:hatched done)) (seq (:failed done)) (some? remaining))
        pending-n  (if started? (or remaining 0) (count (:pending preview)))]
    (drop-source/drop-zone
     {:on-added (fn [_] (when-not demo? (load-preview! *preview *error *busy? *force?)))}
     [:div.w-full.mx-auto {:class "md:max-w-[600px]"}
      [:h2#modal-headline.text-xl.mb-3 "Hatch sources"]
      [:p.text-sm.opacity-70.mb-3
       "Turns new or changed files in " (glossary/term "pages/") " and "
       [:code "journals/"] " into nest pages — no per-file review. Runs in batches of "
       (str batch-size) "."]

     (if demo?
       [:div.text-sm.opacity-70.my-2
        "Open a folder as your graph first — " (glossary/term "pages/")
        " and the rest of the coop live inside it. Use the graph menu at the top left."]
       [:<>

     (when-not @*busy?
       [:div.mb-3
         (ui/button {:variant :outline :size :sm
                     :on-click #(pick-sources! *preview *error *busy? *force?)}
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
                       (load-preview! *preview *error *busy? *force?))}))

     (llm-banner/provider-banner)

     (when @*error
       [:div.my-2 (llm-banner/error-view @*error)])

     (when (and recovery (not @*busy?))
       [:div.my-2.p-3.rounded.border.border-amber-500.text-sm
        {:class "bg-amber-500/10"}
        [:div.font-medium
         (if-let [{:keys [done total]} (when (:total recovery) recovery)]
           (str "The last run stopped after " done " of " total " file"
                (when (not= 1 total) "s") ".")
           "The last run didn't finish.")]
        [:div.text-xs.opacity-70.mt-1
         "What finished is saved — hatching again skips it and picks up the rest."]])

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
          (ui/button {:on-click #(load-preview! *preview *error *busy? *force?)} "Retry"))

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
              (str (count (:pending preview)) " file(s) pending"
                   (when-let [changed (:changedCount preview)]
                     (when (pos? changed) (str ", " changed " edited since hatch")))
                   ", ~" (:totalKb preview) " KB total.")
              [:ul.list-disc.pl-5.mt-1 {:class "max-h-40 overflow-y-auto"}
               (for [[i {:keys [source kind kb status]}] (map-indexed vector (:pending preview))]
                 [:li.text-xs.opacity-70 {:key i}
                  (str "[" kind (when (= status "changed") " · edited since hatch") "] " source " (" kb " KB)")])]])
            (checkbox-row "Re-hatch already-hatched sources" @*force?
                          #(do (swap! *force? not)
                               (load-preview! *preview *error *busy? *force?)))
            (checkbox-row "Review each source's pages before writing" @*review? #(swap! *review? not))
           (checkbox-row "Record LLM activity (thinking + timings)" @*trace? #(swap! *trace? not))
           (checkbox-row "Classic mode — one LLM call per page (slower; for comparison)" @*classic? #(swap! *classic? not))
           (ui/button
            {:on-click #(if @*review? (review-start! ctx) (run-batch! ctx))
             :disabled @*busy?}
            (if @*review?
              (str "Review " (min review-group-size pending-n) " file" (when (not= 1 (min review-group-size pending-n)) "s") " →")
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
           (str (count (:empty preview)) " near-empty file(s) skipped.")])])])])))

(defn show-hatch-modal! [e]
  (state/set-modal! hatch-modal)
  (when e (util/stop e)))
