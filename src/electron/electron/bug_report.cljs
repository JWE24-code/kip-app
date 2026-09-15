(ns electron.bug-report
  "Renderer -> kip-backend for the 'Report this bug' feature
  (frontend.components.bug-report-modal). Always posts to Joeri's own managed
  backend at a FIXED URL, independent of whatever LLM provider/base URL the
  user has configured — bug reporting is not a BYOK-routable operation.
  Unauthenticated on the backend side (no kip_ account required), so this
  works for every install regardless of which LLM provider they use."
  (:require [cljs-bean.core :as bean]
            [electron.utils :as utils]
            [promesa.core :as p]))

(def ^:private endpoint "https://api.kip-ai.be/v1/bug-reports")

(defn report!
  "POSTs `payload` (a plain map — see bug-report-modal/send! for its shape) to
  kip-backend and resolves to a plain JS object {ok true url} or {ok false
  message} for the renderer to js->clj. Never rejects."
  [payload]
  (-> (utils/fetch endpoint
                    {:method "POST"
                     :headers {"Content-Type" "application/json"}
                     :body (js/JSON.stringify (bean/->js payload))})
      (p/then (fn [^js r]
                (p/let [json (.json r)]
                  (bean/->js
                   (if (.-ok r)
                     {:ok true :url (.-issue_url json)}
                     {:ok false :message (or (some-> json .-error .-message) (str "HTTP " (.-status r)))})))))
      (p/catch (fn [e] (bean/->js {:ok false :message (or (.-message e) (str e))})))))
