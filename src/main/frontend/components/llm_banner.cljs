(ns frontend.components.llm-banner
  "A small non-blocking banner shown at the top of the Peck and Hatch panels
  when no LLM provider is configured — Kip can't hatch or answer without one,
  and before this the first call just errored. See frontend.handler.llm."
  (:require [frontend.handler.llm :as llm-handler]
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
