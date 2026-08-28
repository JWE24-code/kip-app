(ns frontend.components.onboarding
  (:require [rum.core :as rum]
            [frontend.state :as state]
            [frontend.components.onboarding.setups :as setups]))

(rum/defc intro
  [onboarding-and-home?]
  (setups/picker onboarding-and-home?))

(defn- link [label href]
  [:li [:a {:href href :target "_blank"} label]])

(defn help
  "Kip's help panel (the right-sidebar `?`). Kip-specific — nothing here points
  at Logseq docs."
  []
  [:div.help.cp__sidebar-help-docs
   [:p.mt-4.mb-1 [:b "Getting started"]]
   [:ul
    (link "kip website" "https://jwe24-code.github.io/kip-site/")
    (link "First-run walkthrough" "https://github.com/JWE24-code/kip/blob/main/docs/GETTING-STARTED.md")
    (link "Everything Kip adds on top of Logseq" "https://github.com/JWE24-code/kip/blob/main/docs/FEATURES.md")
    [:li [:a {:on-click (fn [] (state/sidebar-add-block! (state/get-current-repo)
                                                        "shortcut-settings" :shortcut-settings))}
          "Keyboard shortcuts"]]]

   [:p.mt-4.mb-1 [:b "How it fits together"]]
   [:ul.text-sm.opacity-80
    [:li [:b "Hatch"] " — a file you drop in " [:code "eggs/"] " becomes linked "
     [:code "entity"] " / " [:code "concept"] " / " [:code "source"] " pages under The Nest."]
    [:li [:b "Peck"] " — ask the nest a question (answers cite " [:code "[[pages]]"]
     "), or tell it a fact or an upcoming meeting."]
    [:li [:b "Groom"] " — read-only health checks over the nest."]]

   [:p.mt-4.mb-1 [:b "The coop"]]
   [:ul.text-sm.opacity-80
    [:li [:code "eggs/"] " — the source documents you add"]
    [:li [:code "nest/"] " — the LLM-maintained wiki"]
    [:li [:code "clucks/"] " — the activity log"]
    [:li [:code ".henhouse/"] " — LLM provider + skills config"]
    [:li [:code ".roost/"] " — the search index (disposable — rebuild any time)"]]

   [:p.mt-4.mb-1 [:b "Feedback"]]
   [:ul
    (link "Report a bug / request a feature" "https://github.com/JWE24-code/kip-app/issues")
    (link "Discussions" "https://github.com/JWE24-code/kip-app/discussions")
    (link "Changelog" "https://github.com/JWE24-code/kip-app/blob/version/file/CHANGELOG.md")]])
