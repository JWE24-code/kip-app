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
         :capabilities #{}    ; advertised by the ready payload, when the sidecar sends any
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
               :session-id (:sessionId payload)
               :capabilities (set (:capabilities payload)))
        (when-let [d (:ready-deferred @*conn)]
          (p/resolve! d @*conn)
          (swap! *conn assoc :ready-deferred nil)))
      (note-turn! type payload)
      (emit! (assoc env :type type :payload payload)))))

(defn- handle-close! [repo]
  ;; A send-chat! that is still waiting on the hello/ready handshake must not
  ;; hang: reject its deferred before tearing the connection state down.
  (when-let [d (:ready-deferred @*conn)]
    (p/reject! d (js/Error. "Lost the connection to Kip's sidecar.")))
  (swap! *conn assoc :ws nil :status :closed :turn-id nil :ready-deferred nil
         :capabilities #{})
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
    (swap! *conn assoc :status :connecting :repo repo :url url :session-id nil
           :capabilities #{})
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
      (not (util/electron?))
      (do (swap! *conn assoc :status :unavailable)
          (p/rejected (js/Error. "The sidecar runs only in the desktop app.")))
      (and (= status :connected) (= repo target-repo) (open? (:ws conn))) (p/resolved conn)
      (and (= status :connecting) (= repo target-repo) (:ready-deferred conn)) (:ready-deferred conn)
      :else (connect-repo! target-repo))))

(defn ensure-connected!
  []
  (ensure-connected-for! (state/get-current-repo)))

(defn status [] (:status @*conn))
(defn current-turn-id [] (:turn-id @*conn))

(defn unavailable?
  "True when no sidecar is reachable for this build/coop — the only case where
  the caller should fall back to the spawn-per-action :wikiChat path. A real
  turn error (closed socket mid-turn, server error) is NOT unavailable."
  []
  (= :unavailable (:status @*conn)))

(defn cancel-supported?
  "True only when the connected sidecar advertised cancel support in its ready
  payload. Kip's v1 ready frame carries no capabilities yet (chat.cancel is
  kip#69), so this is false today and the UI hides Stop instead of offering a
  control that silently does nothing."
  []
  (contains? (:capabilities @*conn) "cancel"))

;; --- settled turns ----------------------------------------------------------
;; The conversation atom is shared by every mounted chat panel (peck-main and
;; the right-sidebar :chat pane can both be live). Each panel subscribes to the
;; same socket events, so without a singleton the one turn.end would append two
;; answers. This is that singleton: the first panel to settle a turn id wins;
;; the others skip the shared append and only clear their own live state.

(defonce ^:private *settled-turns (atom #{}))

(defn settle-turn!
  "Record that `turn-id`'s settlement has been folded into the shared
  conversation. True for the first caller (append), false for any other panel
  mounted at the same time (skip). A nil id can't be deduped, so it settles."
  [turn-id]
  (if (nil? turn-id)
    true
    (let [first? (not (contains? @*settled-turns turn-id))]
      (swap! *settled-turns conj turn-id)
      first?)))

(defn turn->message
  "Map a settled turn to a UI message. `payload` is the sidecar's turn.end
  payload, `streamed` the text accumulated from turn.delta and `steps` the tool
  steps the panel collected. The enrichment fields (citations, sources, lint
  warnings, call/arena ids, intent) are read from the payload when the sidecar
  sends them, so the settled-answer widgets stay wired without the old
  spawn-per-action turn shape."
  [payload streamed steps]
  (let [{:keys [reason intent answer learned note pages callId arenaId webSource
                citedSlugs candidateSlugs deadCitations lintWarnings sources]} payload
        steps (vec (or (seq steps) (:steps payload) []))
        final (or answer
                  (when-not (string/blank? streamed) streamed)
                  (:text payload))
        enrichment {:steps steps :call-id callId :arena-id arenaId
                    :web-source webSource :cited-slugs citedSlugs
                    :candidate-slugs candidateSlugs :dead-citations deadCitations
                    :lint-warnings lintWarnings :sources sources}]
    (cond
      (= reason "cancelled")
      {:role :assistant :text "Cancelled." :empty? true}

      (and (= intent "statement") (not learned))
      {:role :assistant :text (if (string/blank? note) "Nothing new to add there." note)}

      (= intent "statement")
      {:role :learned :text (if (string/blank? note) "Recorded." note) :pages pages}

      (not (string/blank? final))
      (assoc enrichment :role :assistant :text final :answer? true)

      (= intent "reminder")
      {:role :assistant :text "Reminder noted — check the Reminders panel." :steps steps}

      :else
      {:role :assistant
       :text "No matching pages found in the nest for this question."
       :empty? true})))

(defn send-chat!
  "Start a turn for `text`; resolves nil once the frame is on the wire. Turn
  output arrives asynchronously via `add-listener!`. Options (all optional, sent
  for forward-compatibility with the server's richer chat.send): `:depth`
  \"quick\"|\"full\", `:history` [{:role :text} …] and `:arena-compare-to` a
  prior call id."
  ([text] (send-chat! text {}))
  ([text {:keys [depth history arena-compare-to]}]
   (if (string/blank? text)
     (p/rejected (js/Error. "empty turn"))
     (-> (ensure-connected!)
         (p/then (fn [_]
                   (when-not (send-frame! "chat.send"
                                          (cond-> {:text text}
                                            (string? depth) (assoc :depth depth)
                                            (seq history) (assoc :history (vec history))
                                            (some? arena-compare-to) (assoc :arenaCompareTo arena-compare-to)))
                     (throw (js/Error. "sidecar socket is not open")))))))))

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
