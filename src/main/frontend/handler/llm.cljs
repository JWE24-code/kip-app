(ns frontend.handler.llm
  "Shared read of the LLM provider config (<graph>/.henhouse/llm.json), so the
  Peck and Hatch panels can tell the user up front when no provider is set —
  instead of failing on the first call.

  `configured?` is deliberately strict: it wants an explicit provider plus that
  provider's required credentials *in the config file*, not an ambient fallback
  (an Anthropic CLI profile, PROVIDER/*_API_KEY env vars). The retrieval layer
  will still use those fallbacks if present — this check just decides whether to
  nudge the user toward Settings → LLM."
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.state :as state]
            [promesa.core :as p]))

(defn- present? [s]
  (and (string? s) (not (string/blank? s))))

(defn- provider-ready?
  "The fields <graph>/.henhouse/llm.json must carry for `provider` to work
  without any env-var / ambient-credential fallback."
  [provider {:keys [apiKey model baseUrl]}]
  (case provider
    "anthropic"            (present? apiKey)
    ("openai" "deepseek")  (and (present? apiKey) (present? model))
    "other"                (and (present? apiKey) (present? model) (present? baseUrl))
    "local"                (and (present? model) (present? baseUrl))
    false))

(defn configured?
  "Given the parsed llm.json map (or nil), is a provider explicitly set up?"
  [cfg]
  (boolean
   (when-let [provider (some-> cfg :provider)]
     (provider-ready? provider (get-in cfg [:providers (keyword provider)])))))

(defn- vault-root []
  (config/get-repo-dir (state/get-current-repo)))

(defn refresh!
  "Re-read llm.json and cache the result in the app db under :kip/llm. Call on
  panel mount and after an LLM settings save. Never throws."
  []
  (-> (ipc/ipc "getLlmConfig" (vault-root))
      (p/then (fn [result]
                (state/set-state! :kip/llm {:loaded? true
                                            :configured? (configured? (some-> result bean/->clj))})))
      (p/catch (fn [_]
                 (state/set-state! :kip/llm {:loaded? true :configured? false})))))

(defn needs-setup?
  "True once we've read the config and no provider is configured. False while
  still loading, so the banner never flashes on mount."
  []
  (let [{:keys [loaded? configured?]} (:kip/llm @state/state)]
    (and loaded? (not configured?))))
