(ns electron.wiki
  "Shells out to Kip's coop-maintenance scripts (bundled at <app>/scripts,
  synced there from the repo's ./scripts by gulp — not part of upstream
  logseq/og) and returns parsed JSON results to the renderer.

  Deliberately NOT routed through electron.shell's :runCli path, since that's
  gated by a commands-allowlist meant for a small set of known external tools
  (git, pandoc, ...); adding a Node runner there would let any
  renderer-reachable code run arbitrary Node scripts, not just these. The one
  renderer-supplied argument that reaches here — the Peck question — is passed
  as an argv entry to a no-shell spawn, so it can't break out into a shell
  command.

  The scripts run under the bundled Electron binary as a plain Node
  interpreter (process.execPath + ELECTRON_RUN_AS_NODE=1) — no system `node`
  needed, and they load the same better-sqlite3 the app already bundles for
  its search index (Electron ABI), so a packaged Kip is self-contained. The
  old in-process electron.llm bridge for peck/hatch was still retired: a
  child process keeps the (synchronous, blocking) SQLite + LLM work off the
  main process.

  Every call takes the current graph's directory as `vault-root` and exports
  it as KIP_COOP_ROOT for the child, so the scripts operate on the coop
  (eggs/, nest/, clucks/, .roost/) inside the open graph — see
  scripts/lib/paths.js."
  (:require ["child_process" :as child-process]
            ["fs" :as fs]
            ["path" :as node-path]
            ["electron" :refer [app dialog shell]]
            [clojure.string :as string]
            [electron.logger :as logger]
            [promesa.core :as p]))

(def log-error (partial logger/error "[Coop]"))

