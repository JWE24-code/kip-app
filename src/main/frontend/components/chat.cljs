(ns frontend.components.chat
  "The Peck panel — Kip's pecking-first main channel. Backed by
  scripts/lib/peck.js's peckTurn(), called via the :wikiChat IPC channel
  (electron.wiki shells out to scripts/chat.js — same one-code-path approach as
  the Coop status and Hatch panels).

  `peck-main` is the full-width centre-of-window view shown whenever
  :ui/peck-mode? is true (the default — see frontend.components.container);
  `chat-panel` is the same conversation rendered in the right sidebar (:chat).
  Both read one session atom (*peck-session), so the conversation survives a
  mod+shift+p toggle and is the same in either place.

  Each turn is auto-classified: a question is answered with [[wikilink]]
  citations; a statement is filed into the nest as a fact and the panel reports
  what it learned. Skills (<graph>/.henhouse/skills/) may run mid-answer — each
  shows as a ⚙ line above the answer, live from
  <coop>/.roost/peck-progress.json while it runs."
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            [electron.ipc :as ipc]
            [frontend.components.block :as block]
            [frontend.components.drop-source :as drop-source]
            [frontend.components.first-run :as first-run]
            [frontend.components.kip-brand :as brand]
            [frontend.components.llm-banner :as llm-banner]
            [frontend.components.telemetry :as telemetry]
            [frontend.config :as config]
            [frontend.handler.llm :as llm-handler]
            [frontend.handler.preference-signals :as pref-signals]
            [frontend.state :as state]
            [promesa.core :as p]
            [rum.core :as rum]
            [logseq.shui.ui :as ui]))

;; Polled while a turn runs: the ⚙ activity feed and the streaming answer
;; (`:partialAnswer` in peck-progress.json) both come from here. 200ms so the
;; streamed text reads as live — it's a small local-file read over IPC.
(def ^:private poll-ms 200)

;; Session-only conversation, lifted out of component-local state so a
;; mod+shift+p toggle (which unmounts peck-main) and the sidebar/main split
;; both keep one shared history. Not persisted across restarts.
(defonce *peck-session (atom {:messages [] :input "" :depth "full"}))
(def ^:private *messages (rum/cursor-in *peck-session [:messages]))
(def ^:private *input (rum/cursor-in *peck-session [:input]))
(def ^:private *depth (rum/cursor-in *peck-session [:depth]))

(defn prefill!
  "Drop `text` into the Peck input, ready for the user to send or edit. Used by
  the post-hatch 'Ask Kip about them' CTA (see :peck/prefill in events)."
  [text]
  (swap! *peck-session assoc :input text))

;; Generic on purpose — these render in the empty state, so they must not
;; reference anything from the user's own nest.
(def ^:private example-prompts
  ["What do I know about our onboarding process?"
   "Summarize this week's journals"
   "I have a meeting with Acme on Friday at 15:00"])

(defn- vault-root [] (config/get-repo-dir (state/get-current-repo)))

(defn- learned-message [{:keys [note pages]}]
  {:role :learned
   :text (if (string/blank? note) "Recorded." note)
   :pages pages})

