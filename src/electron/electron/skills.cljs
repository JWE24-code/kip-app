(ns electron.skills
  "In-process bridge to scripts/lib/skills.js for the Skills settings tab (see
  :skills in settings.cljs) — the skill on/off list plus the web-search backend
  picker, stored in <graph>/.henhouse/skills.json. Required via js/require (a
  plain runtime call, not a CLJS :require, so shadow-cljs's :node-script target
  doesn't try to statically bundle it).

  Same rationale as electron.llm: skills.js only touches the filesystem and
  child_process.execFile — no native deps — so it's safe on the main process.
  Peck itself still runs the retrieval layer as a spawned child (electron.wiki),
  where better-sqlite3's Electron ABI matters; that path is unchanged."
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            ["path" :as node-path]
            [electron.wiki :as wiki]
            [promesa.core :as p]))

(def skills-lib (js/require (.join node-path wiki/scripts-dir "lib" "skills.js")))

(defn list!
  "Content-free view of every skill (enabled + disabled): [{name description
  whenToUse source network enabled parameters} …]. Synchronous."
  [vault-root]
  (.describeSkills skills-lib (or vault-root js/undefined)))

(defn set-enabled!
  "Adds/removes `name` in skills.json \"disabled\", preserving every other
  field. Returns {name enabled}."
  [vault-root name enabled]
  (.setSkillEnabled skills-lib (or vault-root js/undefined) name (boolean enabled)))

(defn set-approval!
  "Records a review decision for a user skill in skills.json \"approved\".
  `decision` is \"always\", \"never\", or nil (forget). Built-ins ignore it.
  Returns {name approval}."
  [vault-root name decision]
  (.setSkillApproval skills-lib (or vault-root js/undefined) name
                     (if (contains? #{"always" "never"} decision) decision js/undefined)))

(defn get-search-config!
  "{backend braveApiKey tavilyApiKey} — backend defaults to \"duckduckgo\"."
  [vault-root]
  (.loadSearchSettings skills-lib (or vault-root js/undefined)))

(defn save-search-config!
  "Writes the web-search backend into config['web-search'] and its keys into
  secrets['web-search'] (read-modify-write; leaves `disabled` + other skills
  untouched). `m` is {:backend :braveApiKey :tavilyApiKey}; an omitted field is
  left unchanged, an empty-string key is removed. Returns the reloaded config."
  [vault-root m]
  (.saveSearchSettings skills-lib (or vault-root js/undefined) (bean/->js m)))

(defn test-search!
  "Runs the web-search skill once with `candidate` (the settings form's
  possibly-unsaved {:backend :braveApiKey :tavilyApiKey}) against `query`.
  Never rejects — resolves to {ok output} / {ok false error}."
  [vault-root query candidate]
  (let [skills (.discoverSkills skills-lib (or vault-root js/undefined) #js {:includeDisabled true})
        skill (some (fn [s] (when (= "web-search" (.-name s)) s)) skills)
        {:keys [backend braveApiKey tavilyApiKey]} candidate
        env #js {"SEARCH_BACKEND" (or backend "duckduckgo")}]
    (when-not (string/blank? braveApiKey) (unchecked-set env "BRAVE_API_KEY" braveApiKey))
    (when-not (string/blank? tavilyApiKey) (unchecked-set env "TAVILY_API_KEY" tavilyApiKey))
    (if-not skill
      (p/resolved (clj->js {:ok false :error "web-search skill not found"}))
      (-> (.runSkill skills-lib skill #js {:query query :count 3} (or vault-root js/undefined) #js {:env env})
          (.then (fn [^js r]
                   (clj->js {:ok (.-ok r) :output (.-output r) :error (.-error r)})))
          (.catch (fn [e] (clj->js {:ok false :error (str e)})))))))