;; scripts/ ships inside the app: gulp syncs ../scripts (source + a pure-JS
;; node_modules; better-sqlite3 comes from the app's own Electron-ABI copy)
;; into static/scripts, which is app.getAppPath()/scripts both in dev (static/)
;; and packaged (resources/app/).
(def scripts-dir (.join node-path (.getAppPath app) "scripts"))

(defn- script
  "Absolute path to a bundled coop-maintenance script, e.g. (script \"groom.js\").
  node-path/join so the separator is right on every OS (Linux included)."
  [name]
  (.join node-path scripts-dir name))

(defn- roost-file
  "Absolute path to a file in the open coop's .roost/ cache."
  [vault-root name]
  (.join node-path vault-root ".roost" name))

(defn- script-env
  "process.env plus ELECTRON_RUN_AS_NODE (run the child as plain Node) and
  KIP_COOP_ROOT = the open graph's directory. nil vault-root (no graph open)
  omits KIP_COOP_ROOT — the scripts then fall back to their bundled ./coop."
  [vault-root]
  (let [base #js {"ELECTRON_RUN_AS_NODE" "1"}]
    (js/Object.assign #js {} (.-env js/process)
                      (if vault-root
                        (js/Object.assign base #js {"KIP_COOP_ROOT" vault-root})
                        base))))

(defn- run-node-script!
  "Spawns the bundled Electron-as-Node against <script-path> <args...> (cwd =
  scripts-dir, no shell — args go through as an argv array), collects stdout,
  and resolves a promise with the parsed JSON result on a clean exit, or
  rejects with stderr (or the parse error) otherwise."
  [script-path vault-root args]
  (let [deferred (p/deferred)
        job (child-process/spawn (.-execPath js/process)
                                 (apply array script-path args)
                                 #js {:cwd scripts-dir :env (script-env vault-root)})
        stdout-chunks (atom [])
        stderr-chunks (atom [])]
    (.on (.-stdout job) "data" (fn [chunk] (swap! stdout-chunks conj (str chunk))))
    (.on (.-stderr job) "data" (fn [chunk] (swap! stderr-chunks conj (str chunk))))
    (.on job "error" (fn [err]
                       (log-error (str "Failed to start " script-path ": " err))
                       (p/reject! deferred err)))
    (.on job "close"
         (fn [code]
           (let [stdout (apply str @stdout-chunks)
                 stderr (apply str @stderr-chunks)]
             (if (zero? code)
               (try
                 (p/resolve! deferred (js/JSON.parse stdout))
                 (catch :default e
                   (log-error (str "Failed to parse output from " script-path ": " e))
                   (p/reject! deferred (str "Could not parse output from " script-path))))
               (do
                 (log-error (str script-path " exited with code " code ": " stderr))
                 (p/reject! deferred (if (string/blank? stderr)
                                       (str script-path " exited with code " code)
                                       stderr)))))))
    deferred))

(defn recent-clucks! [vault-root]
  (run-node-script! (script "recent-clucks.js") vault-root []))

(defn groom! [vault-root]
  (run-node-script! (script "groom.js") vault-root ["--json"]))

(defn groom-deep!
  "The weekly deep groom (scripts/groom.js --deep): every quick check plus
  per-page _Update_ reconciliation, summary drift, missing/broken/dead-end
  links, content-level merge candidates, and a deeper contradiction pass.
  Many LLM calls / minutes. Also writes <coop>/.roost/groom-report.md (a
  checklist) and groom-metrics.json. Resolves to the full report incl.
  :reportPath."
  [vault-root]
  (run-node-script! (script "groom.js") vault-root ["--deep" "--json"]))

(defn groom-progress!
  "Reads <coop>/.roost/groom-progress.json, written continuously by a deep
  groom run (same shape as hatch-progress.json: {done total current running
  activity metrics}). nil when there's none. Plain file read for cheap polling."
  [vault-root]
  (p/create
   (fn [resolve* _reject]
     (try
       (resolve* (js/JSON.parse (fs/readFileSync (roost-file vault-root "groom-progress.json") "utf8")))
       (catch :default _ (resolve* nil))))))

(defn groom-metrics!
  "Reads <coop>/.roost/groom-metrics.json ({at summary entries}), written at
  the end of a deep groom run. nil when there's none — used for the 'last
  deep groom N days ago' note."
  [vault-root]
  (p/create
   (fn [resolve* _reject]
     (try
       (resolve* (js/JSON.parse (fs/readFileSync (roost-file vault-root "groom-metrics.json") "utf8")))
       (catch :default _ (resolve* nil))))))

(defn hatch-preview!
  "What a 'Hatch sources' run would touch — new/changed files in eggs/,
  journals/, pages/, plus the oversized and empty ones it skips. No LLM
  calls. Resolves to {:pending [...] :oversized [...] :empty [...] :totalKb n}."
  [vault-root]
  (run-node-script! (script "hatch-all.js") vault-root ["--preview"]))

(defn hatch-batch!
  "Hatches up to `limit` pending source files — no per-file review. Resolves
  to {:hatched [...] :failed [...] :oversized [...] :empty [...] :remaining n
  :metrics {...}}. When `trace?`, the run also streams full prompts/responses
  to <coop>/.roost/hatch-trace.jsonl and attaches short text previews to the
  activity feed in hatch-progress.json. When `classic?`, uses the old
  one-propose-plus-one-generate-call-per-page path instead of the default
  single combined call per file."
  [vault-root limit trace? classic?]
  (run-node-script! (script "hatch-all.js") vault-root
                    (cond-> ["--limit" (str limit)]
                      trace? (conj "--trace")
                      classic? (conj "--classic"))))

(defn hatch-progress!
  "Reads <coop>/.roost/hatch-progress.json, written continuously by
  hatch-all.js during a batch. Resolves to {:done :total :current :running
  :activity [...] :metrics {...}} or nil when there's no readable progress
  file (no run yet / between runs). A plain file read — no subprocess — so
  the modal can poll it cheaply."
  [vault-root]
  (p/create
   (fn [resolve* _reject]
     (try
       (resolve* (js/JSON.parse (fs/readFileSync (roost-file vault-root "hatch-progress.json") "utf8")))
       (catch :default _ (resolve* nil))))))

(defn hatch-metrics!
  "Reads <coop>/.roost/hatch-metrics.json, written by hatch-all.js at the end
  of a batch: {:at :summary {...} :entries [...] :perFile [...]}. Resolves to
  the parsed object, or nil when there's none. Content-free — timing and
  token counts only."
  [vault-root]
  (p/create
   (fn [resolve* _reject]
     (try
       (resolve* (js/JSON.parse (fs/readFileSync (roost-file vault-root "hatch-metrics.json") "utf8")))
       (catch :default _ (resolve* nil))))))

(defn peck!
  "Runs the Peck workflow for `question` without filing the answer back.
  Resolves to {:answer :citedSlugs :candidateSlugs :steps}. `steps` is what
  the skills tool loop ran (scripts/lib/skills.js), if anything. With `trace?`
  the run also streams every LLM + skill call, full I/O included, to
  <coop>/.roost/peck-trace.jsonl."
  ([vault-root question] (peck! vault-root question false))
  ([vault-root question trace?]
   (run-node-script! (script "chat.js") vault-root
                     (cond-> [question] trace? (conj "--trace")))))

(defn peck-progress!
  "Reads <coop>/.roost/peck-progress.json, written continuously by chat.js
  during a Peck turn — same shape as hatch-progress.json ({phase running
  activity metrics}). nil when there's none. Plain file read for cheap
  polling while the panel waits for an answer."
  [vault-root]
  (p/create
   (fn [resolve* _reject]
     (try
       (resolve* (js/JSON.parse (fs/readFileSync (roost-file vault-root "peck-progress.json") "utf8")))
       (catch :default _ (resolve* nil))))))

(defn skills-list!
  "The skills Peck can see (scripts/skills-list.js) — content-free: name,
  description, whenToUse, source, network, enabled, parameters. No secrets, no
  paths."
  [vault-root]
  (run-node-script! (script "skills-list.js") vault-root []))

;; --------------------------------------------------------------------------
;; Reminders (scripts/reminders.js, <coop>/reminders.json). The scheduler in
;; electron.reminders polls `reminders-due!`; the panel uses list/add/cancel.
;; --------------------------------------------------------------------------

(defn reminders-due!
  "Fires every pending reminder whose lead time has arrived: retrieves related
  nest pages, drafts a short prep brief, marks it notified, logs it. Resolves
  to {:fired [{id title eventAt leadMin relatedSlugs context …}]}."
  [vault-root]
  (run-node-script! (script "reminders.js") vault-root ["--due" "--json"]))

(defn reminders-list! [vault-root]
  (run-node-script! (script "reminders.js") vault-root ["list" "--all" "--json"]))

(defn reminders-add! [vault-root text]
  (run-node-script! (script "reminders.js") vault-root ["add" text "--json"]))

(defn reminders-cancel! [vault-root id]
  (run-node-script! (script "reminders.js") vault-root ["cancel" (str id) "--json"]))

(defn reminders-mute! [vault-root id on?]
  (run-node-script! (script "reminders.js") vault-root [(if on? "unmute" "mute") (str id) "--json"]))

;; --------------------------------------------------------------------------
;; <coop>/exports/ — the files Peck's docx/pptx/… skills produce. The Exports
;; panel (frontend.components.exports) lists them and drives these actions.
;; The renderer only ever sends a bare filename it got from exports-list!;
;; resolve-export re-validates that it names a real file directly inside
;; exports/, so a crafted name can't reach anything else.
;; --------------------------------------------------------------------------

(defn- exports-dir [vault-root]
  (.resolve node-path (.join node-path vault-root "exports")))

(defn- resolve-export
  "vault-root + a bare filename → the absolute path, but only when it names an
  existing file sitting directly in <coop>/exports/. nil otherwise."
  [vault-root filename]
  (when (and (string? vault-root) (string? filename)
             (not (string/blank? filename))
             (not (re-find #"[\\/]" filename))
             (not (contains? #{"." ".."} filename)))
    (let [dir (exports-dir vault-root)
          full (.resolve node-path dir filename)]
      (when (and (= (.dirname node-path full) dir)
                 (try (.isFile (fs/statSync full)) (catch :default _ false)))
        full))))

(defn exports-list!
  "Files in <coop>/exports/, newest first: [{:name :size :mtime :ext} …].
  A plain fs read — no subprocess. Empty when the folder doesn't exist yet."
  [vault-root]
  (p/create
   (fn [resolve* _reject]
     (try
       (let [dir (exports-dir vault-root)
             files (->> (fs/readdirSync dir)
                        (keep (fn [nm]
                                (try
                                  (let [st (fs/statSync (.join node-path dir nm))]
                                    (when (.isFile st)
                                      {:name nm
                                       :size (.-size st)
                                       :mtime (.getTime (.-mtime st))
                                       :ext (-> (.extname node-path nm) (subs 1) string/lower-case)}))
                                  (catch :default _ nil))))
                        (sort-by :mtime >)
                        vec)]
         (resolve* (clj->js files)))
       (catch :default _ (resolve* #js []))))))

(defn export-open!
  "Open the file with the OS default app. Resolves {:ok :error}."
  [vault-root filename]
  (p/create
   (fn [resolve* _reject]
     (if-let [full (resolve-export vault-root filename)]
       (-> (.openPath shell full)
           (.then (fn [err] (resolve* (clj->js {:ok (string/blank? err) :error err}))))
           (.catch (fn [e] (resolve* (clj->js {:ok false :error (str e)})))))
       (resolve* (clj->js {:ok false :error "file not found"}))))))

(defn export-reveal!
  "Show the file in the OS file manager. Resolves {:ok true}."
  [vault-root filename]
  (p/create
   (fn [resolve* _reject]
     (when-let [full (resolve-export vault-root filename)]
       (.showItemInFolder shell full))
     (resolve* (clj->js {:ok true})))))

(defn export-save-as!
  "Prompt for a destination and copy the file there. Resolves {:ok :path} /
  {:ok false :canceled true} / {:ok false :error}."
  [vault-root filename]
  (p/create
   (fn [resolve* _reject]
     (if-let [full (resolve-export vault-root filename)]
       (-> (.showSaveDialog dialog #js {:defaultPath filename})
           (.then (fn [^js res]
                    (if (or (.-canceled res) (not (.-filePath res)))
                      (resolve* (clj->js {:ok false :canceled true}))
                      (try
                        (fs/copyFileSync full (.-filePath res))
                        (resolve* (clj->js {:ok true :path (.-filePath res)}))
                        (catch :default e
                          (resolve* (clj->js {:ok false :error (str e)})))))))
           (.catch (fn [e] (resolve* (clj->js {:ok false :error (str e)})))))
       (resolve* (clj->js {:ok false :error "file not found"}))))))

(defn export-trash!
  "Move the file to the OS trash (recoverable — not a hard delete). Resolves
  {:ok :error}."
  [vault-root filename]
  (p/create
   (fn [resolve* _reject]
     (if-let [full (resolve-export vault-root filename)]
       (-> (.trashItem shell full)
           (.then (fn [] (resolve* (clj->js {:ok true}))))
           (.catch (fn [e] (resolve* (clj->js {:ok false :error (str e)})))))
       (resolve* (clj->js {:ok false :error "file not found"}))))))
