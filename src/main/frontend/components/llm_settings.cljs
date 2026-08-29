(ns frontend.components.llm-settings
  "The LLM settings tab (see :llm in settings.cljs). The provider list and
  each provider's form fields are data-driven: they come from
  scripts/lib/connectors.js via the :listLlmProviders IPC channel — the
  built-in providers plus any bundled or graph-local connector. The config
  lives at <graph>/.henhouse/llm.json, so every call passes the open graph's
  directory.

  A graph-local connector is installed from an npm .tgz (:pickConnectorTarball
  + :installConnector, @kip-ai/* only) and can be removed again
  (:removeConnector). The managed Kip backend connector stays out of the
  dropdown until the user opts in or is already using it."
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            [electron.ipc :as ipc]
            [frontend.components.coop-glossary :as glossary]
            [frontend.components.llm-banner :as llm-banner]
            [frontend.config :as config]
            [frontend.handler.llm :as llm-handler]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [promesa.core :as p]
            [rum.core :as rum]))

(defn- vault-root []
  (config/get-repo-dir (state/get-current-repo)))

(defn- present? [s]
  (and (string? s) (not (string/blank? s))))

;; The managed Kip backend connector is invite-only for now: keep it out of
;; the dropdown unless the user opts in, is already using it, or installed it
;; into this graph by hand.
(def ^:private managed-id "kip")

(defn- provider-visible?
  [{:keys [id source]} {:keys [opted-in? selected saved-apikey]}]
  (or (not= id managed-id)
      opted-in?
      (= source "graph-local")
      (= selected id)
      (present? saved-apikey)))

(defn- field-value
  "What to show in a field's input: the user's edit, else the saved value, else blank."
  [values pid fkey]
  (or (get-in values [pid fkey]) ""))

(defn- missing-required
  "Labels of the selected provider's required fields that are still blank."
  [fields values pid]
  (->> fields
       (filter :required)
       (remove #(present? (field-value values pid (:key %))))
       (mapv #(or (:label %) (:key %)))))

;; ---------------------------------------------------------------------------
;; loading
;; ---------------------------------------------------------------------------

(defn- load-providers!
  [*providers]
  (-> (ipc/ipc "listLlmProviders" (vault-root))
      (p/then (fn [result]
                (reset! *providers (vec (js->clj result :keywordize-keys true)))))
      (p/catch (fn [_] (reset! *providers [])))))

(defn- load-config!
  [*selected *values *loaded?]
  (-> (ipc/ipc "getLlmConfig" (vault-root))
      (p/then (fn [result]
                (let [config (some-> result bean/->clj)]
                  (reset! *selected (or (:provider config) "anthropic"))
                  (reset! *values
                          (into {}
                                (map (fn [[k v]]
                                       [(name k) (into {} (map (fn [[fk fv]] [(name fk) fv])) v)]))
                                (or (:providers config) {})))
                  (reset! *loaded? true))))
      (p/catch (fn [_] (reset! *loaded? true)))))

(defn- refresh-and-select!
  "Re-read the provider list, then select `id`."
  [*providers *selected id]
  (-> (load-providers! *providers)
      (p/then (fn [_] (when id (reset! *selected id))))))

;; ---------------------------------------------------------------------------
;; actions
;; ---------------------------------------------------------------------------

(defn- save!
  [*selected *values *saving? *save-message]
  (reset! *saving? true)
  (reset! *save-message nil)
  (let [config {:provider @*selected :providers @*values}]
    (-> (ipc/ipc "saveLlmConfig" (vault-root) config)
        (p/then (fn [_]
                  (reset! *save-message {:type :success :text "Saved."})
                  (llm-handler/refresh!)))
        (p/catch (fn [err] (reset! *save-message {:type :error :text (str err)})))
        (p/finally (fn [] (reset! *saving? false))))))

(defn- test-connection!
  [*selected *values *testing? *test-result]
  (reset! *testing? true)
  (reset! *test-result nil)
  (let [pid @*selected
        candidate (assoc (get @*values pid {}) "provider" pid)]
    (-> (ipc/ipc "testLlmConnection" (vault-root) candidate)
        (p/then (fn [result] (reset! *test-result (bean/->clj result))))
        (p/catch (fn [err] (reset! *test-result {:success false :error (str err)})))
        (p/finally (fn [] (reset! *testing? false))))))

(defn- probe-local!
  "Ping the local endpoint's /models and stash {:status … :models … :error …}
  in *probe. No-op unless the selected provider is `local`."
  [*selected *values *probe]
  (when (= "local" @*selected)
    (let [base-url (field-value @*values "local" "baseUrl")]
      (reset! *probe {:status :checking})
      (-> (ipc/ipc "probeLocalLlm" base-url)
          (p/then (fn [r]
                    (let [{:keys [ok models error]} (bean/->clj r)]
                      (reset! *probe (if ok
                                       {:status :ok :models (vec models)}
                                       {:status :down :error error})))))
          (p/catch (fn [err] (reset! *probe {:status :down :error (str err)})))))))

(defn- install!
  [src *providers *selected *installing? *install-message]
  (when (present? src)
    (reset! *installing? true)
    (reset! *install-message nil)
    (-> (ipc/ipc "installConnector" (vault-root) src)
        (p/then (fn [result]
                  (let [{:keys [ok id name error]} (bean/->clj result)]
                    (if ok
                      (do (reset! *install-message {:type :success :text (str "Installed " (or name id) ".")})
                          (refresh-and-select! *providers *selected id))
                      (reset! *install-message {:type :error :text error})))))
        (p/catch (fn [err] (reset! *install-message {:type :error :text (str err)})))
        (p/finally (fn [] (reset! *installing? false))))))

(defn- pick-and-install!
  [*providers *selected *installing? *install-message]
  (-> (ipc/ipc "pickConnectorTarball")
      (p/then (fn [path]
                (when (present? path)
                  (install! path *providers *selected *installing? *install-message))))))

(defn- remove!
  [id *providers *selected *install-message]
  (-> (ipc/ipc "removeConnector" (vault-root) id)
      (p/then (fn [result]
                (let [{:keys [ok error]} (bean/->clj result)]
                  (if ok
                    (do (reset! *install-message {:type :success :text (str "Removed " id ".")})
                        (refresh-and-select! *providers *selected "anthropic"))
                    (reset! *install-message {:type :error :text error})))))
      (p/catch (fn [err] (reset! *install-message {:type :error :text (str err)})))))

;; ---------------------------------------------------------------------------
;; view
;; ---------------------------------------------------------------------------

(defn- field-input
  [{:keys [key label type required placeholder default help]} pid *values *probe *selected]
  [:div.text-sm.my-2 {:key key}
   [:label.block.text-sm.font-medium.mb-1 {:for (str "llm-" key)}
    label (when required [:span.text-red-500 " *"])]
   [:input.form-input.is-small
    {:id (str "llm-" key)
     :type (if (= type "password") "password" "text")
     :autoComplete "off"
     :value (field-value @*values pid key)
     :placeholder (or placeholder default)
     :on-change (fn [e] (swap! *values assoc-in [pid key] (util/evalue e)))
     :on-blur (fn [_] (when (and (= pid "local") (= key "baseUrl"))
                        (probe-local! *selected *values *probe)))}]
   (when (present? help)
     [:div.text-xs.opacity-50.mt-1 help])])

(defn- local-probe-view
  [*values *probe]
  (let [{:keys [status models error]} @*probe]
    [:div.text-xs.mt-1
     (case status
       :checking [:span.opacity-60 "Checking…"]
       :ok       [:span
                  [:span.text-green-500 "● Reachable"]
                  (when (seq models)
                    [:span.opacity-70
                     (str " · " (count models) (if (= 1 (count models)) " model: " " models: "))
                     (interpose ", "
                                (for [m models]
                                  [:a.underline {:key m
                                                 :on-click #(swap! *values assoc-in ["local" "model"] m)}
                                   m]))])]
       :down     [:span.text-red-500 (str "● " error)]
       nil)]))

(defn- add-connector-view
  [*providers *selected *installing? *install-message *url]
  [:div.mt-4.pt-3.border-t.border-gray-200.dark:border-gray-700
   [:div.text-sm.font-medium.mb-1 "Add a connector"]
   [:div.text-xs.opacity-60.mb-2
    "Install an " [:code "@kip-ai/*"] " connector package from a " [:code ".tgz"]
    " file or a URL. Connector code runs with Kip's privileges — only install one you trust."]
   [:div.flex.gap-2.items-center.flex-wrap
    (ui/button {:on-click #(pick-and-install! *providers *selected *installing? *install-message)
                :disabled @*installing?
                :background "gray"}
               (if @*installing? "Installing…" "Choose .tgz…"))
    [:input.form-input.is-small.flex-1
     {:type "text" :placeholder "…or paste an https URL"
      :style {:min-width "180px"}
      :value @*url
      :on-change (fn [e] (reset! *url (util/evalue e)))}]
    (ui/button {:on-click #(install! @*url *providers *selected *installing? *install-message)
                :disabled (or @*installing? (not (present? @*url)))
                :background "gray"}
               "Install")]
   (when-let [{:keys [type text]} @*install-message]
     [:div.text-sm.my-1 {:class (if (= type :success) "text-green-500" "text-red-500")} text])])

(rum/defcs settings-content
  < rum/reactive
  (rum/local nil ::providers)
  (rum/local nil ::selected)
  (rum/local {} ::values)
  (rum/local false ::loaded?)
  (rum/local false ::saving?)
  (rum/local nil ::save-message)
  (rum/local false ::testing?)
  (rum/local nil ::test-result)
  (rum/local nil ::probe)
  (rum/local false ::installing?)
  (rum/local nil ::install-message)
  (rum/local "" ::url)
  (rum/local false ::opted-in?)
  {:will-mount (fn [state]
                 (load-providers! (get state ::providers))
                 (load-config! (get state ::selected) (get state ::values) (get state ::loaded?))
                 state)
   :did-update (fn [state]
                 (let [*providers (get state ::providers)
                       *selected (get state ::selected)]
                   ;; llm.json may name a provider that isn't installed (a
                   ;; connector that was removed, or never installed here).
                   (when (and (seq @*providers)
                              @*selected
                              (not (some #(= (:id %) @*selected) @*providers)))
                     (reset! *selected "anthropic"))
                   (when (and @(get state ::loaded?)
                              (= "local" @*selected)
                              (nil? @(get state ::probe)))
                     (probe-local! *selected (get state ::values) (get state ::probe))))
                 state)}
  [state]
  (let [*providers (get state ::providers)
        *selected (get state ::selected)
        *values (get state ::values)
        *loaded? (get state ::loaded?)
        *saving? (get state ::saving?)
        *save-message (get state ::save-message)
        *testing? (get state ::testing?)
        *test-result (get state ::test-result)
        *probe (get state ::probe)
        *installing? (get state ::installing?)
        *install-message (get state ::install-message)
        *url (get state ::url)
        *opted-in? (get state ::opted-in?)
        selected (or @*selected "anthropic")
        all-providers (or @*providers [])
        saved-managed-apikey (get-in @*values [managed-id "apiKey"])
        visible-providers (filterv #(provider-visible? % {:opted-in? @*opted-in?
                                                          :selected selected
                                                          :saved-apikey saved-managed-apikey})
                                   all-providers)
        spec (some #(when (= (:id %) selected) %) all-providers)
        fields (:fields spec)
        missing (missing-required fields @*values selected)
        can-add? (not (config/demo-graph?))
        has-managed? (some #(= (:id %) managed-id) all-providers)
        managed-hidden? (and has-managed? (not (some #(= (:id %) managed-id) visible-providers)))]
    [:div.panel-wrap
     (if-not (and @*loaded? @*providers)
       [:div.text-sm.opacity-60 "Loading…"]
       [:<>
        [:div.text-sm.my-2
         [:label.block.text-sm.font-medium.mb-1 {:for "llm-provider"} "Provider"]
         [:select.form-select.is-small
          {:id "llm-provider"
           :value selected
           :on-change (fn [e]
                        (let [p (util/evalue e)]
                          (reset! *selected p)
                          (reset! *test-result nil)
                          (reset! *probe nil)
                          (when (= p "local") (probe-local! *selected *values *probe))))}
          (for [{:keys [id label]} visible-providers]
            [:option {:key id :value id} label])]]

        (when (= "graph-local" (:source spec))
          [:div.text-xs.opacity-60.my-1
           "Installed connector · "
           [:a.underline {:on-click #(remove! (:id spec) *providers *selected *install-message)}
            "Remove"]])

        (for [field fields]
          (field-input field selected *values *probe *selected))

        (when (and (= "local" selected) @*probe)
          (local-probe-view *values *probe))

        [:div.text-xs.opacity-50.my-3
         "Stored in plaintext at .henhouse/llm.json inside your graph folder "
         "— a local-machine-only secret, not encrypted at rest. Add "
         (glossary/term "henhouse" ".henhouse/") " to your graph's ignore list if it's under version control."]

        (when (seq missing)
          [:div.text-xs.text-amber-500.my-1
           (str "Fill in: " (string/join ", " missing))])

        [:div.flex.gap-2.items-center.my-2
         (ui/button {:on-click #(save! *selected *values *saving? *save-message)
                     :disabled (or @*saving? (boolean (seq missing)))}
                    (if @*saving? "Saving…" "Save"))
         (ui/button {:on-click #(test-connection! *selected *values *testing? *test-result)
                     :disabled @*testing?
                     :background "gray"}
                    (if @*testing? "Testing…" "Test connection"))]

        (when-let [{:keys [type text]} @*save-message]
          [:div.text-sm.my-1 {:class (if (= type :success) "text-green-500" "text-red-500")} text])

        (when-let [{:keys [success error]} @*test-result]
          (if success
            [:div.text-sm.my-1.text-green-500 "Connection OK."]
            [:div.my-1 (llm-banner/error-view error)]))

        (if can-add?
          (add-connector-view *providers *selected *installing? *install-message *url)
          [:div.text-xs.opacity-50.mt-4.pt-3.border-t.border-gray-200.dark:border-gray-700
           "Open a folder as your graph to install a connector."])

        (when (and can-add? (not @*opted-in?) (or managed-hidden? (not has-managed?)))
          [:div.text-xs.mt-2.opacity-70
           [:a.underline {:on-click (fn []
                                      (reset! *opted-in? true)
                                      (when has-managed? (reset! *selected managed-id)))}
            "Have a Kip backend key?"]
           " Install the Kip connector package (ask your Kip admin for the "
           [:code ".tgz"] "), then pick " [:b "Kip (managed)"] "."])])]))
