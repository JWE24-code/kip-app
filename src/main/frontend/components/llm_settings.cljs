(ns frontend.components.llm-settings
  "The LLM settings tab (see :llm in settings.cljs) — provider/model/API-key/
  base-URL form backed by scripts/lib/llm.js's loadLLMConfig/saveLLMConfig/
  testConnection, via the :getLlmConfig/:saveLlmConfig/:testLlmConnection IPC
  channels (electron.llm, in-process — llm.js has no native deps). The config
  lives at <graph>/.henhouse/llm.json, so every call passes the open graph's
  directory."
  (:require [cljs-bean.core :as bean]
            [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.handler.llm :as llm-handler]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [promesa.core :as p]
            [rum.core :as rum]))

(defn- vault-root []
  (config/get-repo-dir (state/get-current-repo)))

(def providers
  [{:value "anthropic" :label "Anthropic"}
   {:value "openai" :label "OpenAI"}
   {:value "deepseek" :label "DeepSeek"}
   {:value "local" :label "Local (Ollama)"}
   {:value "other" :label "Other (OpenAI-compatible)"}])

(def providers-with-base-url #{:local :other})

;; Placeholder text for the Model field, per provider.
(def model-hints
  {:openai "e.g. gpt-4o-mini"
   :local "e.g. llama3.1"})

(defn- empty-provider-fields []
  {:apiKey "" :model "" :baseUrl ""})

(defn- load-config!
  [*provider *fields *loaded?]
  (-> (ipc/ipc "getLlmConfig" (vault-root))
      (p/then (fn [result]
                (let [config (some-> result bean/->clj)
                      provider (keyword (or (:provider config) "anthropic"))
                      providers-map (or (:providers config) {})]
                  (reset! *provider provider)
                  (reset! *fields
                          (into {}
                                (map (fn [{:keys [value]}]
                                       [(keyword value)
                                        (merge (empty-provider-fields) (get providers-map (keyword value)))]))
                                providers))
                  (reset! *loaded? true))))))

(defn- save!
  [*provider *fields *saving? *save-message]
  (reset! *saving? true)
  (reset! *save-message nil)
  (let [config {:provider (name @*provider) :providers @*fields}]
    (-> (ipc/ipc "saveLlmConfig" (vault-root) config)
        (p/then (fn [_]
                  (reset! *save-message {:type :success :text "Saved."})
                  (llm-handler/refresh!)))
        (p/catch (fn [err] (reset! *save-message {:type :error :text (str err)})))
        (p/finally (fn [] (reset! *saving? false))))))

(defn- test-connection!
  [*provider *fields *testing? *test-result]
  (reset! *testing? true)
  (reset! *test-result nil)
  (let [provider @*provider
        fields (get @*fields provider (empty-provider-fields))
        candidate (assoc fields :provider (name provider))]
    (-> (ipc/ipc "testLlmConnection" candidate)
        (p/then (fn [result] (reset! *test-result (bean/->clj result))))
        (p/catch (fn [err] (reset! *test-result {:success false :error (str err)})))
        (p/finally (fn [] (reset! *testing? false))))))

(rum/defcs settings-content
  < rum/reactive
  (rum/local nil ::provider)
  (rum/local {} ::fields)
  (rum/local false ::loaded?)
  (rum/local false ::saving?)
  (rum/local nil ::save-message)
  (rum/local false ::testing?)
  (rum/local nil ::test-result)
  {:will-mount (fn [state]
                 (load-config! (get state ::provider) (get state ::fields) (get state ::loaded?))
                 state)}
  [state]
  (let [*provider (get state ::provider)
        *fields (get state ::fields)
        *loaded? (get state ::loaded?)
        *saving? (get state ::saving?)
        *save-message (get state ::save-message)
        *testing? (get state ::testing?)
        *test-result (get state ::test-result)
        provider (or @*provider :anthropic)
        fields (get @*fields provider (empty-provider-fields))
        show-base-url? (contains? providers-with-base-url provider)]
    [:div.panel-wrap
     (if-not @*loaded?
       [:div.text-sm.opacity-60 "Loading..."]
       [:<>
        [:div.text-sm.my-2
         [:label.block.text-sm.font-medium.mb-1 {:for "llm-provider"} "Provider"]
         [:select.form-select.is-small
          {:id "llm-provider"
           :value (name provider)
           :on-change (fn [e]
                        (reset! *provider (keyword (util/evalue e)))
                        (reset! *test-result nil))}
          (for [{:keys [value label]} providers]
            [:option {:key value :value value} label])]]

        [:div.text-sm.my-2
         [:label.block.text-sm.font-medium.mb-1 {:for "llm-api-key"} "API key"]
         [:input.form-input.is-small
          {:id "llm-api-key"
           :type "password"
           :autoComplete "off"
           :value (:apiKey fields)
           :placeholder (when (= provider :local) "not needed for local")
           :on-change (fn [e] (swap! *fields assoc-in [provider :apiKey] (util/evalue e)))}]]

        [:div.text-sm.my-2
         [:label.block.text-sm.font-medium.mb-1 {:for "llm-model"} "Model"]
         [:input.form-input.is-small
          {:id "llm-model"
           :type "text"
           :value (:model fields)
           :placeholder (get model-hints provider)
           :on-change (fn [e] (swap! *fields assoc-in [provider :model] (util/evalue e)))}]]

        (when show-base-url?
          [:div.text-sm.my-2
           [:label.block.text-sm.font-medium.mb-1 {:for "llm-base-url"} "Base URL"]
           [:input.form-input.is-small
            {:id "llm-base-url"
             :type "text"
             :value (:baseUrl fields)
             :placeholder "http://localhost:11434/v1"
             :on-change (fn [e] (swap! *fields assoc-in [provider :baseUrl] (util/evalue e)))}]])

        [:div.text-xs.opacity-50.my-3
         "Stored in plaintext at .henhouse/llm.json inside your graph folder "
         "— a local-machine-only secret, not encrypted at rest. Add "
         [:code ".henhouse/"] " to your graph's ignore list if it's under version control."]

        [:div.flex.gap-2.items-center.my-2
         (ui/button
          {:on-click #(save! *provider *fields *saving? *save-message)
           :disabled @*saving?}
          (if @*saving? "Saving..." "Save"))
         (ui/button
          {:on-click #(test-connection! *provider *fields *testing? *test-result)
           :disabled @*testing?
           :background "gray"}
          (if @*testing? "Testing..." "Test connection"))]

        (when-let [{:keys [type text]} @*save-message]
          [:div.text-sm.my-1 {:class (if (= type :success) "text-green-500" "text-red-500")} text])

        (when-let [{:keys [success error]} @*test-result]
          [:div.text-sm.my-1 {:class (if success "text-green-500" "text-red-500")}
           (if success "Connection OK." (str "Failed: " error))])])]))
