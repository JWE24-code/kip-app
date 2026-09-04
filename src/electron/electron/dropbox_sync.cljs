(ns electron.dropbox-sync
  "Two-way sync of a graph folder through the connected Dropbox account
  (kip-app v0.4.0). Dropbox is the source of truth for \"latest\"; git
  auto-commit stays on only as a local undo history.

  Model:
    - a synced graph maps to /<graph-name> inside the app folder (Apps/Kip-ai/)
    - remote → local: files/list_folder + a cursor, longpolled for changes
    - local → remote: a dedicated chokidar watcher, debounced
    - conflicts (both sides changed a file since the last sync):
        \"auto\"   — last write wins by mtime; the loser is overwritten
                     (Dropbox keeps server-side history, so it's recoverable)
        \"manual\" — keep both: the remote copy lands as
                     `<name> (Dropbox <date>).<ext>`, a notification lists it

  Per-graph state lives in userData/dropbox-sync/<sha1(path)>.json — machine-
  local, never synced, safe to delete (a delete forces a full reconcile).

  NOT synced: .roost/ (derived cache), .henhouse/ (LLM keys, ICS URLs — stay
  on the machine), .git/, logseq/.recycle|bak/."
  (:require ["chokidar" :as chokidar]
            ["crypto" :as crypto]
            ["fs" :as fs]
            ["path" :as node-path]
            [cljs-bean.core :as bean]
            [clojure.string :as string]
            [electron.dropbox :as dbx]
            [electron.logger :as logger]
            ["electron" :refer [app]]
            [promesa.core :as p]))

(def ^:private log (partial logger/info "[DropboxSync]"))
(def ^:private log-error (partial logger/error "[DropboxSync]"))

(def ^:private excluded-dirs #{".roost" ".henhouse" ".git" "node_modules"})
(def ^:private excluded-rel #{"logseq/.recycle" "logseq/bak"})
(def ^:private debounce-ms 2500)
(def ^:private max-file-bytes (* 140 1024 1024))

;; graph-path -> { :watcher chokidar :timer id :pending #{rel} :syncing? bool :loop-stop fn }
(defonce ^:private *runtime (atom {}))

;; per-graph serialization — push flushes and pulls both rewrite the state
;; file, so they must not interleave. Each op chains after the last.
(defonce ^:private *locks (atom {}))

(defn- with-lock [graph-path f]
  (let [prev (get @*locks graph-path (p/resolved nil))
        run (p/then prev (fn [_] (f)))]
    (swap! *locks assoc graph-path (p/catch run (fn [_] nil)))
    run))

;; abs-path -> content-hash we just wrote from a pull. The chokidar watcher
;; checks this: if the file on disk is exactly what a pull put there, that
;; `change` event is our own echo — drop it instead of pushing it back. TTL'd
;; so a genuine later edit of the same file isn't muted.
(defonce ^:private *pull-writes (atom {}))
(def ^:private pull-write-ttl-ms 20000)

(defn- note-pull-write! [abs hash]
  (swap! *pull-writes assoc abs hash)
  (js/setTimeout #(swap! *pull-writes (fn [m] (if (= (get m abs) hash) (dissoc m abs) m)))
                 pull-write-ttl-ms))

(defn- own-echo? [abs current-hash]
  (= current-hash (get @*pull-writes abs)))

;; ---------------------------------------------------------------------------
;; per-graph state file
;; ---------------------------------------------------------------------------

(defn- state-dir []
  (let [d (.join node-path (.getPath app "userData") "dropbox-sync")]
    (fs/mkdirSync d #js {:recursive true})
    d))

(defn- state-path [graph-path]
  (.join node-path (state-dir)
         (str (-> (.createHash crypto "sha1") (.update graph-path) (.digest "hex")) ".json")))

(defn- read-state [graph-path]
  (try (bean/->clj (js/JSON.parse (.readFileSync fs (state-path graph-path) "utf8")))
       (catch :default _ nil)))

(defn- write-state! [graph-path st]
  (.writeFileSync fs (state-path graph-path) (js/JSON.stringify (bean/->js st) nil 2)))

(defn synced? [graph-path] (some? (read-state graph-path)))

;; ---------------------------------------------------------------------------
;; local file walking + hashing
;; ---------------------------------------------------------------------------

(defn- excluded? [rel]
  (let [segs (string/split rel #"/")]
    (or (some excluded-dirs segs)
        (some #(string/starts-with? rel (str % "/")) excluded-rel)
        (string/starts-with? (last segs) ".")
        (= (last segs) ".DS_Store"))))

(defn- walk-files
  "Every non-excluded file under `root`, as repo-relative POSIX paths."
  [root]
  (let [out (volatile! [])]
    (letfn [(go [dir]
              (doseq [ent (fs/readdirSync dir #js {:withFileTypes true})]
                (let [abs (.join node-path dir (.-name ent))
                      rel (-> (node-path/relative root abs) (string/replace "\\" "/"))]
                  (cond
                    (excluded? rel) nil
                    (.isDirectory ent) (go abs)
                    (.isFile ent) (vswap! out conj rel)))))]
      (go root))
    @out))

(defn- dropbox-content-hash
  "Dropbox's content hash: sha256 of the concatenated sha256s of each 4 MiB block."
  [^js buf]
  (let [block (* 4 1024 1024)
        acc (.createHash crypto "sha256")]
    (loop [off 0]
      (when (< off (.-length buf))
        (let [end (min (.-length buf) (+ off block))
              h (-> (.createHash crypto "sha256")
                    (.update (.subarray buf off end))
                    (.digest))]
          (.update acc h)
          (recur end))))
    (.digest acc "hex")))

(defn- local-hash [abs]
  (try (dropbox-content-hash (.readFileSync fs abs)) (catch :default _ nil)))

(defn- remote-path [graph-path rel]
  (str (:remote (read-state graph-path)) "/" rel))

(defn- abs-path [graph-path rel] (.join node-path graph-path rel))

;; ---------------------------------------------------------------------------
;; push  (local -> remote)
;; ---------------------------------------------------------------------------

(defn- push-file! [graph-path rel]
  (p/let [st (read-state graph-path)
          abs (abs-path graph-path rel)
          exists? (fs/existsSync abs)]
    (cond
      (not exists?)
      (when (get-in st [:files rel])
        (p/do! (dbx/delete! (remote-path graph-path rel))
              (write-state! graph-path (update st :files dissoc rel))
              (log (str "deleted remote " rel))))

      :else
      (p/let [^js buf (.readFileSync fs abs)
              hash (dropbox-content-hash buf)
              recorded (get-in st [:files rel])]
        (when (and (or (nil? recorded) (not= (:hash recorded) hash))
                   (not (own-echo? abs hash)))          ; don't push a pull's own write
          (if (> (.-length buf) max-file-bytes)
            (log (str "skipping " rel " — larger than the sync limit"))
            (p/let [rpath (remote-path graph-path rel)
                    meta (-> (dbx/upload rpath buf (or (:rev recorded) :add))
                             (p/catch
                              (fn [e]
                                (if-not (re-find #"conflict" (str (aget e "dropbox")))
                                  (throw e)
                                  ;; the known rev is stale. Check what's actually there:
                                  (p/let [rm (dbx/get-metadata rpath)]
                                    (cond
                                      ;; remote already holds our bytes — our own earlier
                                      ;; push; nothing to do but record the current rev
                                      (= (:content_hash rm) hash) rm
                                      ;; genuine divergence — local wins (auto policy),
                                      ;; overwrite; Dropbox keeps the old version
                                      :else (p/do!
                                             (log (str "push conflict — local " rel " overwrote the remote"))
                                             (dbx/upload rpath buf :overwrite))))))))]
              (write-state! graph-path
                            (assoc-in (read-state graph-path) [:files rel]
                                      {:rev (:rev meta) :hash (or (:content_hash meta) hash)}))
              (log (str "pushed " rel)))))))))

(defn- flush-pending! [graph-path]
  (let [pending (get-in @*runtime [graph-path :pending] #{})]
    (swap! *runtime assoc-in [graph-path :pending] #{})
    (when (seq pending)
      (with-lock graph-path
        (fn []
          (-> (p/run! #(push-file! graph-path %) (vec pending))
              (p/catch (fn [e] (log-error (str "push failed: " e))))))))))

(defn- schedule-push! [graph-path rel]
  (swap! *runtime update-in [graph-path :pending] (fnil conj #{}) rel)
  (when-let [t (get-in @*runtime [graph-path :timer])] (js/clearTimeout t))
  (swap! *runtime assoc-in [graph-path :timer]
         (js/setTimeout #(flush-pending! graph-path) debounce-ms)))

;; ---------------------------------------------------------------------------
;; pull  (remote -> local)
;; ---------------------------------------------------------------------------

(defn- conflict-name [rel stamp]
  (let [ext (node-path/extname rel)
        stem (subs rel 0 (- (count rel) (count ext)))]
    (str stem " (Dropbox " stamp ")" ext)))

(defn- apply-entry! [graph-path st entry]
  (let [tag (get entry (keyword ".tag"))
        rel (-> (:path_display entry)
                (string/replace-first (re-pattern (str "(?i)^" (string/replace (:remote st) "/" "\\/") "/")) "")
                (string/replace "\\" "/"))]
    (cond
      (string/blank? rel) (p/resolved st)

      (= tag "deleted")
      (let [abs (abs-path graph-path rel)
            recorded (get-in st [:files rel])]
        (if (and (fs/existsSync abs) recorded
                 (not= (:hash recorded) (local-hash abs)))
          (do (log (str "kept locally-modified " rel " (deleted upstream)"))
              (p/resolved st))
          (do (when (fs/existsSync abs) (fs/rmSync abs))
              (p/resolved (update st :files dissoc rel)))))

      (= tag "file")
      (let [recorded (get-in st [:files rel])
            abs (abs-path graph-path rel)
            local-now (when (fs/existsSync abs) (local-hash abs))]
        (cond
          ;; already reconciled against this exact revision
          (= (:rev recorded) (:rev entry))
          (p/resolved st)

          ;; the bytes on disk already ARE this entry's content — our own push
          ;; coming back, or a race we already handled. Catch the rev up, don't
          ;; rewrite the file (that would re-trigger the watcher).
          (and local-now (= local-now (:content_hash entry)))
          (p/resolved (assoc-in st [:files rel] {:rev (:rev entry) :hash local-now}))

          :else
          (let [local-changed? (and local-now recorded (not= (:hash recorded) local-now))
                manual? (= "manual" (:conflictMode st))
                local-mtime (try (.-mtimeMs (fs/statSync abs)) (catch :default _ 0))
                remote-mtime (try (.getTime (js/Date. (or (:client_modified entry) "")))
                                  (catch :default _ 0))
                ;; auto mode, both sides changed, local is newer — keep the local
                ;; edit and let the pending push (chokidar already fired on it)
                ;; upload it. Clobbering it here with a stale remote copy is what
                ;; triggered Logseq's "file modified on disk" prompt mid-edit.
                local-wins? (and local-changed?
                                 (not manual?)
                                 (pos? local-mtime)
                                 (> local-mtime remote-mtime))]
            (cond
              local-wins?
              (do (log (str "conflict — kept local " rel " (newer)"))
                  (p/resolved st))

              :else
              (p/let [dl (dbx/download (:path_display entry))
                      dl-hash (dropbox-content-hash (:buffer dl))]
                (fs/mkdirSync (node-path/dirname abs) #js {:recursive true})
                (if (and local-changed? manual?)
                  (let [cn (conflict-name rel (subs (.toISOString (js/Date.)) 0 10))
                        cabs (abs-path graph-path cn)]
                    (note-pull-write! cabs dl-hash)
                    (fs/writeFileSync cabs (:buffer dl))
                    (log (str "conflict — wrote " cn))
                    (p/resolved st))
                  (do
                    (when local-changed?
                      (log (str "conflict — Dropbox copy of " rel " won")))
                    (note-pull-write! abs dl-hash)
                    (fs/writeFileSync abs (:buffer dl))
                    (p/resolved (assoc-in st [:files rel] {:rev (:rev dl) :hash dl-hash})))))))))

      :else (p/resolved st))))

(defn- pull-page! [graph-path]
  (p/let [st0 (read-state graph-path)
          {:keys [entries cursor has-more]} (dbx/list-folder-continue (:cursor st0))
          st1 (reduce (fn [acc entry] (p/then acc #(apply-entry! graph-path % entry)))
                      (p/resolved st0) entries)]
    (write-state! graph-path (assoc st1 :cursor cursor :lastSync (js/Date.now)))
    (when (seq entries) (log (str "pulled " (count entries) " change(s)")))
    (when has-more (pull-page! graph-path))))

(defn- pull! [graph-path]
  (with-lock graph-path #(pull-page! graph-path)))

;; ---------------------------------------------------------------------------
;; longpoll loop + local watcher
;; ---------------------------------------------------------------------------

(defn- start-longpoll! [graph-path]
  (let [stopped? (atom false)]
    (letfn [(cycle []
              (when-not @stopped?
                (-> (p/let [st (read-state graph-path)
                            {:keys [changes backoff]} (dbx/longpoll (:cursor st))]
                      (when backoff (p/delay (* 1000 backoff)))
                      (when (and changes (not @stopped?)) (pull! graph-path)))
                    (p/catch (fn [e] (log-error (str "longpoll: " e)) (p/delay 30000)))
                    (p/finally (fn [] (when-not @stopped? (cycle)))))))]
      (cycle))
    (swap! *runtime assoc-in [graph-path :loop-stop] #(reset! stopped? true))))

(defn- start-watcher! [graph-path]
  (let [w (.watch chokidar graph-path
                  #js {:ignored (fn [pth]
                                  (let [rel (-> (node-path/relative graph-path pth)
                                                (string/replace "\\" "/"))]
                                    (and (seq rel) (excluded? rel))))
                       :ignoreInitial true
                       :awaitWriteFinish true
                       :persistent true})
        on (fn [pth]
             (let [rel (-> (node-path/relative graph-path pth) (string/replace "\\" "/"))
                   abs (abs-path graph-path rel)]
               (when (and (seq rel) (not (excluded? rel)))
                 (if (and (fs/existsSync abs) (own-echo? abs (local-hash abs)))
                   ;; this is a file a pull just wrote — consume the marker, don't push
                   (swap! *pull-writes dissoc abs)
                   (schedule-push! graph-path rel)))))]
    (doto w
      (.on "add" on) (.on "change" on) (.on "unlink" on))
    (swap! *runtime assoc-in [graph-path :watcher] w)))

;; ---------------------------------------------------------------------------
;; public: enable / disable / status / sync-now
;; ---------------------------------------------------------------------------

(defn- graph-remote-name [graph-path]
  (str "/" (-> (node-path/basename graph-path)
               (string/replace #"[^\w.\- ]" "_")
               (string/trim))))

(defn- collect-remote
  "Full recursive listing under `remote` as { rel -> entry }, following cursors."
  [remote]
  (p/let [first-page (dbx/list-folder remote)]
    (p/loop [page first-page acc {}]
      (let [acc' (reduce (fn [m e]
                           (if (= "file" (get e (keyword ".tag")))
                             (assoc m (-> (:path_display e)
                                          (string/replace-first (str remote "/") "")
                                          (string/replace "\\" "/"))
                                    e)
                             m))
                         acc (:entries page))]
        (if (:has-more page)
          (p/let [next-page (dbx/list-folder-continue (:cursor page))]
            (p/recur next-page acc'))
          {:files acc' :cursor (:cursor page)})))))

(defn- do-enable!
  [graph-path {:keys [conflict-mode] :or {conflict-mode "auto"}}]
  (if (synced? graph-path)
    (p/resolved {:synced true})
    (p/let [remote (graph-remote-name graph-path)
            _ (dbx/ensure-folder! remote)
            {remote-files :files} (collect-remote remote)
            _ (write-state! graph-path {:graphPath graph-path
                                        :remote remote
                                        :conflictMode conflict-mode
                                        :files {}
                                        :enabledAt (js/Date.now)})
            locals (walk-files graph-path)
            ;; local files: upload if remote is missing/different, else just record
            _ (p/run! (fn [rel]
                        (p/let [abs (abs-path graph-path rel)
                                ^js buf (.readFileSync fs abs)
                                hash (dropbox-content-hash buf)
                                rentry (get remote-files rel)]
                          (cond
                            (> (.-length buf) max-file-bytes) (log (str "skipping large file " rel))
                            (and rentry (= (:content_hash rentry) hash))
                            (write-state! graph-path (assoc-in (read-state graph-path) [:files rel]
                                                               {:rev (:rev rentry) :hash hash}))
                            :else
                            (p/let [meta (dbx/upload (str remote "/" rel) buf (if rentry (:rev rentry) :add))]
                              (write-state! graph-path (assoc-in (read-state graph-path) [:files rel]
                                                                 {:rev (:rev meta) :hash (:content_hash meta)}))))))
                      locals)
            ;; remote-only files: pull them down
            local-set (set locals)
            _ (p/run! (fn [[rel entry]]
                        (when-not (contains? local-set rel)
                          (p/let [dl (dbx/download (:path_display entry))
                                  abs (abs-path graph-path rel)]
                            (fs/mkdirSync (node-path/dirname abs) #js {:recursive true})
                            (fs/writeFileSync abs (:buffer dl))
                            (write-state! graph-path (assoc-in (read-state graph-path) [:files rel]
                                                               {:rev (:rev dl)
                                                                :hash (dropbox-content-hash (:buffer dl))})))))
                      remote-files)
            {:keys [cursor]} (dbx/list-folder remote)]
      (write-state! graph-path (assoc (read-state graph-path) :cursor cursor :lastSync (js/Date.now)))
      (start-watcher! graph-path)
      (start-longpoll! graph-path)
      (log (str "sync enabled: " graph-path " -> " remote " (" (count locals) " local, "
                (count remote-files) " remote)"))
      {:synced true :remote remote :files (count (:files (read-state graph-path)))})))

(defn enable!
  "Turn on sync for `graph-path`. Ensures /<name> in the app folder, reconciles
   local ↔ remote by content hash, records a cursor, starts the watcher +
   longpoll. `conflict-mode` ∈ auto|manual. A failure is logged and any
   partially-written state is torn down so a retry starts clean."
  [graph-path opts]
  (log (str "enable! " graph-path " -> " (graph-remote-name graph-path)))
  (-> (do-enable! graph-path opts)
      (p/catch (fn [e]
                 (log-error (str "enable! failed for " graph-path
                                 " (remote " (graph-remote-name graph-path) "): " (.-message e)
                                 (when-let [d (aget e "dropbox")] (str " — " d))))
                 (try (fs/rmSync (state-path graph-path)) (catch :default _ nil))
                 (swap! *runtime dissoc graph-path)
                 (throw e)))))

(defn disable! [graph-path]
  (when-let [stop (get-in @*runtime [graph-path :loop-stop])] (stop))
  (when-let [^js w (get-in @*runtime [graph-path :watcher])] (.close w))
  (when-let [t (get-in @*runtime [graph-path :timer])] (js/clearTimeout t))
  (swap! *runtime dissoc graph-path)
  (try (fs/rmSync (state-path graph-path)) (catch :default _ nil))
  {:synced false})

(defn sync-now! [graph-path]
  (if-not (synced? graph-path)
    (p/resolved {:synced false})
    (p/do! (flush-pending! graph-path)
          (pull! graph-path)
          {:synced true :lastSync (:lastSync (read-state graph-path))})))

(defn status [graph-path]
  (if-let [st (read-state graph-path)]
    {:synced true
     :remote (:remote st)
     :conflictMode (:conflictMode st)
     :files (count (:files st))
     :lastSync (:lastSync st)
     :running (contains? @*runtime graph-path)}
    {:synced false}))

(defn resume-all!
  "On launch: re-attach the watcher + longpoll for every graph that had sync on."
  []
  (doseq [f (try (fs/readdirSync (state-dir)) (catch :default _ []))
          :when (string/ends-with? f ".json")]
    (when-let [gp (:graphPath (try (bean/->clj (js/JSON.parse (.readFileSync fs (.join node-path (state-dir) f) "utf8")))
                                   (catch :default _ nil)))]
      (when (and (fs/existsSync gp) (not (contains? @*runtime gp)))
        (start-watcher! gp)
        (start-longpoll! gp)
        (-> (sync-now! gp) (p/catch (fn [e] (log-error (str "resume sync-now for " gp ": " e)))))
        (log (str "resumed sync for " gp))))))
