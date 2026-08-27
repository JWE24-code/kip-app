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
            ["path" :as node-path]
            [electron.wiki :as wiki]))

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
