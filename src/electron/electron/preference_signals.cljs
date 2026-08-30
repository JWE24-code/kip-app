(ns electron.preference-signals
  "In-process bridge to scripts/lib/feedback-poster.js — the electron side of
  the preference-signals epic (kip-app#73). The renderer's content-free
  signals (a 👍/👎 rating, a debounced behaviour event, an arena verdict)
  come in over the :kipFeedback IPC channel and are POSTed once to the
  managed Kip backend's /v1/feedback.

  feedback-poster.js does the gating itself: postFeedback() checks the
  active provider is `kip` and resolves { ok: false } without a request
  otherwise, and it sanitises the signal down to the closed wire field set,
  so nothing but { call_id, kind, enum/int } can leave. Required via
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
