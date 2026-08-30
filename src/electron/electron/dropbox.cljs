(ns electron.dropbox
  "Dropbox account connection for graph sync (kip-app v0.4.0).

  OAuth 2 with PKCE and a loopback redirect — no client secret ships in the
  app (a distributed desktop app can't keep one). The flow:

    1. make a code_verifier + code_challenge (S256)
    2. start a one-shot http server on 127.0.0.1:<random port>
    3. open the system browser at Dropbox's /oauth2/authorize
    4. Dropbox redirects to http://localhost:<port>/?code=… — the server
       grabs the code and shows a `close this tab` page
    5. exchange code + verifier for { access_token, refresh_token } at
       /oauth2/token, asking for `offline` access so we get a refresh token

  The refresh token is the long-lived secret; it's kept in configs.edn,
  encrypted with Electron's safeStorage (OS keychain) when that's available,
  plaintext otherwise (same posture as the LLM keys in .henhouse/llm.json).
  The short-lived access token lives only in memory.

  App key: Kip ships its own (`default-app-key`). A DIY user can override it
  with their own Dropbox app — KIP_DROPBOX_APP_KEY or the advanced setting —
  e.g. to get their own rate-limit quota. Planned: a *managed* mode where a
  Kip-subscription account brokers the OAuth through api.kip-ai.be so the
  user needs no Dropbox app of their own (backend endpoints TBD).

  This ns is *connection only*; the sync engine is electron.dropbox-sync."
  (:require ["crypto" :as crypto]
            ["http" :as http]
            ["electron" :refer [shell safeStorage]]
            [cljs-bean.core :as bean]
            [clojure.string :as string]
            [electron.configs :as cfgs]
            [electron.logger :as logger]
            [promesa.core :as p]))

;; Kip's own Dropbox PKCE public-client id — embedded in every copy of the app
;; by design, not a secret (a PKCE public client has no client secret). One
;; value for every install; Dropbox rate-limits per *app*, so a DIY user who
;; wants their own quota (or their own Dropbox app for any reason) can override
;; it — KIP_DROPBOX_APP_KEY, or :dropbox/config {:app-key "…"} in configs.edn
;; (Settings → General → Dropbox → advanced). Whatever app is used must have
;; `http://localhost` registered as an OAuth redirect URI.
(def ^:private default-app-key "oqoouohdvzvbdia")

(defn app-key []
  (or (not-empty (aget (.-env js/process) "KIP_DROPBOX_APP_KEY"))
      (not-empty (:app-key (cfgs/get-item :dropbox/config)))
      default-app-key))

(defn using-custom-app-key? [] (not= (app-key) default-app-key))

(defn set-app-key!
  "Persist a DIY Dropbox app key (or clear it with nil/blank). Disconnects
   first — tokens from one app aren't valid for another."
  [k]
  (let [k (some-> k str string/trim not-empty)]
    (cfgs/set-item! :dropbox/config (if k {:app-key k} nil))))

(def ^:private authorize-url "https://www.dropbox.com/oauth2/authorize")
(def ^:private token-url "https://api.dropboxapi.com/oauth2/token")
(def ^:private account-url "https://api.dropboxapi.com/2/users/get_current_account")

;; Request the scopes explicitly so the consent is legible and doesn't silently
;; depend on which boxes are ticked on the Dropbox app's Permissions tab. The
;; app (Kip's or a DIY one) must have at least these enabled there.
(def ^:private oauth-scope
  "account_info.read files.metadata.read files.content.read files.content.write")

(def ^:private log-error (partial logger/error "[Dropbox]"))

;; { :access-token str :expires-at ms } — memory only, never persisted
(defonce ^:private *access (atom nil))

;; ---------------------------------------------------------------------------
;; refresh-token storage (configs.edn, encrypted when possible)
;; ---------------------------------------------------------------------------

(defn- encryption-available? []
  (try (.isEncryptionAvailable safeStorage) (catch :default _ false)))

(defn- protect [^string s]
  (if (encryption-available?)
    {:enc (.toString (.encryptString safeStorage s) "base64")}
    {:plain s}))

(defn- unprotect [{:keys [enc plain]}]
  (cond
    plain plain
    enc (try (.decryptString safeStorage (js/Buffer.from enc "base64"))
             (catch :default e (log-error (str "decrypt failed: " e)) nil))
    :else nil))

(defn- load-auth [] (cfgs/get-item :dropbox/auth))

(defn- save-auth! [{:keys [refresh-token account-id]}]
  (cfgs/set-item! :dropbox/auth
                  {:refresh (protect refresh-token)
                   :account-id account-id
                   :connected-at (js/Date.now)}))

(defn connected? [] (some? (load-auth)))

(defn- refresh-token [] (some-> (load-auth) :refresh unprotect))

;; ---------------------------------------------------------------------------
;; PKCE + the loopback authorize flow
;; ---------------------------------------------------------------------------

(defn- b64url [^js buf]
  (-> (.toString buf "base64")
      (string/replace "+" "-") (string/replace "/" "_") (string/replace #"=+$" "")))

(defn- make-pkce []
  (let [verifier (b64url (.randomBytes crypto 64))
        challenge (b64url (-> (.createHash crypto "sha256") (.update verifier) (.digest)))]
    {:verifier verifier :challenge challenge}))

(defn- form-encode [m]
  (->> m
       (map (fn [[k v]] (str (js/encodeURIComponent (name k)) "=" (js/encodeURIComponent (str v)))))
       (string/join "&")))

(def ^:private done-page
  (str "<!doctype html><meta charset=utf-8><title>Kip</title>"
       "<body style=\"font:15px/1.5 system-ui;margin:16vh auto;max-width:22rem;text-align:center;color:#333\">"
       "<h2 style=\"font-weight:600\">Dropbox connected</h2>"
       "<p>You can close this tab and return to Kip.</p></body>"))

(defn- token-request [params]
  (p/let [^js r (js/fetch token-url
                          #js {:method "POST"
                               :headers #js {"Content-Type" "application/x-www-form-urlencoded"}
                               :body (form-encode (merge {:client_id (app-key)} params))})
          j (.json r)]
    (if (.-ok r)
      j
      (let [m (bean/->clj j)]
        (throw (js/Error. (str "Dropbox token exchange failed (" (.-status r) "): "
                               (or (:error_description m) (:error m) ""))))))))

(defn- fetch-account [access-token]
  (-> (js/fetch account-url
                #js {:method "POST"
                     :headers #js {"Authorization" (str "Bearer " access-token)
                                   "Content-Type" "application/json"}
                     :body "null"})
      (p/then (fn [^js r] (when (.-ok r) (.json r))))
      (p/then (fn [j]
                (when j
                  (let [m (bean/->clj j)]
                    {:name (get-in m [:name :display_name])
                     :email (:email m)}))))
      (p/catch (fn [_] nil))))

(defn- start-loopback
  "Resolves { :redirect-uri, :code } — the code arrives on the first request
   the loopback server sees carrying ?code (or it rejects on ?error/timeout).
   Tracks settlement with a plain flag: promesa 4.0.2 has no `p/pending?` in
   cljs (its IState protocol is JVM-only), and the timer must be cleared on
   success so it doesn't fire 5 minutes later."
  []
  (let [ready (p/deferred)
        code-d (p/deferred)
        settled (volatile! false)
        timer (volatile! nil)
        srv (http/createServer)
        finish (fn [f] (when-not @settled
                         (vreset! settled true)
                         (some-> @timer js/clearTimeout)
                         (f)))]
    (.on srv "request"
         (fn [^js req ^js res]
           (let [u (js/URL. (.-url req) "http://localhost")
                 code (.. u -searchParams (get "code"))
                 err (.. u -searchParams (get "error"))]
             (.end (doto res (.writeHead 200 #js {"Content-Type" "text/html"})) done-page)
             (finish #(if code
                        (p/resolve! code-d code)
                        (p/reject! code-d (js/Error. (str "Dropbox authorization "
                                                         (if err (str "was declined (" err ")") "returned no code") ".")))))
             (js/setImmediate #(.close srv)))))
    (.on srv "error" (fn [e] (p/reject! ready e) (finish #(p/reject! code-d e))))
    (.listen srv 0 "127.0.0.1"
             (fn [] (p/resolve! ready (str "http://localhost:" (.. srv address -port)))))
    (vreset! timer
             (js/setTimeout
              (fn [] (finish (fn []
                               (try (.close srv) (catch :default _ nil))
                               (p/reject! code-d (js/Error. "Timed out waiting for Dropbox authorization.")))))
              300000))
    (p/let [redirect-uri ready]
      {:redirect-uri redirect-uri :code code-d})))

(defn connect!
  "Runs the full browser OAuth dance. Resolves { :connected true :account {…} }
   or rejects with a readable error."
  []
  (let [{:keys [verifier challenge]} (make-pkce)]
    (p/let [{:keys [redirect-uri code]} (start-loopback)
            _ (.openExternal shell
                             (str authorize-url "?"
                                  (form-encode {:client_id (app-key)
                                                :response_type "code"
                                                :code_challenge challenge
                                                :code_challenge_method "S256"
                                                :token_access_type "offline"
                                                :scope oauth-scope
                                                :redirect_uri redirect-uri})))
            auth-code code
            tok-js (token-request {:grant_type "authorization_code"
                                   :code auth-code
                                   :code_verifier verifier
                                   :redirect_uri redirect-uri})
            tok (bean/->clj tok-js)
            account (fetch-account (:access_token tok))]
      (reset! *access {:access-token (:access_token tok)
                       :expires-at (+ (js/Date.now) (* 1000 (or (:expires_in tok) 14400)))})
      (save-auth! {:refresh-token (:refresh_token tok)
                   :account-id (:account_id tok)})
      (logger/info "[Dropbox]" (str "connected" (when account (str " as " (:email account)))))
      {:connected true :account account})))

(defn disconnect! []
  (reset! *access nil)
  (cfgs/set-item! :dropbox/auth nil)
  {:connected false})

(defn set-app-key-and-disconnect!
  "Swap the DIY Dropbox app key and drop any existing connection (tokens from
   one app don't work for another). Pass nil/blank to go back to Kip's key."
  [k]
  (set-app-key! k)
  (disconnect!)
  {:connected false :customAppKey (using-custom-app-key?)})

(defn access-token
  "Resolves a currently-valid access token, refreshing via the stored refresh
   token when needed. Rejects if not connected."
  []
  (let [{:keys [access-token expires-at]} @*access]
    (if (and access-token (> expires-at (+ (js/Date.now) 60000)))
      (p/resolved access-token)
      (if-let [rt (refresh-token)]
        (p/let [tok-js (token-request {:grant_type "refresh_token" :refresh_token rt})
                tok (bean/->clj tok-js)]
          (reset! *access {:access-token (:access_token tok)
                           :expires-at (+ (js/Date.now) (* 1000 (or (:expires_in tok) 14400)))})
          (:access_token tok))
        (p/rejected (js/Error. "Not connected to Dropbox."))))))

(defn status
  "{ :connected bool, :account {:name :email} | nil, :encrypted bool,
     :customAppKey bool }"
  []
  (let [base {:customAppKey (using-custom-app-key?)}]
    (if-not (connected?)
      (p/resolved (assoc base :connected false))
      (-> (access-token)
          (p/then fetch-account)
          (p/then (fn [account] (assoc base :connected true
                                       :account account
                                       :encrypted (encryption-available?))))
          (p/catch (fn [e]
                     (assoc base :connected true :account nil
                            :error (str "Dropbox connection needs re-authorising: " (.-message e)))))))))

;; ---------------------------------------------------------------------------
;; Files API — the low-level calls the sync engine builds on
;; (https://www.dropbox.com/developers/documentation/http/documentation)
;; ---------------------------------------------------------------------------

(def ^:private api-base "https://api.dropboxapi.com/2/")
(def ^:private content-base "https://content.dropboxapi.com/2/")

(defn- api-error [status url body]
  (let [m (try (bean/->clj (js/JSON.parse body)) (catch :default _ nil))
        ;; JSON errors carry error_summary; request-level 400s are plain text
        detail (or (:error_summary m)
                   (some-> body string/trim not-empty (subs 0 (min 300 (count (string/trim body))))))
        endpoint (last (string/split (str url) #"/"))]
    (doto (js/Error. (str "Dropbox API " status " on " endpoint (when detail (str ": " detail))))
      (aset "status" status)
      (aset "dropbox" (or detail body)))))

(defn rpc
  "POST {api-base}<endpoint> with a JSON body, JSON back. `arg` is a CLJS map."
  [endpoint arg]
  (p/let [token (access-token)
          ^js res (js/fetch (str api-base endpoint)
                            #js {:method "POST"
                                 :headers #js {"Authorization" (str "Bearer " token)
                                               "Content-Type" "application/json"}
                                 :body (js/JSON.stringify (bean/->js arg))})
          text (.text res)]
    (if (.-ok res)
      (if (seq text) (bean/->clj (js/JSON.parse text)) {})
      (throw (api-error (.-status res) (.-url res) text)))))

(defn- read-download [^js res]
  (if (.-ok res)
    (p/let [ab (.arrayBuffer res)
            hdr (.. res -headers (get "dropbox-api-result"))
            m (bean/->clj (js/JSON.parse hdr))]
      {:buffer (js/Buffer.from ab) :rev (:rev m) :size (:size m)})
    (-> (.text res)
        (p/then (fn [t] (throw (api-error (.-status res) (.-url res) t)))))))

(defn download
  "GET a file's bytes. Resolves { :buffer <Buffer> :rev str :size n }."
  [remote-path]
  (p/let [token (access-token)
          res (js/fetch (str content-base "files/download")
                        #js {:method "POST"
                             :headers #js {"Authorization" (str "Bearer " token)
                                           "Dropbox-API-Arg" (js/JSON.stringify #js {:path remote-path})}})]
    (read-download res)))

(defn get-metadata
  "Current { :rev :content_hash :size … } for a remote file, or nil if it's gone."
  [remote-path]
  (-> (rpc "files/get_metadata" {:path remote-path})
      (p/catch (fn [e] (if (re-find #"not_found" (str (aget e "dropbox"))) nil (throw e))))))

(defn upload
  "PUT bytes to `remote-path`. `mode` is :add, :overwrite, or a rev string
   (update). Resolves the new metadata { :rev :size :content_hash … }."
  [remote-path ^js buffer mode]
  (p/let [token (access-token)
          arg {:path remote-path
               :mode (cond
                       (string? mode) {".tag" "update" :update mode}
                       (= mode :overwrite) {".tag" "overwrite"}
                       :else {".tag" "add"})
               :autorename false
               :mute true}
          ^js res (js/fetch (str content-base "files/upload")
                            #js {:method "POST"
                                 :headers #js {"Authorization" (str "Bearer " token)
                                               "Content-Type" "application/octet-stream"
                                               "Dropbox-API-Arg" (js/JSON.stringify (bean/->js arg))}
                                 :body buffer})
          text (.text res)]
    (if (.-ok res)
      (bean/->clj (js/JSON.parse text))
      (throw (api-error (.-status res) (.-url res) text)))))

(defn delete! [remote-path]
  (-> (rpc "files/delete_v2" {:path remote-path})
      (p/catch (fn [e] (when-not (re-find #"not_found" (str (aget e "dropbox"))) (throw e))))))

(defn ensure-folder! [remote-path]
  (-> (rpc "files/create_folder_v2" {:path remote-path})
      (p/catch (fn [e] (when-not (re-find #"path/conflict" (str (aget e "dropbox"))) (throw e))))))

(defn list-folder
  "First page of a recursive listing under `remote-path` (\"\" = app root).
   Resolves { :entries [ … ] :cursor str :has-more bool }."
  [remote-path]
  (p/let [r (rpc "files/list_folder" {:path remote-path :recursive true})]
    {:entries (:entries r) :cursor (:cursor r) :has-more (:has_more r)}))

(defn list-folder-continue [cursor]
  (p/let [r (rpc "files/list_folder/continue" {:cursor cursor})]
    {:entries (:entries r) :cursor (:cursor r) :has-more (:has_more r)}))

(defn longpoll
  "Blocks (up to `timeout` s, default 300) until the folder behind `cursor`
   changes. Unauthenticated endpoint. Resolves { :changes bool :backoff n }."
  ([cursor] (longpoll cursor 300))
  ([cursor timeout]
   (p/let [^js res (js/fetch "https://notify.dropboxapi.com/2/files/list_folder/longpoll"
                             #js {:method "POST"
                                  :headers #js {"Content-Type" "application/json"}
                                  :body (js/JSON.stringify #js {:cursor cursor :timeout timeout})})
           text (.text res)]
     (if (.-ok res)
       (let [m (bean/->clj (js/JSON.parse text))]
         {:changes (:changes m) :backoff (:backoff m)})
       (throw (api-error (.-status res) (.-url res) text))))))
