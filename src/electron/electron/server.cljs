(ns electron.server
  (:require ["fastify" :as Fastify]
            ["@fastify/cors" :as FastifyCORS]
            ["crypto" :as crypto]
            ["electron" :refer [ipcMain dialog]]
            ["fs-extra" :as fs-extra]
            ["path" :as node-path]
            [clojure.string :as string]
            [promesa.core :as p]
            [cljs-bean.core :as bean]
            [electron.utils :as utils]
            [camel-snake-kebab.core :as csk]
            [electron.logger :as logger]
            [electron.configs :as cfgs]
            [electron.state :as e-state]
            [electron.wiki :as wiki]
            [electron.window :as window]))

(defonce ^:private *win (atom nil))
(defonce ^:private *server (atom nil))

(defn get-host [] (or (cfgs/get-item :server/host) "127.0.0.1"))
(defn get-port [] (or (cfgs/get-item :server/port) 12315))

(defonce *state
  (atom nil))

(defn- reset-state!
  []
  (reset! *state {:status    nil                            ;; :running :starting :closing :closed :error
                  :error     nil
                  :host      (get-host)
                  :port      (get-port)
                  :tokens    (cfgs/get-item :server/tokens)
                  :autostart (cfgs/get-item :server/autostart)}))

(defn- set-status!
  ([status] (set-status! status nil))
  ([status error]
   (swap! *state assoc :status status :error error)))

(defn load-state-to-renderer!
  ([] (load-state-to-renderer! @*state))
  ([s]
   (doseq [^js w (window/get-all-windows)]
     (utils/send-to-renderer w :syncAPIServerState s))))

(defn set-config!
  [config]
  (when-let [config (and (map? config) (dissoc config :status))]
    (reset! *state (merge @*state config))
    (doseq [[k v] config]
      (cfgs/set-item! (keyword (str "server/" (name k))) v))
    (load-state-to-renderer!)))

