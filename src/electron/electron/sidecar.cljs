(ns electron.sidecar
  "Spawns and supervises the persistent Kip sidecar (the kip repo's
  sidecar/index.ts, bundled at <app>/sidecar) — one process per open coop,
  launched once and reused for the whole session instead of spawning a fresh
  script per chat action (kip-app#147).

  Main process only owns the *process*: it spawns the sidecar with `--port 0`
  and a random bearer token, then reads the sidecar's own discovery file
  (workspaceRoot(vault-root)/sidecar.json — see the kip repo's
  sidecar/discovery.ts) for the loopback port + token and hands that to the
  renderer over IPC. The renderer opens and owns the WebSocket (see
  frontend.handler.sidecar), so turn.delta/ask_user/chat.cancel never cross the
  main-process boundary on their hot path — only the initial wake-up does.

  The sidecar runs under the bundled Electron binary as a plain Node
  interpreter (process.execPath + ELECTRON_RUN_AS_NODE=1), same as
  electron.wiki's script runner, so a packaged Kip needs no system `node`. It
  ships at <app>/sidecar (gulp's syncSidecar copies the kip repo's sidecar/
  source + installs its own node_modules there); KIP_SIDECAR_DIR overrides the
  lookup for dev. It tears itself down on SIGTERM/SIGINT or when its parent
  dies; we also stop every managed process explicitly on app teardown."
  (:require ["child_process" :as child-process]
            ["crypto" :as crypto]
            ["fs" :as fs]
            ["path" :as node-path]
            ["electron" :refer [app]]
            [clojure.string :as string]
            [electron.logger :as logger]
            [electron.wiki :as wiki]
            [promesa.core :as p]))

(def log-error (partial logger/error "[Sidecar]"))

;; paths.js is the kip scripts' single source of truth for where machine-local
;; state lives (workspaceRoot). Required at runtime so the app and the sidecar
;; agree on the discovery-file location without a second copy of the logic.
(def paths-lib (js/require (.join node-path wiki/scripts-dir "lib" "paths.js")))

;; sidecar/ is expected next to scripts/ inside the app (becoming
;; <app>/sidecar once packaged). KIP_SIDECAR_DIR overrides it, so a dev build
;; can point at a kip checkout's sidecar/ without a packaging round trip.
(def sidecar-dir
  (let [override (aget (.-env js/process) "KIP_SIDECAR_DIR")]
    (if (and (string? override) (not (string/blank? override)))
      override
      (let [p (.join node-path (.getAppPath app) "sidecar")]
        (if (string/includes? p ".asar")
          (string/replace p #"app\.asar([\\/])" "app.asar.unpacked$1")
          p)))))

(defn entry-point
  "Absolute path to the sidecar entry. Prefers a compiled index.js when one
  was shipped, otherwise the TypeScript source (run via Node type stripping)."
  []
  (let [js (.join node-path sidecar-dir "index.js")]
    (if (fs/existsSync js)
      js
      (.join node-path sidecar-dir "index.ts"))))

(defn available?
  "Is the sidecar actually bundled? False in a dev tree without the kip repo's
  sidecar/ synced in, which lets the caller fall back instead of spawning a
  path that doesn't exist."
  []
  (fs/existsSync (entry-point)))

(defn- discovery-file
  "workspaceRoot(vault-root)/sidecar.json — where the sidecar publishes its
  loopback url + token. Uses the same paths.js the sidecar does."
  [vault-root]
  (.join node-path (.workspaceRoot paths-lib vault-root) "sidecar.json"))

(defn- read-discovery
  "Parsed discovery info (CLJS map) or nil when there's no readable file."
  [vault-root]
  (try
    (js->clj (js/JSON.parse (fs/readFileSync (discovery-file vault-root) "utf8"))
             :keywordize-keys true)
    (catch :default _ nil)))

(defn- generate-token
  []
  (-> (crypto/randomBytes 32) (.toString "hex")))

;; vault-root -> {:proc <child> :token <string> :started-at <ms>}
(defonce *procs (atom {}))

(defn- alive? [proc]
  (and proc (nil? (.-exitCode proc)) (not (.-killed proc))))

(defn- spawn-env
  "process.env plus ELECTRON_RUN_AS_NODE (run the child as plain Node) and
  KIP_COOP_ROOT so the sidecar's own defaults (and its paths.js) point at the
  open coop."
  [vault-root]
  (js/Object.assign #js {} (.-env js/process)
                    #js {"ELECTRON_RUN_AS_NODE" "1"
                         "KIP_COOP_ROOT" vault-root}))

