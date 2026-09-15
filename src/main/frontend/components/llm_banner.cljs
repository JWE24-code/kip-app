(ns frontend.components.llm-banner
  "Shared LLM-related UI bits: the 'no provider set' banner at the top of Peck
  and Hatch, and a humanised error view (title + hint + collapsible raw + a
  'Report this bug' action). Both lean on frontend.handler.llm."
  (:require [frontend.components.bug-report-modal :as bug-report-modal]
            [frontend.handler.llm :as llm-handler]
            [frontend.state :as state]
            [logseq.shui.ui :as ui]
            [rum.core :as rum]))

(rum/defc provider-banner
  "Renders nothing until the llm.json read resolves, then nothing if a provider
  is configured, then the banner. Mount a `(:will-mount llm-handler/refresh!)`
  on the host component so this reflects the current config."
  < rum/reactive
  []
  (let [_ (state/sub :kip/llm)]
    (when (llm-handler/needs-setup?)
      [:div.flex.items-center.gap-3.text-sm.rounded.px-3.py-2.mb-3
       {:class "bg-gray-03 border-l-2 border-orange-500"}
       [:div.flex-1
        [:div.font-medium "No LLM provider set"]
        [:div.text-xs.opacity-70 "Kip can't hatch sources or answer questions yet."]]
       (ui/button {:size :sm :variant :outline
                   :on-click #(state/open-settings! :llm)}
                  "Set it up")])))

;; Render an LLM failure humanely — a short title + a hint + a collapsible
;; "Show details" with the raw error, plus a "Report this bug" action. `raw`
;; is the original error string (a rejected IPC message, a `failed` entry's
;; :error, stderr). `context` is a short feature tag (e.g. "peck/chat",
;; "hatch/modal") identifying which part of the app this error came from —
;; every call site names its own, so one component here covers all of them.
(rum/defcs error-view
  < (rum/local false ::open?)
  [state raw context]
  (let [*open? (::open? state)
        {:keys [title hint] :as parsed} (llm-handler/humanize-error raw)
        raw-str (:raw parsed)]
    [:div.text-sm.text-red-500
     (if title
       [:<>
        [:div.font-medium title]
        (when hint [:div.text-xs.opacity-80 hint])
        [:button.text-xs.underline.opacity-60.mt-1
         {:on-click #(swap! *open? not)}
         (if @*open? "Hide details" "Show details")]
        (when @*open?
          [:pre.text-xs.opacity-70.mt-1.whitespace-pre-wrap
           {:style {:max-height "8rem" :overflow-y "auto"}}
           raw-str])]
       raw-str)
     [:div.mt-1
      (ui/button {:variant :ghost :size :sm
                  :on-click #(bug-report-modal/open! {:context context
                                                 :error {:title title :hint hint :raw raw-str}})}
                 "Report this bug")]]))