(defn- setup-state-watch!
  []
  (add-watch *state ::ws #(load-state-to-renderer! %4))
  #(remove-watch *state ::ws))

(defn type-proxy-api? [s]
  (when (string? s)
    (string/starts-with? s "logseq.")))

(defn resolve-real-api-method
  [s]
  (when-not (string/blank? s)
    (if (type-proxy-api? s)
      (let [s'   (string/split s ".")
            tag  (second s')
            tag' (when (and (not (string/blank? tag))
                            (contains? #{"ui" "git" "assets"} (string/lower-case tag)))
                   (str tag "_"))]
        (csk/->snake_case (str tag' (last s'))))
      (string/trim s))))

(defn- validate-auth-token
  [token]
  (let [token (string/replace token "Bearer " "")]
    (when-let [valid-tokens (cfgs/get-item :server/tokens)]
      (when (or (string/blank? token)
                (not (some #(or (= % token)
                                (= (:value %) token)) valid-tokens)))
        (throw (js/Error. "Access Denied!"))))))

(defn- api-pre-handler!
  [^js req ^js rep callback]
  (if (contains? #{"/" "/pair"} (.-url req))
    (callback)
    (try
      (let [^js headers (.-headers req)]
        (validate-auth-token (.-authorization headers))
        (callback))
      (catch js/Error e
        (-> rep
            (.code 401)
            (.send e))))))

(defonce ^:private *cid (volatile! 0))
(defn- invoke-logseq-api!
  [method args]
  (p/create
   (fn [resolve _reject]
     (let [sid        (vswap! *cid inc)
           ret-handle (fn [^js _w ret] (resolve ret))]
       (utils/send-to-renderer @*win :invokeLogseqAPI {:syncId sid :method method :args args})
       (.handleOnce ipcMain (str ::sync! sid) ret-handle)))))

(defn- api-handler!
  [^js req ^js rep]
  (if-let [^js body (.-body req)]
    (if-let [method (resolve-real-api-method (.-method body))]
      (-> (invoke-logseq-api! method (.-args body))
          (p/then #(.send rep %))
          (p/catch #(.send rep %)))
      (-> rep
          (.code 400)
          (.send (js/Error. ":method of body is missing!"))))
    (throw (js/Error. "Body{:method :args} is required!"))))

(defn- interaction-vault-root
  "The coop an incoming interaction belongs to: the focused window's graph, or
  the single open graph. nil when there's nothing sensible to write into."
  []
  (or (e-state/get-active-window-graph-path)
      (let [graphs (e-state/get-all-graph-paths)]
        (when (= 1 (count graphs)) (first graphs)))))

(defn- interactions-handler!
  "POST /interactions { email, name, subject, direction, date } → resolve/ create
  the person page and append the interaction (kip-app#127). Auth is the server's
  normal token preHandler; the write is delegated to scripts/log-interaction.js."
  [^js req ^js rep]
  (if-let [vault-root (interaction-vault-root)]
    (-> (wiki/log-interaction! vault-root (bean/->clj (.-body req)))
        (p/then (fn [r] (.send rep (bean/->js r))))
        (p/catch (fn [e] (-> rep (.code 400) (.send (js/Error. (str e)))))))
    (-> rep (.code 400) (.send (js/Error. "No graph open — open a folder first.")))))

(defn- new-token []
  (.toString (.randomBytes crypto 24) "hex"))

(defn- pair-handler!
  "POST /pair { name } — unauthenticated one-click connection for a local client
  (the Gmail extension, the webclipper, …). Kip shows a native confirm dialog;
  on Allow it mints a token, appends it to :server/tokens, and returns
  { token host port }. Loopback-only, and the user must approve in Kip, so a
  random local process can't grab a token silently (kip-app#133)."
  [^js req ^js rep]
  (let [body (bean/->clj (.-body req))
        name (or (not-empty (:name body)) "an app")]
    (-> (.showMessageBox dialog
                         (bean/->js {:type "question"
                                     :buttons #js ["Allow" "Deny"]
                                     :defaultId 0
                                     :cancelId 1
                                     :title "Kip API server"
                                     :message (str "Allow \"" name "\" to connect to Kip's API server?")
                                     :detail "It will be able to call Kip's local HTTP API — including logging interactions to your coop."}))
        (p/then (fn [^js res]
                  (if (zero? (.-response res))
                    (let [token (new-token)
                          tokens (conj (vec (or (cfgs/get-item :server/tokens) []))
                                       {:value token :name name})]
                      (cfgs/set-item! :server/tokens tokens)
                      (swap! *state assoc :tokens tokens)
                      (.send rep (bean/->js {:token token :host (get-host) :port (get-port)})))
                    (-> rep (.code 403) (.send (js/Error. "Denied."))))))
        (p/catch (fn [e] (-> rep (.code 500) (.send (js/Error. (str e)))))))))

(defn close!
  []
  (when (and @*server (contains? #{:running :error} (:status @*state)))
    (logger/debug "[server] closing ...")
    (set-status! :closing)
    (-> (.close @*server)
        (p/then (fn []
                  (reset! *server nil)
                  (set-status! :closed)))
        (p/catch (fn [^js e]
                   (set-status! :running e))))))

(defn start!
  []
  (-> (p/let [_     (close!)
              _     (set-status! :starting)
              ^js s (Fastify. #js {:logger                (not utils/win32?)
                                   :requestTimeout        (* 1000 42)
                                   :forceCloseConnections true})
              ;; middlewares
              _     (.register s FastifyCORS #js {:origin "*"})
              ;; hooks & routes
               _     (doto s
                       (.addHook "preHandler" api-pre-handler!)
                       (.post "/api" api-handler!)
                       (.post "/interactions" interactions-handler!)
                       (.post "/pair" pair-handler!)
                       (.get "/" (fn [_ ^js rep]
                                  (let [html (fs-extra/readFileSync (.join node-path js/__dirname "./docs/api_server.html"))
                                        HOST (get-host)
                                        PORT (get-port)
                                        html (-> (str html)
                                                 (string/replace-first "${HOST}" HOST)
                                                 (string/replace-first "${PORT}" PORT))]
                                    (doto rep (.type "text/html")
                                              (.send html))))))
              ;; listen port
              _     (.listen s (bean/->js (select-keys @*state [:host :port])))]
        (reset! *server s)
        (set-status! :running))
      (p/then (fn [] (logger/debug "[server] start successfully!")))
      (p/catch (fn [^js e]
                 (set-status! :error e)
                 (logger/error "[server] start error! " e)))))

(defn do-server!
  [action]
  (case (keyword action)
    :start (when (contains? #{nil :closed :error} (:status @*state))
             (start!))
    :stop (close!)
    :restart (start!)
    :else :dune))

(defn setup!
  [^js win]
  (reset! *win win)
  (let [t (setup-state-watch!)]
    (reset-state!) t))
