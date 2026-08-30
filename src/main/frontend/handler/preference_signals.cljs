(ns frontend.handler.preference-signals
  "Renderer side of the preference-signals epic (kip-app#73).

  Every mechanism — the 👍/👎 rating, the behaviour watcher, the arena — is
  INERT unless the active LLM provider is the managed `kip` connector. The
  gate here, `enabled?`, is the one check they all share; it reads the
  `:provider` already cached in `:kip/llm` by `frontend.handler.llm/refresh!`
  (called on the Peck/Hatch panels' mount and after an LLM settings save),
  so there's no extra IPC for it.

  `send!` ships one content-free signal to the managed backend via the
  :kipFeedback IPC (electron.preference-signals → scripts/lib/feedback-poster).
  `verdict!` is the same pattern over :kipArena for an arena A/B verdict.
  All of it is fire-and-forget and best-effort: a disabled gate, a rejected
  IPC, or a backend error all resolve to nil without disturbing anything."
  (:require [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.state :as state]
            [promesa.core :as p]))

(defn enabled?
  "True only when the managed `kip` connector is the active provider."
  []
  (= "kip" (some-> (:kip/llm @state/state) :provider)))

(defn- vault-root []
  (config/get-repo-dir (state/get-current-repo)))

(defn send!
  "Post one preference signal. `signal` is a map: at least
  {:call_id \"…\" :kind \"rating\"|\"behavior\"} plus the kind's fields
  (:score/:scale, or :behavior/:edit_bucket). No-op unless `enabled?`.
  Returns a promise that always resolves (never rejects)."
  [signal]
  (if (and (enabled?)
           (string? (:call_id signal))
           (:kind signal))
    (-> (ipc/ipc "kipFeedback" (vault-root) signal)
        (p/catch (fn [_] nil)))
    (p/resolved nil)))

(defn rate!
  "Convenience for the 👍/👎 widget. `score` is 1 (up) or 0 (down)."
  [call-id score]
  (send! {:call_id call-id :kind "rating" :score score :scale 2}))

(defn behavior!
  "Convenience for the behaviour watcher. `behavior` is one of
  \"accepted\" \"copied\" \"edited\" \"regenerated\" \"discarded\";
  `edit-bucket` (0–4) only with \"edited\"."
  ([call-id behavior] (behavior! call-id behavior nil))
  ([call-id behavior edit-bucket]
   (send! (cond-> {:call_id call-id :kind "behavior" :behavior behavior}
            (some? edit-bucket) (assoc :edit_bucket edit-bucket)))))

(def ^:private arena-winners #{"a" "b" "tie" "skip"})

(defn verdict!
  "Post a verdict on an arena A/B pair (a regenerate free-rider). `winner` is
  one of \"a\" \"b\" \"tie\" \"skip\". No-op unless `enabled?` and the args
  are well-formed. Returns a promise that always resolves."
  [arena-id winner]
  (if (and (enabled?) (string? arena-id) (seq arena-id) (arena-winners winner))
    (-> (ipc/ipc "kipArena" (vault-root) arena-id winner)
        (p/catch (fn [_] nil)))
    (p/resolved nil)))
