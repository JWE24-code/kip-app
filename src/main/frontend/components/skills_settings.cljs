(ns frontend.components.skills-settings
  "The Skills settings tab (see :skills in settings.cljs) — an on/off list of
  every Peck skill plus the web-search backend picker, backed by
  scripts/lib/skills.js via the :skillsList / :setSkillEnabled / :getSearchConfig
  / :saveSearchConfig / :testSearch IPC channels (electron.skills, in-process —
  skills.js has no native deps). Everything lives in
  <graph>/.henhouse/skills.json, so every call passes the open graph's directory."
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            [electron.ipc :as ipc]
            [frontend.components.coop-glossary :as glossary]
            [frontend.config :as config]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [promesa.core :as p]
            [rum.core :as rum]))

(defn- vault-root []
  (config/get-repo-dir (state/get-current-repo)))

(def search-backends
  [{:value "duckduckgo" :label "DuckDuckGo (no key)"}
   {:value "brave" :label "Brave"}
   {:value "tavily" :label "Tavily"}])

(def key-backends #{"brave" "tavily"})

(defn- load-all!
  [*skills *search *loaded?]
  (-> (p/let [skills (ipc/ipc "skillsList" (vault-root))
              search (ipc/ipc "getSearchConfig" (vault-root))]
        (reset! *skills (vec (bean/->clj skills)))
        (reset! *search (or (bean/->clj search) {:backend "duckduckgo" :braveApiKey "" :tavilyApiKey ""}))
        (reset! *loaded? true))
      (p/catch (fn [_] (reset! *loaded? true)))))

(defn- toggle-skill!
  [*skills name enabled?]
  ;; optimistic — reflect the flip immediately, reconcile on the reply
  (swap! *skills (fn [ss] (mapv #(if (= (:name %) name) (assoc % :enabled enabled?) %) ss)))
  (-> (ipc/ipc "setSkillEnabled" (vault-root) name enabled?)
      (p/catch (fn [_]
                 (swap! *skills (fn [ss] (mapv #(if (= (:name %) name) (assoc % :enabled (not enabled?)) %) ss)))))))

(defn- set-approval!
  "decision: \"always\" | \"never\" | nil. Refreshes the whole list from the
  reply so an approval change also flips discoverability."
  [*skills name decision]
  (swap! *skills (fn [ss] (mapv #(if (= (:name %) name) (assoc % :approval (or decision "pending")) %) ss)))
  (-> (ipc/ipc "setSkillApproval" (vault-root) name decision)
      (p/then (fn [_]
                (-> (ipc/ipc "skillsList" (vault-root))
                    (p/then (fn [r] (reset! *skills (vec (bean/->clj r))))))))))

(defn- save-search!
  [*search *saving? *save-msg]
  (reset! *saving? true)
  (reset! *save-msg nil)
  (-> (ipc/ipc "saveSearchConfig" (vault-root) @*search)
      (p/then (fn [result]
                (reset! *search (bean/->clj result))
                (reset! *save-msg {:type :success :text "Saved."})))
      (p/catch (fn [err] (reset! *save-msg {:type :error :text (str err)})))
      (p/finally (fn [] (reset! *saving? false)))))

(defn- test-search!
  [*search *testing? *test-result]
  (reset! *testing? true)
  (reset! *test-result nil)
  (-> (ipc/ipc "testSearch" (vault-root) "logseq" @*search)
      (p/then (fn [result] (reset! *test-result (bean/->clj result))))
      (p/catch (fn [err] (reset! *test-result {:ok false :error (str err)})))
      (p/finally (fn [] (reset! *testing? false)))))

(rum/defcs settings-content
  < rum/reactive
  (rum/local [] ::skills)
  (rum/local {} ::search)
  (rum/local false ::loaded?)
  (rum/local false ::saving?)
  (rum/local nil ::save-msg)
  (rum/local false ::testing?)
  (rum/local nil ::test-result)
  {:will-mount (fn [state]
                 (load-all! (::skills state) (::search state) (::loaded? state))
                 state)}
  [state]
  (let [*skills (::skills state)
        *search (::search state)
        *loaded? (::loaded? state)
        *saving? (::saving? state)
        *save-msg (::save-msg state)
        *testing? (::testing? state)
        *test-result (::test-result state)
        search @*search
        backend (or (:backend search) "duckduckgo")
        show-key? (contains? key-backends backend)
        key-field (if (= backend "tavily") :tavilyApiKey :braveApiKey)]
    [:div.panel-wrap
     (if-not @*loaded?
       [:div.text-sm.opacity-60 "Loading..."]
       [:<>
        [:h2.text-lg.font-medium.mt-1.mb-2 "Skills"]
        [:div.text-xs.opacity-60.mb-3
         "Small programs Peck can run while answering a question. Toggle one off to "
         "stop offering it. Config lives in " (glossary/term "henhouse" ".henhouse/skills.json") ". "
         "A skill you add yourself under " (glossary/term "henhouse" ".henhouse/skills/")
         " runs with your privileges — approve it once before Peck can use it."]

        [:div.mb-6
         (for [{:keys [name description source enabled approval network permissions]} @*skills]
           (let [pending? (= approval "pending")
                 blocked? (= approval "never")]
             [:div.py-2.border-b.border-gray-100
              {:key name :style {:border-color "var(--ls-border-color)"}}
              [:div.flex.items-start.justify-between
               [:div.pr-3
                [:div.text-sm.font-medium name
                 [:span.text-xs.opacity-40.ml-2 source]
                 (when blocked? [:span.text-xs.text-red-500.ml-2 "blocked"])]
                [:div.text-xs.opacity-60 description]]
               (cond
                 pending?
                 [:div.flex.gap-2.flex-none
                  (ui/button {:variant :outline :size :sm :on-click #(set-approval! *skills name "always")} "Approve")
                  (ui/button {:variant :ghost :size :sm :on-click #(set-approval! *skills name "never")} "Block")]
                 blocked?
                 (ui/button {:variant :outline :size :sm :on-click #(set-approval! *skills name "always")} "Approve")
                 :else
                 (ui/toggle enabled #(toggle-skill! *skills name (not enabled)) true))]
              (when (and (= source "user") (or pending? (seq permissions) network))
                [:div.text-xs.opacity-60.mt-1.ml-0
                 (when pending? [:span.text-amber-600 "Custom skill — needs approval. "])
                 "Declares: "
                 (->> (concat (when network ["network access"]) permissions)
                      (map #(str %))
                      (string/join ", ")
                      (#(if (string/blank? %) "nothing (still full access — nothing is enforced)" %)))
                 (when (= approval "always")
                   [:a.underline.ml-2 {:on-click #(set-approval! *skills name nil)} "revoke"])])]))]

        [:h2.text-lg.font-medium.mt-4.mb-2 "Web search"]
        [:div.text-xs.opacity-60.mb-3
         "DuckDuckGo needs no key and is used by default. Peck searches on its own "
         "when a question needs facts the wiki doesn't have — turn off the "
         [:strong "web-search"] " skill above to stop that."]

        [:div.text-sm.my-2
         [:label.block.text-sm.font-medium.mb-1 {:for "search-backend"} "Backend"]
         [:select.form-select.is-small
          {:id "search-backend"
           :value backend
           :on-change (fn [e]
                        (swap! *search assoc :backend (util/evalue e))
                        (reset! *test-result nil))}
          (for [{:keys [value label]} search-backends]
            [:option {:key value :value value} label])]]

        (when show-key?
          [:div.text-sm.my-2
           [:label.block.text-sm.font-medium.mb-1 {:for "search-key"}
            (str (if (= backend "tavily") "Tavily" "Brave") " API key")]
           [:input.form-input.is-small
            {:id "search-key"
             :type "password"
             :autoComplete "off"
             :value (get search key-field "")
             :on-change (fn [e] (swap! *search assoc key-field (util/evalue e)))}]])

        [:div.flex.gap-2.items-center.my-3
         (ui/button {:on-click #(save-search! *search *saving? *save-msg)
                     :disabled @*saving?}
                    (if @*saving? "Saving..." "Save"))
         (ui/button {:on-click #(test-search! *search *testing? *test-result)
                     :disabled @*testing?
                     :background "gray"}
                    (if @*testing? "Searching..." "Test search"))]

        (when-let [{:keys [type text]} @*save-msg]
          [:div.text-sm.my-1 {:class (if (= type :success) "text-green-500" "text-red-500")} text])

        (when-let [{:keys [ok output error]} @*test-result]
          [:div.text-xs.my-2.p-2.rounded
           {:style {:background "var(--ls-secondary-background-color)"}
            :class (if ok "" "text-red-500")}
           [:pre.whitespace-pre-wrap.m-0 (if ok (or output "(no output)") (str "Failed: " error))]])])]))
