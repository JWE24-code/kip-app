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

            #"(?i)\(429\)|rate.?limit|too many requests|quota|insufficient_quota"
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
