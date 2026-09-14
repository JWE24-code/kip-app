(ns frontend.handler.sidecar
  "The renderer's persistent WebSocket client to the Kip sidecar
  (kip-app#147). Main process spawns the sidecar once per coop and hands over
  its discovery info via the :sidecarInfo IPC (see electron.sidecar); this ns
  opens the socket, completes the hello/ready handshake, and speaks the
  versioned envelope for the rest of the session.

  One connection per app session (per open coop). Turn events stream in as
  `turn.start` → `turn.delta`* → `agent.tool.start|end`*
  → (`ask_user` → `chat.respond`)? → `turn.usage` → `turn.end`, plus
  `skill.progress` and `error`. Callers subscribe with `add-listener!` and
  react to the event maps; `send-chat!`/`cancel!`/`respond!` write frames.

  This replaces the spawn-per-action `:wikiChat` + `.roost/peck-progress.json`
  polling path. Frames are tiny JSON objects; the token is the sidecar's own
  bearer secret, never logged."
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.state :as state]
            [frontend.util :as util]
            [promesa.core :as p]))

;; Must match sidecar/server/protocol.ts PROTOCOL_VERSION.
(def protocol-version 1)

(defn make-envelope
  "The wire envelope: {v id type ts payload}. `id` is a fresh correlation id."
  [type payload]
  {:v protocol-version
   :id (str (random-uuid))
   :type type
   :ts (js/Date.now)
   :payload (or payload {})})

(defn encode
  [envelope]
  (js/JSON.stringify (clj->js envelope)))

(defn decode
  "Parse a frame off the socket into a CLJS map (keywordized); nil when it
  isn't valid JSON."
  [raw]
  (try
    (js->clj (js/JSON.parse raw) :keywordize-keys true)
    (catch :default _ nil)))

(declare send-frame! ensure-connected-for!)

(defonce *conn
  (atom {:status :idle         ; :idle | :connecting | :connected | :closed | :unavailable
         :repo nil
         :url nil
         :session-id nil
         :turn-id nil
         :ws nil
         :ready-deferred nil}))

(defonce ^:private *listeners (atom #{}))

(defn add-listener!
  "Register `f` (called with each event map) for sidecar events. Returns an
  unsubscribe fn."
  [f]
  (swap! *listeners conj f)
  #(swap! *listeners disj f))

(defn- emit!
  [event]
  (doseq [f @*listeners]
    (try (f event)
         (catch :default e (js/console.error "sidecar listener failed" e)))))

(defn- vault-root
  []
  (config/get-repo-dir (state/get-current-repo)))

(defn- open? [ws]
  (and ws (= 1 (.-readyState ws))))

;; --- socket -> events -------------------------------------------------------

(defn- note-turn! [type payload]
  (cond
    (= type :turn.start) (swap! *conn assoc :turn-id (:turnId payload))
    (contains? #{:turn.end :turn.error} type) (swap! *conn assoc :turn-id nil)))

(defn- handle-frame! [^js ev]
  (when-let [env (decode (.-data ev))]
    (let [type (keyword (:type env))
          payload (:payload env)]
      (when (= type :ready)
        (swap! *conn assoc :status :connected
               :session-id (:sessionId payload))
        (when-let [d (:ready-deferred @*conn)]
          (p/resolve! d @*conn)
          (swap! *conn assoc :ready-deferred nil)))
      (note-turn! type payload)
      (emit! (assoc env :type type :payload payload)))))

(defn- handle-close! [repo]
  (swap! *conn assoc :ws nil :status :closed :turn-id nil)
  (emit! {:type :sidecar/closed})
  ;; Eagerly reconnect so a streamed turn resumes after a transient drop; the
  ;; sidecar's loopback socket is cheap and local.
  (js/setTimeout (fn []
                   (when (= repo (:repo @*conn))
                     (ensure-connected-for! repo)))
                 1000))

(defn- open-socket!
  [repo info]
  (let [url (:url info)
        token (:token info)]
    (swap! *conn assoc :status :connecting :repo repo :url url :session-id nil)
    (let [ws (js/WebSocket. url)]
      (swap! *conn assoc :ws ws)
      (set! (.-onopen ws) (fn [_] (send-frame! "hello" {:token token})))
      (set! (.-onmessage ws) handle-frame!)
      (set! (.-onerror ws) (fn [e] (js/console.error "sidecar socket error" e)))
      (set! (.-onclose ws) (fn [_] (handle-close! repo)))
      ws)))

(defn- send-frame!
  ([type] (send-frame! type {}))
  ([type payload]
   (let [ws (:ws @*conn)]
     (if (open? ws)
       (do (.send ws (encode (make-envelope type (or payload {})))) true)
       false))))

(defn- connect-repo!
  "Ask main for the current coop's sidecar discovery info and open a socket.
  Resolves once the hello/ready handshake completes."
  [repo]
  (let [vr (vault-root)
        d (p/deferred)]
    (swap! *conn assoc :status :connecting :ready-deferred d :repo repo)
    (-> (ipc/ipc "sidecarInfo" vr)
        (p/then (fn [info-js]
                  (let [info (bean/->clj info-js)]
                    (if (:url info)
                      (open-socket! repo info)
                      (do (swap! *conn assoc :status :unavailable)
                          (p/reject! d (js/Error. "Kip's sidecar isn't available.")))))))
        (p/catch (fn [e]
                   (swap! *conn assoc :status :unavailable :ws nil)
                   (p/reject! d e))))
    d))

(defn ensure-connected-for!
  [target-repo]
  (let [{:keys [status repo] :as conn} @*conn]
    (cond
      (not (util/electron?)) (p/rejected (js/Error. "The sidecar runs only in the desktop app."))
      (and (= status :connected) (= repo target-repo) (open? (:ws conn))) (p/resolved conn)
      (and (= status :connecting) (= repo target-repo) (:ready-deferred conn)) (:ready-deferred conn)
      :else (connect-repo! target-repo))))

(defn ensure-connected!
  []
  (ensure-connected-for! (state/get-current-repo)))

(defn status [] (:status @*conn))
(defn current-turn-id [] (:turn-id @*conn))

(defn send-chat!
  "Start a turn for `text`; resolves true once the frame is on the wire. Turn
  output arrives asynchronously via `add-listener!`."
  [text]
  (if (string/blank? text)
    (p/rejected (js/Error. "empty turn"))
    (-> (ensure-connected!)
        (p/then (fn [_]
                  (when-not (send-frame! "chat.send" {:text text})
                    (throw (js/Error. "sidecar socket is not open"))))))))

(defn cancel!
  "Ask the sidecar to abort the in-flight turn. `turn-id` is optional — the
  server cancels the active turn when it's omitted."
  ([] (cancel! nil))
  ([turn-id]
   (send-frame! "chat.cancel" (cond-> {} turn-id (assoc :turnId turn-id)))))

(defn respond!
  "Answer a pending `ask_user` (suspension) by its tool-call id."
  [call-id answer]
  (send-frame! "chat.respond" {:toolCallId call-id :value (str answer)}))

(defn disconnect!
  []
  (when-let [ws (:ws @*conn)]
    (try (set! (.-onclose ws) nil)
         (.close ws)
         (catch :default _ nil)))
  (swap! *conn assoc :ws nil :status :idle :turn-id nil :ready-deferred nil))
