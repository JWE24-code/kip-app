(ns electron.preference-signals
  "In-process bridge to scripts/lib/feedback-poster.js — the electron side of
  the preference-signals epic (kip-app#73). The renderer's content-free
  signals (a 👍/👎 rating, a debounced behaviour event, an arena verdict)
  come in over two IPC channels — :kipFeedback (rating / behaviour) POSTs
  once to /v1/feedback, :kipArena (a verdict on a regenerate A/B pair) POSTs
  once to /v1/arena/<id>/verdict.

  feedback-poster.js does the gating itself: postFeedback() and
  postArenaVerdict() both check the active provider is `kip` and resolve
  { ok: false } without a request otherwise, and postFeedback() sanitises
  the signal down to the closed wire field set, so nothing but
  { call_id, kind, enum/int } can leave. Required via
  js/require for the same reason electron.llm does (see its docstring)."
  (:require [cljs-bean.core :as bean]
            ["path" :as node-path]
            [electron.logger :as logger]
            [electron.wiki :as wiki]
            [promesa.core :as p]))

(def ^:private feedback-lib
  (js/require (.join node-path wiki/scripts-dir "lib" "feedback-poster.js")))

(defn post-feedback!
  "POST one preference signal. `signal` is a CLJS map — at least
  {:call_id :kind} plus the kind's fields. Never rejects; resolves the
  #js {:ok bool} from feedback-poster (false when the provider isn't `kip`,
  the signal is malformed, or the request failed)."
  [vault-root signal]
  (-> (.postFeedback feedback-lib
                     (bean/->js signal)
                     #js {:vaultRoot (or vault-root js/undefined)})
      (p/catch (fn [err]
                 (logger/warn "[PreferenceSignals]" (str "postFeedback failed: " err))
                 #js {:ok false}))))

(defn post-arena-verdict!
  "POST a verdict on an arena A/B pair. `winner` is \"a\" | \"b\" | \"tie\" |
  \"skip\". Never rejects; resolves #js {:ok bool} (false when the provider
  isn't `kip`, the winner/id is bad, or the request failed)."
  [vault-root arena-id winner]
  (-> (.postArenaVerdict feedback-lib
                         arena-id
                         winner
                         #js {:vaultRoot (or vault-root js/undefined)})
      (p/catch (fn [err]
                 (logger/warn "[PreferenceSignals]" (str "postArenaVerdict failed: " err))
                 #js {:ok false}))))
