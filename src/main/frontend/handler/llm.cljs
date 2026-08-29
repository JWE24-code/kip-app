(ns frontend.handler.llm
  "Shared read of the LLM provider config (<graph>/.henhouse/llm.json), so the
  Peck and Hatch panels can tell the user up front when no provider is set —
  instead of failing on the first call.

  Readiness is decided by the connector registry: `refresh!` caches
  `listLlmProviders` (each provider's `ready` = its ProviderSpec's own
  `isReady` against the resolved config) alongside the selected provider, and
  `configured?` just reads the selected one's flag. `configured?` also takes a
  bare config map (no registry) — then it falls back to a generic 'explicit
  provider + a non-blank apiKey in llm.json' check."
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.state :as state]
            [promesa.core :as p]))

(defn- present? [s]
  (and (string? s) (not (string/blank? s))))

(defn configured?
  "Is the config's selected provider ready to use? Prefers the readiness flag
  from `providers` (a cached `listLlmProviders` result); falls back to
  'explicit provider + a non-blank apiKey in llm.json' when the registry
  isn't available or doesn't know the provider."
  ([cfg] (configured? cfg nil))
  ([cfg providers]
   (boolean
    (when-let [provider (some-> cfg :provider not-empty)]
      (if-let [p (some #(when (= (:id %) provider) %) providers)]
        (:ready p)
        (present? (get-in cfg [:providers (keyword provider) :apiKey])))))))

(defn- vault-root []
  (config/get-repo-dir (state/get-current-repo)))

(defn refresh!
  "Re-read llm.json + the connector list and cache them in the app db under
  :kip/llm. Call on panel mount and after an LLM settings save. Never throws."
  []
  (-> (p/let [cfg-js (ipc/ipc "getLlmConfig" (vault-root))
              providers-js (ipc/ipc "listLlmProviders" (vault-root))]
        (let [cfg (some-> cfg-js bean/->clj)
              providers (vec (js->clj providers-js :keywordize-keys true))]
          (state/set-state! :kip/llm {:loaded? true
                                      :provider (:provider cfg)
                                      :providers providers
                                      :configured? (configured? cfg providers)})))
      (p/catch (fn [_]
                 (state/set-state! :kip/llm {:loaded? true :configured? false})))))

(defn needs-setup?
  "True once we've read the config and no provider is configured. False while
  still loading, so the banner never flashes on mount."
  []
  (let [{:keys [loaded? configured?]} (:kip/llm @state/state)]
    (and loaded? (not configured?))))

(defn humanize-error
  "Turn a raw LLM error (a string — a rejected IPC message, a `failed` entry's
  :error, stderr) into {:title :hint :raw}. :title/:hint are nil when nothing
  matched — callers fall back to showing :raw."
  [raw]
  (let [s (str raw)
        m (condp #(re-find %1 %2) s
            #"(?i)\((?:401|403)\)|unauthor|invalid.{0,12}api.?key|authentication.?fail|x-api-key"
            {:title "The provider rejected your API key."
             :hint  "Check the key in Settings → LLM (and that it matches the selected provider)."}

            ;; 402 = the managed Kip backend's quota/budget response; also
            ;; OpenAI's insufficient_quota (billing exhausted, not "slow down").
            ;; Checked before 429 so those claim the token.
            #"(?i)\(402\)|insufficient_quota|payment required|plan limit|budget (?:exceeded|limit|reached)|over budget"
            {:title "You've hit a usage or billing limit."
             :hint  "This provider is out of quota or budget for now. Check your plan / billing, or ask your Kip backend admin to raise the limit."}

            #"(?i)\(429\)|rate.?limit|too many requests|quota"
            {:title "The provider is rate-limiting you."
             :hint  "Wait a minute and try again — or check your plan's usage limits."}

            #"(?i)ECONNREFUSED|fetch failed|ECONNRESET|socket hang up|network error"
            {:title "Couldn't reach the LLM provider."
             :hint  "Check your connection. For a local model, make sure the server is running (e.g. `ollama serve`)."}

            #"(?i)ENOTFOUND|EAI_AGAIN|getaddrinfo"
            {:title "Couldn't resolve the provider's address."
             :hint  "Check the Base URL in Settings → LLM."}

            #"(?i)ETIMEDOUT|TimeoutError|timed? ?out|request timeout"
            {:title "The request timed out."
             :hint  "The provider may be slow or overloaded — try again."}

            #"(?i)_API_KEY is required|_MODEL is required|_BASE_URL is required|no model configured|no provider|provider .{0,10}not"
            {:title "The LLM provider isn't fully set up."
             :hint  "Fill in the missing field in Settings → LLM."}

            #"(?i)\(404\)|model.{0,24}(not found|does not exist|no such|unknown|not available|invalid)|does not exist.{0,8}model"
            {:title "That model isn't available for this provider."
             :hint  "Pick a valid model in Settings → LLM."}

            #"(?i)\(5\d\d\)|internal server error|service unavailable|overloaded|bad gateway"
            {:title "The provider had a server error."
             :hint  "Usually temporary — try again in a moment."}

            nil)]
    (assoc m :raw s)))