(defn- spawn-sidecar!
  [vault-root]
  (let [token (generate-token)
        entry (entry-point)
        flags (array "--port" "0"
                     "--vault-root" vault-root
                     "--token" token
                     ;; Keep the socket alive between turns/retries instead of
                     ;; the sidecar's 5s idle self-kill — the app owns the
                     ;; lifetime and stops it explicitly.
                     "--silence-ms" "0")
        ;; The sidecar ships as TypeScript; Node's type stripping runs it with
        ;; no build step. On a Node that still gates it behind the flag, pass
        ;; it explicitly (harmless where stripping is already on by default).
        args (if (string/ends-with? entry ".ts")
               (apply array "--experimental-strip-types" entry flags)
               (apply array entry flags))
        proc (child-process/spawn
              (.-execPath js/process)
              args
              #js {:cwd sidecar-dir :env (spawn-env vault-root)})]
    (.on (.-stderr proc) "data" (fn [chunk] (logger/debug "[Sidecar]" (str chunk))))
    (.on proc "error" (fn [err] (log-error (str "failed to start sidecar: " err))))
    (.on proc "exit" (fn [code]
                       (logger/info "[Sidecar]" (str "exited with code " code))
                       ;; Only drop the entry if it still points at this
                       ;; process — a respawn may already have replaced it.
                       (swap! *procs (fn [m]
                                       (if (identical? proc (get-in m [vault-root :proc]))
                                         (dissoc m vault-root)
                                         m)))))
    (logger/info "[Sidecar]" (str "started for " vault-root))
    (swap! *procs assoc vault-root {:proc proc :token token :started-at (js/Date.now)})
    proc))

(def ^:private discovery-timeout-ms 10000)
(def ^:private discovery-poll-ms 50)

(defn- wait-for-discovery
  "Poll for the sidecar's discovery file, resolving the parsed info or
  rejecting after `discovery-timeout-ms`. The file is written atomically, so a
  read either sees the complete new file or nothing — no half-written frame."
  [vault-root]
  (p/create
   (fn [resolve* reject*]
     (let [start (js/Date.now)
           step (fn step []
                  (if-let [info (read-discovery vault-root)]
                    (resolve* info)
                    (if (> (- (js/Date.now) start) discovery-timeout-ms)
                      (reject* (js/Error. "The Kip sidecar did not start (no discovery file)."))
                      (js/setTimeout step discovery-poll-ms))))]
       (step)))))

(defn ensure!
  "Make sure a sidecar is running for `vault-root` and resolve its discovery
  info ({port token pid protocolVersion vaultRoot url startedAt}). Spawns a
  process the first time; respawns if the tracked one died. Idempotent and
  safe to call on every panel mount. Rejects promptly when the sidecar isn't
  bundled, so the caller can fall back instead of waiting on a spawn that
  can't succeed."
  [vault-root]
  (cond
    (not (available?))
    (p/rejected (js/Error. "Kip's sidecar isn't bundled in this build."))

    (string/blank? vault-root)
    (p/rejected (js/Error. "Open a folder first — the Kip sidecar runs per coop."))

    :else
    (let [{:keys [proc]} (get @*procs vault-root)]
      (if (alive? proc)
        (wait-for-discovery vault-root)
        (do
          ;; A dead process may leave a stale discovery file behind; drop it so
          ;; wait-for-discovery can't return the old sidecar's port/token.
          (try (fs/rmSync (discovery-file vault-root) #js {:force true})
               (catch :default _ nil))
          (spawn-sidecar! vault-root)
          (wait-for-discovery vault-root))))))

(defn stop!
  "Stop the sidecar for one coop (SIGTERM; the sidecar removes its discovery
  file on the way out)."
  [vault-root]
  (when-let [{:keys [proc]} (get @*procs vault-root)]
    (try
      (when (alive? proc) (.kill proc "SIGTERM"))
      (catch :default e (log-error (str "failed to stop sidecar: " e)))))
  (swap! *procs dissoc vault-root))

(defn stop-all!
  "Stop every managed sidecar. Called on app teardown / last-window close."
  []
  (doseq [vault-root (keys @*procs)]
    (stop! vault-root)))
