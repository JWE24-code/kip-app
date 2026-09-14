(ns frontend.components.chat
  "The Peck panel — Kip's pecking-first main channel. Backed by the persistent
  Kip sidecar over its WebSocket (frontend.handler.sidecar): a turn streams as
  `turn.delta` events, skills report live `skill.progress`, and the user can
  cancel a turn or answer a mid-turn `ask_user` instead of waiting on one
  blocking spawn-per-action round trip (kip-app#147).

  `peck-main` is the full-width centre-of-window view shown whenever
  :ui/peck-mode? is true (the default — see frontend.components.container);
  `chat-panel` is the same conversation rendered in the right sidebar (:chat).
  Both read one session atom (*peck-session), so the conversation survives a
  mod+shift+p toggle and is the same in either place.

  A turn's live state — the streamed answer text, the tool/skill activity feed,
  the active turn id and any pending ask_user — lives in chat-panel locals and
  is folded in by handle-event! as sidecar events arrive."
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
            [frontend.handler.sidecar :as sidecar]
            [frontend.state :as state]
            [promesa.core :as p]
            [rum.core :as rum]
            [logseq.shui.ui :as ui]))

;; A turn's streamed text, tool/skill activity, active turn id and any pending
;; ask_user all live in chat-panel locals and are driven by sidecar events
;; (frontend.handler.sidecar) — no .roost/peck-progress.json polling.

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

(defn- event-key
  "A stable key for one settleable event, so two mounted panels agree on
  identity. Prefers the turn id, falling back to the error's request id then the
  frame id."
  [prefix {:keys [turnId id]} frame-id]
  (str prefix (or turnId id frame-id)))

