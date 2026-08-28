(ns electron.llm
  "In-process bridge to scripts/lib/llm.js for the LLM settings tab only —
  the provider/model/API-key form, stored in <graph>/.henhouse/llm.json.
  Required via js/require (a plain runtime function call, not a CLJS
  :require form, so shadow-cljs's :node-script target doesn't try to
  statically bundle it).

  This is the *only* thing still done in-process. Peck and Hatch lived
  here too but moved to electron.wiki's shell-out path: their code pulls in
  better-sqlite3's native addon, which can't be ABI-correct for both plain
  Node and Electron's bundled Node at once. llm.js itself only touches the
  filesystem and fetch — no native deps — so it stays here."
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            ["path" :as node-path]
            [electron.wiki :as wiki]
            [promesa.core :as p]))

(def llm-lib (js/require (.join node-path wiki/scripts-dir "lib" "llm.js")))

(defn get-llm-config!
  "Returns the parsed <graph>/.henhouse/llm.json, or null if it doesn't
  exist. Synchronous. vault-root nil (no graph open) -> the scripts' own
  ./coop."
  [vault-root]
  (.loadLLMConfig llm-lib (or vault-root js/undefined)))

(defn save-llm-config!
  "Writes config (a CLJS map) to <graph>/.henhouse/llm.json."
  [vault-root config]
  (.saveLLMConfig llm-lib (bean/->js config) (or vault-root js/undefined))
  nil)

(defn test-llm-connection!
  "Fires a trivial LLM call against `candidate` (a CLJS map: {:provider
  :apiKey :model :baseUrl}, i.e. the settings form's current, possibly
  unsaved values — not whatever's already saved). Never rejects — resolves
  to {success true/false, reply/error}."
  [candidate]
  (.testConnection llm-lib (bean/->js candidate)))

(defn probe-local!
  "GET <base-url>/models — Ollama serves it at its OpenAI-compatible path, as
  does any OpenAI-compatible local server. Resolves a JS object
  {ok bool, models [ids], error str}. 3s timeout; a failed probe is not an
  error the user should see as a stack trace.

  Uses the Node global fetch (no proxy agent) — a localhost endpoint must not
  be routed through a system HTTP proxy."
  [base-url]
  (if (string/blank? base-url)
    (p/resolved #js {:ok false :error "Set a base URL first."})
    (-> (js/fetch (str (string/replace base-url #"/+$" "") "/models")
                  #js {:signal (js/AbortSignal.timeout 3000)})
        (p/then (fn [^js res]
                  (if (.-ok res)
                    (p/then (.json res)
                            (fn [j]
                              (let [models (->> (:data (bean/->clj j)) (keep :id) sort vec)]
                                #js {:ok true :models (clj->js models)})))
                    #js {:ok false :error (str "Server responded " (.-status res) ".")})))
        (p/catch (fn [^js e]
                   ;; global fetch wraps the real network error in e.cause
                   (let [name (str (.-name e))
                         code (str (some-> e .-cause .-code))
                         msg  (str (.-message e))]
                     #js {:ok false
                          :error (cond
                                   (= name "TimeoutError")     "No response — is the server running?"
                                   (= code "ECONNREFUSED")     "Nothing is listening there — start it (e.g. `ollama serve`)."
                                   (contains? #{"ENOTFOUND" "EAI_AGAIN"} code) "Can't resolve that host."
                                   (string/blank? msg)         "Couldn't reach it."
                                   :else                       msg)}))))))
