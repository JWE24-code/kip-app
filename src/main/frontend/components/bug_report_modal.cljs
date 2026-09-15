(ns frontend.components.bug-report-modal
  "The 'Report this bug' flow attached to frontend.components.llm-banner's
  error-view — a targeted, one-click report from the exact moment something
  went wrong, distinct from the existing full-page frontend.components.bug-
  report (Logseq's inherited clipboard-inspector + manual 'file an issue'
  link). Content-free by default: feature tag, app version, OS, LLM provider
  id + configured flag (no keys, no vault/note content), the already-
  humanized error, and a REQUIRED free-text description the user writes
  themselves. Nothing is sent until the user clicks Send — this modal IS the
  confirmation step.

  POSTs via the :reportBug IPC -> electron.bug-report -> kip-backend's
  unauthenticated POST /v1/bug-reports -> a GitHub issue on a fixed repo."
  (:require [clojure.string :as string]
            [electron.ipc :as ipc]
            [frontend.state :as state]
            [frontend.util :as util]
            [frontend.version :as version]
            [promesa.core :as p]
            [rum.core :as rum]
            [logseq.shui.ui :as ui]))

(defn- os-tag []
  (cond util/mac? "mac" util/win32? "windows" util/linux? "linux" :else "unknown"))

(defn- send! [context error *description *busy? *result]
  (let [llm (:kip/llm @state/state)
        payload (cond-> {:context context
                          :description (string/trim @*description)
                          :app_version version/version
                          :os (os-tag)
                          :llm_provider (:provider llm)
                          :llm_configured (boolean (:configured? llm))}
                   (:title error) (assoc :error_title (:title error))
                   (:hint error)  (assoc :error_hint (:hint error))
                   (:raw error)   (assoc :error_raw (:raw error)))]
    (reset! *busy? true)
    (-> (ipc/ipc "reportBug" payload)
        (p/then (fn [r]
                  (let [{:keys [ok url message]} (js->clj r :keywordize-keys true)]
                    (reset! *result (if ok {:ok true :url url} {:ok false :message message})))))
        (p/catch (fn [e] (reset! *result {:ok false :message (str e)})))
        (p/finally (fn [] (reset! *busy? false))))))

(rum/defcs report-form
  < (rum/local "" ::description)
    (rum/local false ::busy?)
    (rum/local nil ::result)
  [state context error close-fn]
  (let [*description (::description state)
        *busy?       (::busy? state)
        *result      (::result state)]
    [:div.w-full.mx-auto {:class "md:max-w-[500px]"}
     [:h2.text-lg.font-medium.mb-2 "Report this bug"]
     (if-let [{:keys [ok url message]} @*result]
       [:div.text-sm.my-2
        (if ok
          [:<>
           [:div.text-green-500.mb-2 "Thanks — the issue was filed."]
           [:a.underline {:href url :target "_blank"} url]]
          [:div.text-red-500 (str "Couldn't send: " message)])
        [:div.mt-3.flex.justify-end
         (ui/button {:size :sm :on-click close-fn} "Close")]]
       [:<>
        [:p.text-sm.opacity-70.mb-2
         "This sends the following to a public GitHub issue tracker. No vault or note content is included."]
        [:div.text-xs.opacity-60.rounded.p-2.mb-2 {:class "bg-gray-02"}
         [:div (str "Feature: " context)]
         [:div (str "App: kip-app v" version/version " (" (os-tag) ")")]
         (when-let [p (:provider (:kip/llm @state/state))] [:div (str "LLM provider: " p)])
         (when (:title error) [:div (str "Error: " (:title error))])]
        [:textarea.form-textarea.is-small.w-full
         {:rows 5 :placeholder "What were you doing when this happened? (required)"
          :value @*description
          :on-change #(reset! *description (.. % -target -value))}]
        [:div.flex.gap-2.justify-end.mt-3
         (ui/button {:variant :ghost :size :sm :on-click close-fn} "Cancel")
         (ui/button {:size :sm
                     :disabled (or @*busy? (string/blank? @*description))
                     :on-click #(send! context error *description *busy? *result)}
                    (if @*busy? "Sending…" "Send bug report"))]])]))

(defn open!
  "Opens the report modal. `context` is a short feature tag (e.g. \"peck/chat\");
  `error` is {:title :hint :raw} from llm-handler/humanize-error, or nil for a
  report not attached to a specific error."
  [{:keys [context error]}]
  (state/set-modal! (fn [close-fn] (report-form context error close-fn))))
