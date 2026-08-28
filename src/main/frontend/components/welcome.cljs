(ns frontend.components.welcome
  "A one-time welcome card shown the first time a coop is opened. Three lines on
  the farm metaphor, the Peck/Documents toggle, and a button into Settings →
  LLM. Dismissal is permanent per coop (a localStorage flag), so this never
  interrupts twice."
  (:require [frontend.components.coop-glossary :as glossary]
            [frontend.components.kip-brand :as brand]
            [frontend.state :as state]
            [frontend.storage :as storage]
            [frontend.util :as util]
            [rum.core :as rum]
            [logseq.shui.ui :as ui]))

(defn- seen-key []
  (str "kip-welcome-seen-" (state/get-current-repo)))

(defn seen? []
  (boolean (storage/get (seen-key))))

(defn mark-seen! []
  (storage/set (seen-key) true))

(rum/defc welcome-card
  [close-fn]
  (let [done! (fn [] (mark-seen!) (close-fn))]
    [:div.w-full.mx-auto {:class "md:max-w-[440px]"}
     [:div.flex.flex-col.items-center.text-center
      [:pre.font-mono.text-sm.mb-3
       {:aria-hidden "true"
        :style {:color "var(--ls-active-primary-color, #10b981)" :margin 0 :line-height 1.3}}
       brand/egg-ascii]
      [:h2#modal-headline.text-xl.mb-1 "Welcome to Kip"]
      [:div.text-xs.opacity-60.mb-2 brand/slogan]]
     [:div.text-sm.opacity-80.space-y-2.my-3
      [:p "Drop documents into " (glossary/term "eggs/") " and Kip hatches them into "
       (glossary/term "nest/") " — a cross-linked wiki of entity, concept and source pages."]
      [:p "Then " [:b "peck"] " it: ask a question and get an answer that cites the "
       [:code "[[pages]]"] " it came from, or tell Kip a fact to file away."]
      [:p "You're in Peck now. Press " [:kbd.px-1.border.rounded "Ctrl/⌘ + 1"]
       " any time to switch to Documents and back."]]
     [:div.flex.gap-2.justify-end.mt-4
      (ui/button {:variant :ghost :size :sm :on-click done!} "Look around")
      (ui/button {:size :sm
                  :on-click (fn []
                              (done!)
                              (state/open-settings! :llm))}
                 "Get started — set up an LLM →")]]))

(defn maybe-show!
  "Show the welcome card once per coop. Marks it seen as soon as it opens so a
  reload mid-read doesn't bring it back."
  []
  (when (and (util/electron?) (state/get-current-repo) (not (seen?)))
    (mark-seen!)
    (state/set-modal! welcome-card {:center? true})))
