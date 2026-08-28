(ns frontend.components.first-run
  "The first-run checklist shown in Peck's empty state until Kip is ready to be
  useful: set an LLM provider, add a source, hatch it. Each step ticks off on
  its own; once all three are done (or the user has done them once — a flag is
  kept per graph) the empty state shows the normal example prompts instead."
  (:require [frontend.handler.coop :as coop]
            [frontend.handler.llm :as llm-handler]
            [frontend.state :as state]
            [frontend.storage :as storage]
            [rum.core :as rum]))

(defn- done-key []
  (str "kip-first-run-done-" (state/get-current-repo)))

(defn dismissed? []
  (boolean (storage/get (done-key))))

(defn mark-dismissed! []
  (storage/set (done-key) true))

(defn refresh! []
  (llm-handler/refresh!)
  (coop/refresh-counts!))

(defn steps
  "The three checklist steps, from the cached :kip/llm and :kip/coop-counts."
  [llm counts]
  [{:label "Set an LLM provider"
    :done? (boolean (:configured? llm))
    :on-click #(state/open-settings! :llm)}
   {:label "Add a source"
    :done? (pos? (:eggs counts 0))
    :note "drop a Markdown or text file anywhere on this panel"}
   {:label "Hatch it into The Nest"
    :done? (pos? (:nestPages counts 0))
    :on-click #(state/pub-event! [:modal/show-hatch])}])

(defn ready?
  "True once we've read both bits of state and every step is done — the point
  at which the checklist gives way to the example prompts."
  [llm counts steps]
  (and (:loaded? llm) (:loaded? counts) (every? :done? steps)))

(rum/defc checklist
  "The rendered checklist. `steps` from `steps`."
  [step-list]
  [:div.flex.flex-col.gap-2.items-start.text-left.mx-auto
   {:style {:max-width "20rem"}}
   [:div.text-xs.font-medium.opacity-60.mb-1 "To get started"]
   (for [{:keys [label done? note on-click]} step-list]
     [:div.flex.items-start.gap-2.text-sm
      {:key label :class (when done? "opacity-50")}
      [:span.mt-px {:style {:color (when done? "var(--ls-active-primary-color, #10b981)")}}
       (if done? "✓" "○")]
      [:div
       (if (and on-click (not done?))
         [:button.underline.hover:opacity-100.opacity-90 {:on-click on-click} label]
         [:span label])
       (when (and note (not done?))
         [:div.text-xs.opacity-60 note])]])])
