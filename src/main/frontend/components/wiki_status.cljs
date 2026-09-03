(ns frontend.components.wiki-status
  "The Coop status panel, top to bottom: the coop overview, the Groom
  workflow (a fast structural \"Run groom\" and a \"Deep groom (weekly)\"
  that does the full LLM-heavy review — per-page _Update_ reconciliation,
  summary drift, missing/broken links, merge candidates, deeper
  contradictions — and writes a <coop>/.roost/groom-report.md checklist),
  the weekly-deep-groom Schedule (groom-settings/schedule-row), and the
  last few clucks (recentClucks via scripts/lib/roost.js). Everything goes
  through electron.wiki, which shells out to the same CLI scripts used from
  the terminal — one code path."
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            [electron.ipc :as ipc]
            [frontend.components.coop-glossary :as glossary]
            [frontend.components.groom-settings :as groom-settings]
            [frontend.components.telemetry :as telemetry]
            [frontend.config :as config]
            [frontend.state :as state]
            [frontend.util :as util]
            [promesa.core :as p]
            [rum.core :as rum]
            [logseq.shui.ui :as ui]))

(defn- vault-root []
  (config/get-repo-dir (state/get-current-repo)))

(defn- fetch-recent-clucks! [*recent-clucks]
  (-> (ipc/ipc "wikiRecentLog" (vault-root))
      (p/then (fn [result] (reset! *recent-clucks (bean/->clj result))))
      (p/catch (fn [error] (reset! *recent-clucks {:error (str error)})))))

(defn- fetch-coop-summary! [*summary]
  (-> (ipc/ipc "wikiCoopSummary" (vault-root))
      (p/then (fn [r] (reset! *summary (bean/->clj r))))
      (p/catch (fn [_] (reset! *summary {})))))

(defn- fetch-pending! [*pending]
  (-> (ipc/ipc "wikiIngestPreview" (vault-root))
      (p/then (fn [r] (let [s (bean/->clj r)]
                        ;; total vs changed kept apart (kip-app#113): an
                        ;; "immutable" source edited since its hatch is a
                        ;; different signal from a fresh drop.
                        (reset! *pending {:total (count (:pending s))
                                          :changed (or (:changedCount s) 0)}))))
      (p/catch (fn [_] (reset! *pending nil)))))

(defn- fetch-deep-last! [*deep-last]
  (-> (ipc/ipc "wikiGroomMetrics" (vault-root))
      (p/then (fn [m] (reset! *deep-last (:at (bean/->clj m)))))
      (p/catch (fn [_] nil))))

(defn- run-groom! [*report *loading?]
  (reset! *loading? true)
  (-> (ipc/ipc "wikiLint" (vault-root))
      (p/then (fn [result] (reset! *report (bean/->clj result))))
      (p/catch (fn [error] (reset! *report {:error (str error)})))
      (p/finally (fn [] (reset! *loading? false)))))

(defn- run-deep-groom! [{:keys [*report *loading? *progress *poll-id *deep-last]}]
  (reset! *loading? true)
  (reset! *progress nil)
  (let [tick #(-> (ipc/ipc "wikiGroomProgress" (vault-root))
                  (p/then (fn [r] (reset! *progress (bean/->clj r))))
                  (p/catch (fn [_] nil)))]
    (tick)
    (reset! *poll-id (js/setInterval tick 2000)))
  (-> (ipc/ipc "wikiGroomDeep" (vault-root))
      (p/then (fn [result] (reset! *report (bean/->clj result))))
      (p/catch (fn [error] (reset! *report {:error (str error)})))
      (p/finally (fn []
                   (when-let [id @*poll-id] (js/clearInterval id))
                   (reset! *poll-id nil)
                   (reset! *progress nil)
                   (reset! *loading? false)
                   (fetch-deep-last! *deep-last)))))

(defn- open-report!
  "Opens the deep-groom checklist. `path` is the report's own :reportPath from
  the run result — an absolute, platform-correct path (scripts/groom.js builds
  it with path.join), so no separator assembly here."
  [path]
  (when (and path (exists? js/window.apis))
    (js/window.apis.openPath path)))

