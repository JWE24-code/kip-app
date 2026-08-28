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
            [frontend.components.llm-banner :as llm-banner]
            [frontend.components.telemetry :as telemetry]
            [frontend.config :as config]
            [frontend.handler.llm :as llm-handler]
            [frontend.state :as state]
            [promesa.core :as p]
            [rum.core :as rum]
            [logseq.shui.ui :as ui]))

(def ^:private poll-ms 1000)

;; Session-only conversation, lifted out of component-local state so a
;; mod+shift+p toggle (which unmounts peck-main) and the sidebar/main split
;; both keep one shared history. Not persisted across restarts.
(defonce *peck-session (atom {:messages [] :input ""}))
(def ^:private *messages (rum/cursor-in *peck-session [:messages]))
(def ^:private *input (rum/cursor-in *peck-session [:input]))

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
  (let [{:keys [intent answer learned note pages steps]} result]
    (cond
      (= intent "statement")
      (if learned
        (learned-message {:note note :pages pages})
        {:role :assistant :text (if (string/blank? note) "Nothing new to add there." note)})

      answer
      {:role :assistant :text answer :steps steps}

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

(defn- send-message!
  [*loading? *progress *poll-id]
  (let [input (string/trim @*input)]
    (when (and (not (string/blank? input)) (not @*loading?))
      (reset! *input "")
      (swap! *messages conj {:role :user :text input})
      (reset! *loading? true)
      (start-poll! *progress *poll-id)
      (-> (ipc/ipc "wikiChat" (vault-root) input)
          (p/then (fn [result]
                    (swap! *messages conj (turn->message (bean/->clj result)))))
          (p/catch (fn [error]
                     (swap! *messages conj {:role :error :text (str error)})))
          (p/finally (fn []
                       (stop-poll! *progress *poll-id)
                       (reset! *loading? false)))))))

(defn- steps-line
  "A ⚙ line per skill the tool loop ran, above the answer."
  [steps]
  [:div.text-xs.font-mono.opacity-70.mb-1.space-y-0.5
   (for [[i {:keys [skill ok ms]}] (map-indexed vector steps)]
     [:div {:key i :class (when-not ok "text-red-500")}
      (str "⚙ " skill "  " (/ (js/Math.round (/ (or ms 0) 100)) 10) "s"
           (when-not ok "  — failed"))])])

(rum/defc message-cp
  [{:keys [role text pages steps]}]
  [:div.py-2
   (case role
     :user
     [:div.text-right
      [:div.inline-block.bg-gray-04.rounded.px-3.py-2.text-sm text]]

     :error
     [:div.text-sm.text-red-500 (str "Error: " text)]

     :learned
     [:div.text-sm.rounded.px-3.py-2 {:class "bg-gray-03 border-l-2 border-gray-11"}
      [:div.text-xs.font-medium.opacity-60.mb-1 "✓ Learned"]
      [:div.prose.prose-sm.max-w-none (block/inline-text {} :markdown text)]
      (when (seq pages)
        [:div.text-xs.opacity-70.mt-1
         (block/inline-text {} :markdown
                            (string/join "  ·  "
                                         (for [{:keys [action slug]} pages]
                                           (str (if (= action "create") "created" "updated") " [[" slug "]]"))))])]

     ;; :assistant — render [[slug]] citations as real clickable page links
     ;; via the app's own markdown renderer, given a plain unsaved string.
     [:div
      (when (seq steps) (steps-line steps))
      [:div.prose.prose-sm.max-w-none (block/inline-text {} :markdown text)]])])

(rum/defc empty-state
  []
  [:div.flex.flex-col.items-center.text-center.opacity-80.py-8.select-none
   [:pre.font-mono.text-sm.mb-3
    {:aria-hidden "true"
     :style {:color "var(--ls-active-primary-color, #10b981)" :margin 0 :line-height 1.3}}
    "  \\\\\n  (o>\n\\_//)\n \\_/_)\n  _|_"]
   [:div.text-sm.font-medium "Kip"]
   [:div.text-sm.opacity-70.mt-1.mb-4 {:style {:max-width "28rem"}}
    "Ask your nest a question, or tell it something to remember."]
   [:div.flex.flex-col.gap-1.5.items-center
    (for [p example-prompts]
      [:button.text-xs.px-3.py-1.rounded-full.transition-colors
       {:key p
        :class "bg-gray-03 hover:bg-gray-04 opacity-80 hover:opacity-100"
        :on-click #(reset! *input p)}
       p])]])

(rum/defcs chat-panel
  < rum/reactive
  (rum/local false ::loading?)
  (rum/local nil ::progress)
  (rum/local nil ::poll-id)
  {:will-mount (fn [state]
                 (llm-handler/refresh!)
                 state)
   :will-unmount (fn [state]
                   (stop-poll! (get state ::progress) (get state ::poll-id))
                   state)}
  [state]
  (let [*loading? (get state ::loading?)
        *progress (get state ::progress)
        *poll-id (get state ::poll-id)
        messages (rum/react *messages)
        input (rum/react *input)
        submit! #(send-message! *loading? *progress *poll-id)
        activity (get @*progress :activity)]
    [:div.flex.flex-col {:style {:height "100%"}}
     [:div.flex-1.overflow-y-auto.px-2.pt-2
      (llm-banner/provider-banner)
      (if (empty? messages)
        (empty-state)
        (map-indexed (fn [idx msg] (rum/with-key (message-cp msg) idx)) messages))
      (when @*loading?
        [:div.py-2
         [:div.text-sm.opacity-60 "Thinking…"]
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