(defn- turn->message [result]
  (let [{:keys [intent answer learned note pages steps callId arenaId webSource citedSlugs candidateSlugs deadCitations lintWarnings sources]} result]
    (cond
      (= intent "statement")
      (if learned
        (learned-message {:note note :pages pages})
        {:role :assistant :text (if (string/blank? note) "Nothing new to add there." note)})

      answer
      {:role :assistant :text answer :steps steps :call-id callId :arena-id arenaId
       :web-source webSource :answer? true
       ;; kept on the message so "file into the nest" has its inputs
       ;; (kip-app#112) — cited vs candidate is also the retrieval-breadth
       ;; signal the evidence view (#117) renders.
       :cited-slugs citedSlugs :candidate-slugs candidateSlugs
       ;; [[links]] in the answer that resolve to no nest page (kip-app#117)
       :dead-citations deadCitations
       ;; groom's findings for the pages this answer cited (kip-app#116)
       :lint-warnings lintWarnings
       ;; the pages the answer leaned on, listed under it (kip#49)
       :sources sources}

      (= intent "reminder")
      {:role :assistant :text "Reminder noted — check the Reminders panel." :steps steps}

      :else
      {:role :assistant :text "No matching pages found in the nest for this question." :empty? true})))

(defn- start-poll! [*progress *poll-id]
  (let [tick #(-> (ipc/ipc "wikiChatProgress" (vault-root))
                  (p/then (fn [r] (reset! *progress (bean/->clj r))))
                  (p/catch (fn [_] nil)))]
    (tick)
    (reset! *poll-id (js/setInterval tick poll-ms))))

(defn- stop-poll! [*progress *poll-id]
  (when-let [id @*poll-id] (js/clearInterval id))
  (reset! *poll-id nil)
  (reset! *progress nil))

;; The last few turns, clipped, sent with the next question so a follow-up
;; ("expand on that", "and their salary?") can resolve what it refers to
;; (kip-app#82). Session-only — *messages resets when the conversation clears.
(def ^:private history-turns 6)
(def ^:private history-clip 700)

(defn- recent-history [msgs]
  (->> msgs
       (filter #(and (#{:user :assistant} (:role %)) (not (string/blank? (:text %)))))
       (take-last history-turns)
       (mapv (fn [{:keys [role text]}]
               {:role (name role)
                :text (subs text 0 (min (count text) history-clip))}))))

(defn- ask!
  "Run one Peck turn for `question` and hand the turn->message map (plus the
  originating `:q` and `:history`) to `on-result`. Shared by a fresh send and
  a regenerate. `opts`: {:arena-compare-to <prior call id, kip-app#73>,
  :history [{:role :text} …] (kip-app#82), :depth \"quick\"|\"full\"
  (epic #38 track #36)}."
  [question on-result *loading? *progress *poll-id {:keys [arena-compare-to history depth]}]
  (when (and (not (string/blank? question)) (not @*loading?))
    (reset! *loading? true)
    (start-poll! *progress *poll-id)
    (-> (ipc/ipc "wikiChat" (vault-root) question false arena-compare-to (or history []) (or depth "full"))
        (p/then (fn [result]
                  (on-result (assoc (turn->message (bean/->clj result))
                                    :q question :history (vec history)))))
        (p/catch (fn [error]
                   (swap! *messages conj {:role :error :text (str error)})))
        (p/finally (fn []
                     (stop-poll! *progress *poll-id)
                     (reset! *loading? false))))))

(defn- send-message!
  [*loading? *progress *poll-id]
  (let [input (string/trim @*input)]
    (when (and (not (string/blank? input)) (not @*loading?))
      (let [history (recent-history @*messages)]
        (reset! *input "")
        (swap! *messages conj {:role :user :text input})
        (ask! input #(swap! *messages conj %) *loading? *progress *poll-id {:history history :depth @*depth})))))

(defn- regenerate!
  "Re-run the question that produced `msg`, appending a fresh answer below it.
  Fires the preference-signals `regenerated` behaviour signal against the old
  answer's call id. On the managed `kip` connector the re-run also goes
  through the arena as candidate B (the new answer carries an :arena-id and
  gets a 'was this better?' strip). A no-op of both on every other provider.
  Replays the same conversation history the original turn used."
  [{:keys [q call-id history]} *loading? *progress *poll-id]
  (when (and (not (string/blank? q)) (not @*loading?))
    (when call-id (pref-signals/behavior! call-id "regenerated"))
    (let [arena-compare-to (when (and call-id (pref-signals/enabled?)) call-id)]
      (ask! q #(swap! *messages conj (assoc % :regen? true))
            *loading? *progress *poll-id
            {:arena-compare-to arena-compare-to :history (vec history) :depth @*depth}))))

(defn- steps-line
  "A ⚙ line per skill the tool loop ran, above the answer."
  [steps]
  [:div.text-xs.font-mono.opacity-70.mb-1.space-y-0.5
   (for [[i {:keys [skill ok ms]}] (map-indexed vector steps)]
     [:div {:key i :class (when-not ok "text-red-500")}
      (str "⚙ " skill "  " (/ (js/Math.round (/ (or ms 0) 100)) 10) "s"
           (when-not ok "  — failed"))])])

;; Citations in an answer open the page as a right-sidebar peek instead of
;; switching the whole app to Documents — the conversation stays in view.
;; cmd/ctrl-click still opens it in Documents.
(def ^:private citation-config {:page-ref-as-sidebar? true})

;; --- preference signals (kip-app#73) — a 👍/👎 under a managed-backend answer.
;; Only shown when the active provider is the `kip` connector AND the turn
;; carried a call id. One rating per answer (the backend upserts, so a
;; change of mind just overwrites). Teenage-Engineering flavour: hairline
;; border, mono-caps micro-label, hot-orange when active, square corners.
(def ^:private te-orange "#ff5c00")

(rum/defcs rate-widget < (rum/local nil ::picked)
  [state call-id]
  (let [*picked (::picked state)
        btn (fn [score glyph]
              (let [active? (= @*picked score)
                    dimmed? (and (some? @*picked) (not active?))]
                [:button
                 {:key score
                  :title (if (= score 1) "Useful" "Not useful")
                  :on-click (fn [] (reset! *picked score) (pref-signals/rate! call-id score))
                  :style {:font-size "11px" :line-height "1" :padding "3px 7px"
                          :border (str "1px solid " (if active? te-orange "var(--ls-border-color)"))
                          :background (if active? te-orange "transparent")
                          :opacity (if dimmed? 0.4 1)
                          :cursor "pointer"}}
                 glyph]))]
    [:div {:style {:display "flex" :align-items "center" :gap "6px" :margin-top "6px"
                   :font-size "9px" :letter-spacing "0.1em" :text-transform "uppercase"
                   :font-family "ui-monospace, SFMono-Regular, Menlo, monospace"
                   :opacity 0.85}}
     [:span {:style {:opacity 0.5}} "Rate this answer"]
     (btn 1 "👍")
     (btn 0 "👎")
     (when (some? @*picked) [:span {:style {:opacity 0.45}} "logged"])]))

;; --- arena verdict (kip-app#73) — shown under a regenerated answer on the
;; managed backend. A = the previous answer, B = this one. The user can't
;; tell which model produced which; the backend just wants the winner.
(rum/defcs verdict-widget < (rum/local nil ::picked)
  [state arena-id]
  (let [*picked (::picked state)
        btn (fn [winner label]
              (let [active? (= @*picked winner)
                    dimmed? (and (some? @*picked) (not active?))]
                [:button
                 {:key winner
                  :on-click (fn [] (when (nil? @*picked)
                                     (reset! *picked winner)
                                     (pref-signals/verdict! arena-id winner)))
                  :disabled (some? @*picked)
                  :style {:font-size "10px" :text-transform "none"
                          :font-family "ui-monospace, SFMono-Regular, Menlo, monospace"
                          :line-height "1" :padding "3px 7px"
                          :border (str "1px solid " (if active? te-orange "var(--ls-border-color)"))
                          :background (if active? te-orange "transparent")
                          :opacity (if dimmed? 0.4 1)
                          :cursor (if (some? @*picked) "default" "pointer")}}
                 label]))]
    [:div {:style {:display "flex" :align-items "center" :gap "6px" :margin-top "6px"
                   :font-size "9px" :letter-spacing "0.1em" :text-transform "uppercase"
                   :font-family "ui-monospace, SFMono-Regular, Menlo, monospace" :opacity 0.85}}
     [:span {:style {:opacity 0.5}} "Better?"]
     (btn "a" "↑ that one")
     (btn "b" "this one")
     (btn "tie" "tie")
     (btn "skip" "skip")
     (when (some? @*picked) [:span {:style {:opacity 0.45}} "logged"])]))

;; --- web-search → source (kip-app#81) — when a turn ran web-search, offer to
;; keep its results as a pages/ source doc so they can be hatched into the nest.
(rum/defcs web-source-widget < (rum/local nil ::st)
  [state {:keys [filename content]}]
  (let [*st (::st state)
        save! (fn []
                (reset! *st :saving)
                (-> (ipc/ipc "wikiAddSource" (vault-root) filename content)
                    (p/then (fn [r] (reset! *st (bean/->clj r))))
                    (p/catch (fn [e] (reset! *st {:ok false :reason (str e)})))))
        s @*st]
    [:div {:style {:margin-top "6px" :font-size "11px" :opacity 0.8}}
     (cond
       (map? s)
       (if (:ok s)
         [:span {:style {:opacity 0.6}}
          (if (:duplicate s)
            (str "Already in your sources as " (:name s) ".")
            (str "Saved to pages/" (:name s) " — run Hatch to add it to your nest."))]
         [:span {:style {:color "#c0392b"}} (str "Couldn't save: " (or (:reason s) "unknown error"))])

       (= s :saving) [:span {:style {:opacity 0.5}} "Saving…"]

       :else
       [:button {:on-click save!
                 :style {:font-size "9px" :letter-spacing "0.1em" :text-transform "uppercase"
                         :font-family "ui-monospace, SFMono-Regular, Menlo, monospace"
                         :opacity 0.6 :cursor "pointer" :background "transparent"
                         :border "none" :padding "3px 0"}}
        "↓ Save these web results as a source"])]))

;; --- file the answer back into the nest (kip-app#112) — the vault-pattern
;; move: a synthesis worth keeping becomes a page. Offered on settled answers
;; that came from the nest (not web-backed, at least one candidate page).
;; Files via chat.js --file-answer, which logs nothing: the turn's `peck`
;; clucks row was written at ask time.
(rum/defcs file-answer-widget < (rum/local nil ::st)
  [state {:keys [q text candidate-slugs]}]
  (let [*st (::st state)
        file! (fn []
                (reset! *st :saving)
                (-> (ipc/ipc "wikiPeckFile" (vault-root) q text candidate-slugs)
                    (p/then (fn [r] (reset! *st (bean/->clj r))))
                    (p/catch (fn [e] (reset! *st {:error (str e)})))))
        s @*st]
    (cond
      (map? s)
      [:span {:style {:font-size "9px" :letter-spacing "0.1em"
                      :font-family "ui-monospace, SFMono-Regular, Menlo, monospace"
                      :opacity 0.6 :margin-top "6px"}}
       (if (:error s)
         [:span {:style {:color "#c0392b"}} (str "Couldn't file: " (:error s))]
         [:span
          (if (= (:action s) "update") "appended to " "filed as ")
          (block/inline-text citation-config :markdown (str "[[" (:slug s) "]]"))
          (when (:path s) [:span {:style {:opacity 0.6}} (str "  ·  " (:path s))])])]

      (= s :saving)
      [:span {:style {:font-size "9px" :opacity 0.5 :margin-top "6px"}} "filing…"]

      :else
      [:button {:on-click file!
                :title "Keep this answer as a nest page (concept, tagged from-peck)"
                :style {:font-size "9px" :letter-spacing "0.1em" :text-transform "uppercase"
                        :font-family "ui-monospace, SFMono-Regular, Menlo, monospace"
                        :opacity 0.6 :cursor "pointer" :background "transparent"
                        :border "none" :padding "3px 0" :margin-top "6px"}}
       "⬇ File into the nest"])))

(rum/defc lint-warnings-cp
  "groom's findings for the pages a Peck answer cited (kip-app#116) — shown
  under the answer so a claim drawn from a flagged page (orphaned, contradicted,
  a near-duplicate, …) carries that caveat."
  [warnings]
  [:div {:style {:font-size "11px" :opacity 0.75 :margin-top "6px"
                 :border-left "2px solid var(--ls-warning-color, #d97706)" :padding-left "8px"}}
   (for [{:keys [slug kind note]} warnings]
     [:div {:key (str slug "/" kind) :style {:margin "2px 0"}}
      "⚠ "
      (block/inline-text citation-config :markdown (str "[[" slug "]]"))
      " — " note])])

(rum/defc sources-cp
  "The pages a Peck answer leaned on, listed under the answer (kip#49)."
  [sources]
  [:div {:style {:font-size "11px" :opacity 0.75 :margin-top "6px"
                 :border-left "2px solid var(--ls-border-color, #e5e7eb)" :padding-left "8px"}}
   [:div {:style {:font-weight 500 :margin-bottom "2px"}} "Sources"]
   (for [{:keys [slug]} sources]
     [:div {:key slug :style {:margin "2px 0"}}
      (block/inline-text citation-config :markdown (str "[[" slug "]]"))])])

(rum/defc evidence-cp
  "Cited vs retrieved + the dead-citation flag (kip-app#117). Under a nest
  answer, surface (a) retrieved pages the answer did not cite inline, and (b)
  any [[link]] that resolves to no nest page at all — a citation the model
  made up. Complements sources-cp, which lists the pages it did cite."
  [candidate-slugs cited-slugs dead-citations]
  (let [cited (set cited-slugs)
        uncited (remove #(contains? cited %) (or candidate-slugs []))]
    (when (or (seq uncited) (seq dead-citations))
      [:div {:style {:font-size "11px" :opacity 0.75 :margin-top "6px"
                     :border-left "2px solid var(--ls-border-color, #e5e7eb)" :padding-left "8px"}}
       [:div {:style {:font-weight 500 :margin-bottom "2px"}} "Evidence"]
       (when (seq uncited)
         [:div {:style {:margin "2px 0"}}
          [:span {:style {:opacity 0.55}} "retrieved, not cited: "]
          (for [slug uncited]
            ^{:key slug} [:span {:style {:margin-right "6px"}}
                          (block/inline-text citation-config :markdown (str "[[" slug "]]"))])])
       (when (seq dead-citations)
         [:div {:style {:margin "2px 0" :color "var(--ls-warning-color, #d97706)"}}
          [:span {:style {:opacity 0.55}} "unresolved: "]
          (for [slug dead-citations]
            ^{:key slug} [:span {:style {:margin-right "6px"}}
                          (block/inline-text citation-config :markdown (str "[[" slug "]]"))])])])))

(rum/defc message-cp
  [{:keys [role text pages steps call-id arena-id web-source answer? regen? candidate-slugs cited-slugs dead-citations lint-warnings sources] :as msg}
   {:keys [on-regenerate busy?]}]
  [:div.py-2
   (case role
     :user
     [:div.text-right
      [:div.inline-block.bg-gray-04.rounded.px-3.py-2.text-sm text]]

     :error
     (llm-banner/error-view text)

     :learned
     [:div.text-sm.rounded.px-3.py-2 {:class "bg-gray-03 border-l-2 border-gray-11"}
      [:div.text-xs.font-medium.opacity-60.mb-1 "✓ Learned"]
      [:div.prose.prose-sm.max-w-none (block/inline-text citation-config :markdown text)]
      (when (seq pages)
        [:div.text-xs.opacity-70.mt-1
         (block/inline-text citation-config :markdown
                            (string/join "  ·  "
                                         (for [{:keys [action slug]} pages]
                                           (str (if (= action "create") "created" "updated") " [[" slug "]]"))))])]

     ;; :assistant — render [[slug]] citations as real clickable page links
     ;; via the app's own markdown renderer, given a plain unsaved string.
     [:div
      (when regen?
        [:div {:style {:font-size "9px" :letter-spacing "0.1em" :text-transform "uppercase"
                       :font-family "ui-monospace, SFMono-Regular, Menlo, monospace"
                       :opacity 0.4 :margin-bottom "4px"}}
         "↻ regenerated"])
      (when (seq steps) (steps-line steps))
      [:div.prose.prose-sm.max-w-none (block/inline-text citation-config :markdown text)]
      (when (seq sources) (sources-cp sources))
      (when (seq lint-warnings) (lint-warnings-cp lint-warnings))
      (evidence-cp candidate-slugs cited-slugs dead-citations)
      (when (and (:filename web-source) (:content web-source))
        (web-source-widget web-source))
      (when (and arena-id (pref-signals/enabled?))
        (verdict-widget arena-id))
      [:div {:style {:display "flex" :align-items "center" :gap "16px" :flex-wrap "wrap"}}
       ;; the A/B verdict is the richer signal — don't also ask for a 👍/👎 on
       ;; the same answer.
       (when (and call-id (not arena-id) (pref-signals/enabled?))
         (rate-widget call-id))
       ;; nest-sourced answers only: a web-backed answer's sources aren't nest
       ;; pages, and an answer with zero candidate pages has nothing to file.
       (when (and answer? (seq candidate-slugs) (nil? web-source))
         (file-answer-widget msg))
       (when (and answer? on-regenerate)
         [:button {:on-click #(when-not busy? (on-regenerate msg))
                   :disabled (boolean busy?)
                   :title "Ask again"
                   :style {:font-size "9px" :letter-spacing "0.1em" :text-transform "uppercase"
                           :font-family "ui-monospace, SFMono-Regular, Menlo, monospace"
                           :opacity (if busy? 0.3 0.6) :cursor (if busy? "default" "pointer")
                           :background "transparent" :border "none" :padding "3px 0" :margin-top "6px"}}
          "↻ Regenerate"])]])])

(rum/defc streaming-message
  "The answer as it streams in, from peck-progress.json's :partialAnswer. Same
  markdown renderer as a settled :assistant turn, with a trailing caret; it's
  swapped for the real message once the turn resolves."
  [text]
  [:div.prose.prose-sm.max-w-none
   (block/inline-text citation-config :markdown (str text " ▍"))])

;; --- autoscroll: keep the newest turn / streaming text in view, but let the
;; user scroll up to read back without being yanked to the bottom.
(def ^:private stick-threshold-px 48)

(defn- near-bottom? [^js el]
  (and el (< (- (.-scrollHeight el) (.-scrollTop el) (.-clientHeight el))
             stick-threshold-px)))

(defn- scroll-to-bottom! [^js el]
  (when el (set! (.-scrollTop el) (.-scrollHeight el))))

(defn- first-run-showing? [llm counts]
  (and (not (first-run/dismissed?))
       (not (first-run/ready? llm counts (first-run/steps llm counts)))))

(rum/defcs empty-state
  < rum/reactive
  (rum/local nil ::poll)
  {:did-mount    (fn [state]
                   (first-run/refresh!)
                   (reset! (::poll state) (js/setInterval first-run/refresh! 3000))
                   state)
   :did-update   (fn [state]
                   (let [llm (:kip/llm @state/state)
                         counts (:kip/coop-counts @state/state)]
                     (when (and (not (first-run/dismissed?))
                                (first-run/ready? llm counts (first-run/steps llm counts)))
                       (first-run/mark-dismissed!)))
                   state)
   :will-unmount (fn [state]
                   (when-let [id @(::poll state)] (js/clearInterval id))
                   state)}
  [_state]
  (let [llm (state/sub :kip/llm)
        counts (state/sub :kip/coop-counts)]
    [:div.flex.flex-col.items-center.text-center.opacity-80.py-8.select-none
     [:div.mb-3 (brand/egg-logo 44)]
     [:div.text-sm.font-medium "Kip"]
     [:div.text-xs.opacity-60.mt-1 {:style {:max-width "24rem"}} brand/slogan]
     (if (first-run-showing? llm counts)
       [:div.mt-3 (first-run/checklist (first-run/steps llm counts))]
       [:<>
        [:div.text-sm.opacity-70.mt-3.mb-4 {:style {:max-width "28rem"}}
         "Ask your nest a question, or tell it something to remember."]
        [:div.flex.flex-col.gap-1.5.items-center
         (for [p example-prompts]
           [:button.text-xs.px-3.py-1.rounded-full.transition-colors
            {:key p
             :class "bg-gray-03 hover:bg-gray-04 opacity-80 hover:opacity-100"
             :on-click #(reset! *input p)}
            p])]])]))

(rum/defc depth-toggle
  "Answer-depth control (epic #38 track #36): Quick = nest-only (fast/cheap),
  Full = the multi-source path (skills + web when warranted). Sticky per
  session via *depth. Cost is never surfaced — this is a depth trade-off only."
  < rum/static
  [depth loading?]
  [:div {:style {:display "inline-flex" :borderRadius "7px" :overflow "hidden"
                 :border "1px solid var(--ls-border-color)" :fontSize "0.75rem"}}
   (for [[v label] [["full" "Full"] ["quick" "Quick"]]]
     (let [on? (= v depth)]
       [:button
        {:key v
         :title (if (= v "quick")
                  "Quick — nest only: faster, cheaper, no external sources"
                  "Full — nest + skills + web search when the question needs it")
         :disabled loading?
         :on-click #(reset! *depth v)
         :style {:padding "0.25rem 0.6rem" :border "none" :cursor "pointer"
                 :fontWeight (if on? 600 400)
                 :color (if on? "var(--ls-active-primary-color)" "var(--ls-secondary-text-color)")
                 :background (if on? "var(--ls-tertiary-background-color)" "transparent")}}
        label]))])

(rum/defcs chat-panel
  < rum/reactive
  (rum/local false ::loading?)
  (rum/local nil ::progress)
  (rum/local nil ::poll-id)
  (rum/local nil ::scroll-el)
  (rum/local true ::stick?)
  {:will-mount (fn [state]
                 (llm-handler/refresh!)
                 state)
   :did-mount (fn [state]
                (scroll-to-bottom! @(::scroll-el state))
                state)
   ;; every append and every streaming tick re-renders this component; stay
   ;; pinned to the bottom unless the user has scrolled up to read back.
   :did-update (fn [state]
                 (when @(::stick? state)
                   (scroll-to-bottom! @(::scroll-el state)))
                 state)
   :will-unmount (fn [state]
                   (stop-poll! (get state ::progress) (get state ::poll-id))
                   state)}
  [state]
  (let [*loading? (get state ::loading?)
        *progress (get state ::progress)
        *poll-id (get state ::poll-id)
        *scroll-el (get state ::scroll-el)
        *stick? (get state ::stick?)
        _ (state/sub :kip/llm)  ; so the 👍/👎 widget appears/hides live on a provider switch
        messages (rum/react *messages)
        input (rum/react *input)
        depth (rum/react *depth)
        ;; a fresh send or a regenerate is the user acting — always ride it down
        submit! #(do (reset! *stick? true) (send-message! *loading? *progress *poll-id))
        regen! (fn [msg] (reset! *stick? true) (regenerate! msg *loading? *progress *poll-id))
        activity (get @*progress :activity)
        partial-answer (get @*progress :partialAnswer)]
    [:div.flex.flex-col {:style {:height "100%"}}
     [:div.flex-1.overflow-y-auto.px-2.pt-2
      {:ref #(when (and % (not @*scroll-el)) (reset! *scroll-el %))
       :on-scroll (fn [_]
                    (let [nb (boolean (near-bottom? @*scroll-el))]
                      (when (not= nb @*stick?) (reset! *stick? nb))))}
      (llm-banner/provider-banner)
      (if (empty? messages)
        (empty-state)
        (map-indexed (fn [idx msg]
                       (rum/with-key (message-cp msg {:on-regenerate regen! :busy? @*loading?}) idx))
                     messages))
      (when @*loading?
        [:div.py-2
         (if (string/blank? partial-answer)
           [:div.text-sm.opacity-60 "Thinking…"]
           (streaming-message partial-answer))
         (when (seq activity)
           (telemetry/activity-feed (reverse activity)))])]
      [:div.flex.gap-2.p-2.border-t.border-gray-06
       [:input.form-input.flex-1.text-sm
        {:type "text"
         :placeholder "Ask a question, or tell it something new…"
         :value input
         :disabled @*loading?
         :on-change #(reset! *input (.. % -target -value))
         :on-key-down (fn [e] (when (and (= "Enter" (.-key e)) (not (.-shiftKey e)))
                                 (.preventDefault e)
                                 (submit!)))}]
       (depth-toggle depth @*loading?)
       (ui/button {:on-click submit! :disabled (or @*loading? (string/blank? input))} "Peck")]]))

(rum/defc peck-main
  "The full-width, centre-of-window Peck view (see :ui/peck-mode? in
  frontend.components.container)."
  []
  [:div.kip-peck-main
   {:style {:display "flex" :flex-direction "column"
            :flex "1 1 auto" :min-height 0
            :width "100%" :max-width "46rem" :margin "0 auto" :padding "0 .5rem"}}
   (drop-source/drop-zone
    {:style {:display "flex" :flex-direction "column" :flex "1 1 auto" :min-height 0}}
    (chat-panel))])