(rum/defc cluck-entry < rum/static
  [{:keys [kind title timestamp]}]
  [:div.py-1.border-b.border-gray-06.last:border-b-0
   [:div.flex.justify-between.items-baseline.gap-2
    [:span.font-medium.text-sm kind]
    [:span.text-xs.opacity-60 (some-> timestamp (string/replace "T" " ") (subs 0 16))]]
   [:div.text-sm.opacity-80 title]])

(rum/defc groom-category < rum/static
  [title items]
  [:div.mb-2
   [:div.font-medium.text-sm (str title " (" (count items) ")")]
   (if (empty? items)
     [:div.text-sm.opacity-60 "none"]
     [:ul.list-disc.pl-5
      (for [[idx item] (map-indexed vector items)]
        [:li.text-sm {:key idx} item])])])

(rum/defc groom-report < rum/static
  [report]
  (let [drift-items (concat
                      (map #(str "in meta.db, missing on disk: " (:slug %)) (get-in report [:drift :missingFiles]))
                      (map #(str "on disk, not in meta.db: " %) (get-in report [:drift :untrackedFiles])))
        duplicate-items (map #(str (first (:slugs %)) " ↔ " (second (:slugs %)) " (similarity " (:score %) ")")
                              (:nearDuplicates report))
        contradiction-items (map #(str (string/join ", " (:slugs %)) " — " (:description %))
                                  (:contradictions report))]
    [:div
     (when (:deep report)
       [:<>
        (groom-category "Page coherence"
                        (map #(str (:slug %) " — " (string/join " " (:issues %))
                                   (when (:consolidate %) "  (consider consolidating)"))
                             (:pageCoherence report)))
        (groom-category "Summary drift"
                        (map #(str (:slug %) " → \"" (:suggested %) "\"") (:summaryDrift report)))
        (groom-category "Merge candidates"
                        (map #(str (string/join " ↔ " (:slugs %)) " — " (:reason %)) (:mergeCandidates report)))
        (groom-category "Missing links"
                        (map #(str (:slug %) " — " (string/join ", " (:shouldLink %))) (:missingLinks report)))
        (groom-category "Broken links"
                        (map #(str (:slug %) " — " (string/join ", " (:badTargets %))) (:brokenLinks report)))
        (groom-category "Dead-end pages" (:deadEnds report))])
     (groom-category "Orphan pages" (:orphans report))
     (groom-category "Sources changed since hatch" (:changedSources report))
     (groom-category "Filesystem drift" drift-items)
     (groom-category "Near-duplicate slugs" duplicate-items)
     (groom-category "Possible contradictions" contradiction-items)
     (when (:reportPath report)
       [:div.mt-2
        (ui/button {:variant :outline :size :sm :on-click #(open-report! (:reportPath report))} "Open report file")])]))

;; The "Your coop" block at the top of Coop status — read-only counts + when
;; things last ran. `summary` from wikiCoopSummary; `pending` a
;; {:total n :changed n} map (or nil) — see fetch-pending!.
(rum/defc coop-overview < rum/static
  [summary pending]
  (let [{:keys [sourceFiles entities concepts sources lastHatchAt lastGroomAt]} summary
        nest-total (+ (or entities 0) (or concepts 0) (or sources 0))]
    [:div.mb-4
     [:h3.text-lg.font-medium.mb-1 "Your coop"]
     [:div.text-sm.opacity-80.space-y-0.5
      [:div (str (or sourceFiles 0) " source " (if (= 1 sourceFiles) "file" "files") " in ")
       (glossary/term "pages/")
       (when (and pending (pos? (:total pending 0)))
         [:span " · "
          [:a.underline {:on-click #(state/pub-event! [:modal/show-hatch])}
           (str (:total pending) " not yet hatched"
                (when (pos? (:changed pending 0))
                  (str " (" (:changed pending) " edited since hatch)"))
                " — hatch now")]])]
      [:div (str nest-total " ")
       (glossary/term "nest" "nest")
       (str " " (if (= 1 nest-total) "page" "pages"))
       (when (pos? nest-total)
         (str " (" entities " entity, " concepts " concept, " sources " source)"))]
      [:div.text-xs.opacity-60
       "Last hatch: " (if lastHatchAt (telemetry/ago lastHatchAt) "never")
       "  ·  Last groom: " (if lastGroomAt (telemetry/ago lastGroomAt) "never")]]
     [:div.mt-2 (glossary/legend)]]))

(rum/defcs coop-status-modal < rum/reactive
  (rum/local nil ::recent-clucks)
  (rum/local nil ::groom-report)
  (rum/local false ::groom-loading?)
  (rum/local false ::deep-loading?)
  (rum/local nil ::deep-progress)
  (rum/local nil ::deep-poll-id)
  (rum/local nil ::deep-last)
  (rum/local nil ::coop-summary)
  (rum/local nil ::pending)
  {:will-mount (fn [state]
                 (fetch-recent-clucks! (get state ::recent-clucks))
                 (fetch-deep-last! (get state ::deep-last))
                 (fetch-coop-summary! (get state ::coop-summary))
                 (fetch-pending! (get state ::pending))
                 state)
   :will-unmount (fn [state]
                   (when-let [id @(get state ::deep-poll-id)] (js/clearInterval id))
                   state)}
  [state _close-fn]
  (let [*recent-clucks (get state ::recent-clucks)
        *groom-report (get state ::groom-report)
        *groom-loading? (get state ::groom-loading?)
        *deep-loading? (get state ::deep-loading?)
        *deep-progress (get state ::deep-progress)
        busy? (or @*groom-loading? @*deep-loading?)
        deep-prog @*deep-progress]
    [:div.w-full.mx-auto {:class "md:max-w-[600px]"}
     [:h2#modal-headline.text-xl.mb-3 "Coop status"]

     (when-let [s @(get state ::coop-summary)]
       (coop-overview s @(get state ::pending)))

     [:div.mb-4
      [:div.flex.justify-between.items-center.mb-1
       [:h3.text-lg.font-medium "Groom"]
       [:div.flex.gap-2
        (ui/button
         {:variant :outline :size :sm
          :on-click (fn [] (run-groom! *groom-report *groom-loading?))
          :disabled busy?}
         (if @*groom-loading? "Running…" "Run groom"))
        (ui/button
         {:size :sm
          :on-click (fn [] (run-deep-groom! {:*report *groom-report :*loading? *deep-loading?
                                             :*progress *deep-progress :*poll-id (get state ::deep-poll-id)
                                             :*deep-last (get state ::deep-last)}))
          :disabled busy?}
         (if @*deep-loading? "Deep groom running…" "Deep groom (weekly)"))]]

      [:div.text-xs.opacity-50.mb-2
       (if-let [at @(get state ::deep-last)]
         (str "Last deep groom " (telemetry/ago at))
         "No deep groom recorded yet")]

      (cond
        @*deep-loading?
        [:div
         (if (:running deep-prog)
           [:div
            (telemetry/progress-bar deep-prog)
            (when (seq (:activity deep-prog))
              (telemetry/activity-feed (reverse (:activity deep-prog))))]
           [:div.text-sm.opacity-60.my-2 "Starting…"])]

        (:error @*groom-report)
        [:div.text-sm.text-red-500 (str "Error: " (:error @*groom-report))]

        (some? @*groom-report)
        (groom-report @*groom-report)

        :else nil)]

     (when (util/electron?)
       [:div.mb-4
        [:h3.text-lg.font-medium.mb-1 "Schedule"]
        (groom-settings/schedule-row)])

     [:div
      [:h3.text-lg.font-medium.mb-1 "Recent clucks"]
      (cond
        (nil? @*recent-clucks) [:div.text-sm.opacity-60 "Loading..."]
        (:error @*recent-clucks) [:div.text-sm.text-red-500 (str "Error: " (:error @*recent-clucks))]
        (empty? @*recent-clucks) [:div.text-sm.opacity-60 "No clucks yet."]
        :else [:div (map (fn [entry] (cluck-entry entry)) @*recent-clucks)])]]))

(defn show-coop-status-modal! [e]
  (state/set-modal! coop-status-modal)
  (when e (util/stop e)))
