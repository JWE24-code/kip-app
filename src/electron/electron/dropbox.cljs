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

  This ns is *connection only* — the sync engine that uses the token is
  separate (electron.dropbox-sync, TODO)."
  (:require ["crypto" :as crypto]
            ["http" :as http]
            ["electron" :refer [shell safeStorage]]
            [cljs-bean.core :as bean]
            [clojure.string :as string]
            [electron.configs :as cfgs]
            [electron.logger :as logger]
            [promesa.core :as p]))

;; A PKCE public-client id — embedded in every copy of the app by design,
;; not a secret. Register `http://localhost` under the app's redirect URIs
;; in the Dropbox App Console (Dropbox ignores the port for localhost).
(def ^:private app-key "oqoouohdvzvbdia")

(def ^:private authorize-url "https://www.dropbox.com/oauth2/authorize")
(def ^:private token-url "https://api.dropboxapi.com/oauth2/token")
(def ^:private account-url "https://api.dropboxapi.com/2/users/get_current_account")

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
                               :body (form-encode (merge {:client_id app-key} params))})
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
   the loopback server sees carrying ?code (or it rejects on ?error/timeout)."
  []
  (let [ready (p/deferred)
        code-d (p/deferred)
        srv (http/createServer)]
    (.on srv "request"
         (fn [^js req ^js res]
           (let [u (js/URL. (.-url req) "http://localhost")
                 code (.. u -searchParams (get "code"))
                 err (.. u -searchParams (get "error"))]
             (.end (doto res (.writeHead 200 #js {"Content-Type" "text/html"})) done-page)
             (if code
               (p/resolve! code-d code)
               (p/reject! code-d (js/Error. (str "Dropbox authorization "
                                                (if err (str "was declined (" err ")") "returned no code") "."))))
             (js/setImmediate #(.close srv)))))
    (.on srv "error" (fn [e] (p/reject! ready e) (p/reject! code-d e)))
    (.listen srv 0 "127.0.0.1"
             (fn [] (p/resolve! ready (str "http://localhost:" (.. srv address -port)))))
    (js/setTimeout (fn [] (when (p/pending? code-d)
                            (try (.close srv) (catch :default _ nil))
                            (p/reject! code-d (js/Error. "Timed out waiting for Dropbox authorization."))))
                   300000)
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
                                  (form-encode {:client_id app-key
                                                :response_type "code"
                                                :code_challenge challenge
                                                :code_challenge_method "S256"
                                                :token_access_type "offline"
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
  "{ :connected bool, :account {:name :email} | nil, :encrypted bool }"
  []
  (if-not (connected?)
    (p/resolved {:connected false})
    (-> (access-token)
        (p/then fetch-account)
        (p/then (fn [account] {:connected true
                               :account account
                               :encrypted (encryption-available?)}))
        (p/catch (fn [e]
                   {:connected true :account nil
                    :error (str "Dropbox connection needs re-authorising: " (.-message e))})))))