(defn- handle-event!
  "Fold one sidecar event into the panel's locals. `state` is the chat-panel
  state (locals ::loading? ::stream ::activity ::steps ::turn-id ::ask
  ::tool-starts ::regen?). The shared conversation append is guarded by
  sidecar/settle-turn! so two mounted panels never record one turn twice; only
  the panel that actually ran the turn appends its streamed text."
  [state {:keys [type payload id] :as _env}]
  (let [*loading?    (get state ::loading?)
        *stream      (get state ::stream)
        *activity    (get state ::activity)
        *steps       (get state ::steps)
        *turn-id     (get state ::turn-id)
        *ask         (get state ::ask)
        *tool-starts (get state ::tool-starts)
        *regen?      (get state ::regen?)
        turn-id (:turnId payload)]
    (case type
      :turn.start
      (reset! *turn-id turn-id)

      :turn.delta
      (swap! *stream str (:text payload))

      :skill.progress
      (swap! *activity conj
             {:phase   (or (:phase payload) "skill")
              :label   (let [s (:skill payload)
                             p (:pct payload)]
                         (str s (when (number? p) (str " " (js/Math.round p) "%"))))
              :preview (:message payload)})

      :agent.tool.start
      (do
        (swap! *tool-starts assoc (:toolCallId payload) (js/Date.now))
        (swap! *activity conj {:phase "tool" :label (:name payload)}))

      :agent.tool.end
      (let [started (get @*tool-starts (:toolCallId payload))
            ms (when (number? started) (- (js/Date.now) started))]
        (swap! *tool-starts dissoc (:toolCallId payload))
        (swap! *activity conj {:phase "tool" :label (:name payload)
                               :ok (:ok payload) :preview (:result payload)})
        (swap! *steps conj (cond-> {:skill (:name payload) :ok (:ok payload)}
                             ms (assoc :ms ms))))

      :ask_user
      (reset! *ask {:call-id (:toolCallId payload)
                    :question (:question payload)
                    :options (:options payload)})

      :turn.end
      (let [was-loading? @*loading?
            streamed @*stream
            steps @*steps
            q (some->> @*messages (filter #(= :user (:role %))) last :text)
            msg (cond-> (assoc (sidecar/turn->message payload streamed steps) :q q)
                  @*regen? (assoc :regen? true))]
        (reset! *turn-id nil)
        (reset! *stream "")
        (reset! *ask nil)
        (reset! *loading? false)
        (reset! *regen? false)
        (reset! *activity [])
        (reset! *steps [])
        (reset! *tool-starts {})
        ;; Only the panel that ran the turn holds the streamed text; a
        ;; co-mounted panel skips (and must not consume the settle key).
        (when (and was-loading? (sidecar/settle-turn! (event-key "turn.end:" payload id)))
          (swap! *messages conj msg)))

      :turn.error
      (let [settled? (sidecar/settle-turn! (event-key "turn.error:" payload id))]
        (reset! *turn-id nil)
        (reset! *stream "")
        (reset! *ask nil)
        (reset! *loading? false)
        (reset! *regen? false)
        (reset! *activity [])
        (reset! *steps [])
        (reset! *tool-starts {})
        (when settled?
          (swap! *messages conj {:role :error
                                 :text (str (or (:message payload) "The turn failed.")
                                            (when-let [c (:code payload)] (str " (" c ")")))})))

      :error
      (when (sidecar/settle-turn! (event-key "error:" payload id))
        (reset! *loading? false)
        (swap! *messages conj {:role :error
                               :text (str (or (:message payload) "Sidecar error")
                                          (when-let [c (:code payload)] (str " (" c ")")))}))

      :sidecar/closed
      (when @*loading?
        (reset! *loading? false)
        (swap! *messages conj {:role :error :text "Lost the connection to Kip's sidecar — try again."}))

      nil)))

(rum/defcs ask-widget < (rum/local "" ::text)
  "Inline ask_user prompt: suggested options (if any) plus a free-text answer."
  [state ask answer!]
  (let [*text (::text state)
        submit! (fn []
                  (let [v (string/trim @*text)]
                    (when-not (string/blank? v)
                      (answer! v)
                      (reset! *text ""))))]
    [:div {:style {:margin-top "8px" :padding "8px 10px"
                   :border "1px solid var(--ls-border-color)" :border-radius "6px"}}
     [:div {:style {:font-size "12px" :margin-bottom "6px"}} (:question ask)]
     (when (seq (:options ask))
       [:div {:style {:display "flex" :gap "6px" :flex-wrap "wrap" :margin-bottom "6px"}}
        (for [opt (:options ask)]
          ^{:key opt}
          [:button {:on-click #(answer! opt)
                    :style {:font-size "11px" :padding "3px 8px" :cursor "pointer"
                            :border "1px solid var(--ls-border-color)" :border-radius "5px"
                            :background "transparent"}}
           opt])])
     [:div {:style {:display "flex" :gap "6px"}}
      [:input.form-input.flex-1.text-sm
       {:type "text"
        :placeholder "Your answer…"
        :value @*text
        :on-change #(reset! *text (.. % -target -value))
        :on-key-down (fn [e] (when (= "Enter" (.-key e))
                               (.preventDefault e)
                               (submit!)))}]
      [:button {:on-click submit!
                :style {:font-size "11px" :padding "3px 10px" :cursor "pointer"
                        :border "1px solid var(--ls-border-color)" :border-radius "5px"
                        :background "transparent"}}
       "Send"]]]))

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

(defn- legacy-send!
  "Fallback for a build without the bundled sidecar: the old spawn-per-action
  :wikiChat path, rendered as a single (non-streaming) assistant turn. Keeps
  Kip usable while the sidecar is only wired in dev. Uses the same turn->message
  mapping as the sidecar path so the answer's widgets don't regress."
  [question *loading? {:keys [history depth arena-compare-to]}]
  (-> (ipc/ipc "wikiChat" (vault-root) question false arena-compare-to
               (or history []) (or depth "full"))
      (p/then (fn [result]
                (swap! *messages conj
                       (assoc (sidecar/turn->message (bean/->clj result) nil nil)
                              :q question
                              :history (vec (or history []))))))
      (p/catch (fn [e]
                 (swap! *messages conj {:role :error :text (str e)})))
      (p/finally (fn [] (reset! *loading? false)))))

(defn- send-message!
  [*loading?]
  (let [input (string/trim @*input)
        history (recent-history @*messages)]
    (when (and (not (string/blank? input)) (not @*loading?))
      (reset! *input "")
      (reset! *loading? true)
      (swap! *messages conj {:role :user :text input :history (vec history)})
      (-> (sidecar/send-chat! input {:depth @*depth :history history})
          (p/catch (fn [e]
                     ;; Only a genuinely missing sidecar falls back to the old
                     ;; path; a real turn failure must not run the turn twice.
                     (if (sidecar/unavailable?)
                       (legacy-send! input *loading? {:history history :depth @*depth})
                       (do (reset! *loading? false)
                           (swap! *messages conj {:role :error :text (str e)})))))))))

(defn- regenerate!
  "Re-run the question that produced `msg`, appending a fresh answer below it.
  Fires the `regenerated` behaviour signal and, on the managed `kip` connector,
  the arena compare (candidate B) — same as the pre-sidecar path. Replays the
  conversation history the original turn used."
  [{:keys [q call-id history]} *loading?]
  (when (and (not (string/blank? q)) (not @*loading?))
    (when call-id (pref-signals/behavior! call-id "regenerated"))
    (reset! *loading? true)
    (let [arena-compare-to (when (and call-id (pref-signals/enabled?)) call-id)
          opts {:arena-compare-to arena-compare-to
                :history (vec (or history []))
                :depth @*depth}]
      (-> (sidecar/send-chat! q opts)
          (p/catch (fn [e]
                     (if (sidecar/unavailable?)
                       (legacy-send! q *loading? opts)
                       (do (reset! *loading? false)
                           (swap! *messages conj {:role :error :text (str e)})))))))))

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
  "The answer as it streams in, accumulated from turn.delta events. Same
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
  (rum/local "" ::stream)
  (rum/local [] ::activity)
  (rum/local [] ::steps)
  (rum/local {} ::tool-starts)
  (rum/local false ::regen?)
  (rum/local nil ::turn-id)
  (rum/local nil ::ask)
  (rum/local nil ::scroll-el)
  (rum/local true ::stick?)
  {:will-mount (fn [state]
                 (llm-handler/refresh!)
                 ;; Warm the renderer's own WS connection now, not on the first
                 ;; send-chat! — the main process prewarms the sidecar PROCESS on
                 ;; graph-open (electron.handler's :setCurrentGraph), but the
                 ;; renderer's own hello/ready handshake never starts until the
                 ;; first message is sent. That made the first message racy
                 ;; (kip-app#149): if the panel mounts and the user sends
                 ;; something quickly, the renderer's cold connect-repo! can lose
                 ;; a startup race and fall back to :wikiChat. Firing it here
                 ;; instead gives it the whole time the user spends reading/
                 ;; typing to settle, invisibly, before send-chat! ever needs it
                 ;; — a failure here is silent and harmless, since send-chat!
                 ;; still retries the connection itself.
                 (-> (sidecar/ensure-connected!) (p/catch (fn [_] nil)))
                 ;; sidecar events drive the live turn; unsubscribe on unmount
                 (assoc state ::unsub
                        (sidecar/add-listener! (fn [ev] (handle-event! state ev)))))
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
                   (when-let [unsub (::unsub state)] (unsub))
                   state)}
  [state]
  (let [*loading? (get state ::loading?)
        *stream (get state ::stream)
        *activity (get state ::activity)
        *ask (get state ::ask)
        *turn-id (get state ::turn-id)
        *regen? (get state ::regen?)
        *scroll-el (get state ::scroll-el)
        *stick? (get state ::stick?)
        _ (state/sub :kip/llm)  ; so the 👍/👎 widget appears/hides live on a provider switch
        messages (rum/react *messages)
        input (rum/react *input)
        depth (rum/react *depth)
        stream @*stream
        activity @*activity
        ask @*ask
        turn-id @*turn-id
        reset-live! #(do (reset! *stream "") (reset! *activity [])
                         (reset! *ask nil) (reset! *regen? false))
        ;; a fresh send or a regenerate is the user acting — always ride it down
        submit! #(do (reset! *stick? true) (reset-live!) (send-message! *loading?))
        regen! (fn [msg] (reset! *stick? true) (reset-live!)
                 (reset! *regen? true) (regenerate! msg *loading?))
        cancel! #(sidecar/cancel! turn-id)
        answer! (fn [answer]
                  (when-let [c (:call-id ask)]
                    (sidecar/respond! c answer)
                    (reset! *ask nil)))]
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
         (if (string/blank? stream)
           [:div.text-sm.opacity-60 "Thinking…"]
           (streaming-message stream))
         (when (seq activity)
           (telemetry/activity-feed (reverse activity)))
         (when ask
           (ask-widget ask answer!))
         ;; Stop is only offered when the sidecar advertises cancel support.
         ;; Kip's v1 protocol has no chat.cancel yet (kip#69), so showing it
         ;; would be a control that silently does nothing.
         (when (sidecar/cancel-supported?)
           [:div.mt-2
            [:button {:on-click cancel!
                      :title "Stop this turn"
                      :style {:font-size "9px" :letter-spacing "0.1em" :text-transform "uppercase"
                              :font-family "ui-monospace, SFMono-Regular, Menlo, monospace"
                              :opacity 0.5 :cursor "pointer" :background "transparent"
                              :border "none" :padding "3px 0"}}
             "■ Stop"]])])]
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
