(ns frontend.components.bug-report-modal
  "The 'Report this bug' flow attached to frontend.components.llm-banner's
  error-view — a targeted, one-click report from the exact moment something
  went wrong, distinct from the existing full-page frontend.components.bug-
  report (Logseq's inherited clipboard-inspector + manual 'file an issue'
  link). Content-free by default: feature tag, app version, OS, LLM provider
  id (no keys, no vault/note content), the already-humanized error, and a
  REQUIRED free-text description the user writes themselves.

  Opens kip-app's real GitHub Issue Form (.github/ISSUE_TEMPLATE/bug_report.yml)
  prefilled in the user's browser, rather than filing the issue automatically
  from a backend — the user reviews and submits it on GitHub themselves, so
  there's no relay server and no server-held GitHub credential. Blank issues
  are disabled on this repo (config.yml), so the template + its field ids are
  required, not optional: a bare title=/body= link would 404 into the
  template picker instead of a prefilled form."
  (:require [clojure.string :as string]
            [frontend.state :as state]
            [frontend.util :as util]
            [frontend.version :as version]
            [rum.core :as rum]
            [logseq.shui.ui :as ui]))

(defn- os-tag []
  (cond util/mac? "mac" util/win32? "windows" util/linux? "linux" :else "unknown"))

;; GitHub issue-form links have no documented length ceiling, but browsers
;; cap URLs well under what a full stack trace could run to — truncate hard
;; rather than risk a silently-broken prefill.
(def ^:private max-raw-chars 800)

(defn- truncate [s]
  (if (> (count s) max-raw-chars) (str (subs s 0 max-raw-chars) "…") s))

(defn- what-field [error description]
  (string/join
   "\n"
   (remove nil?
           [description
            (when (:title error) (str "\n---\n**Error:** " (:title error)))
            (:hint error)
            (when (:raw error)
              (str "\n<details><summary>Details</summary>\n\n```\n" (truncate (:raw error)) "\n```\n</details>"))])))

;; GitHub's issue-form prefill only reaches input/textarea fields (title, and
;; this template's `what`/`version`) — verified live that its OS/Area
;; dropdowns and the sidebar's Labels widget silently ignore a query param,
;; so there's no point spending URL length on them; the user picks those on
;; the form itself.
(defn- issue-title [context error]
  (str "[" context "] " (or (:title error) "bug")))

(defn- issue-url [context error description]
  (let [params {"template" "bug_report.yml"
                "title" (issue-title context error)
                "what" (what-field error description)
                "version" (str "kip-app v" version/version " (" (os-tag) ")")}]
    (str "https://github.com/JWE24-code/kip-app/issues/new?"
         (string/join "&" (map (fn [[k v]] (str k "=" (js/encodeURIComponent v))) params)))))

(rum/defcs report-form
  < (rum/local "" ::description)
  [state context error close-fn]
  (let [*description (::description state)]
    [:div.w-full.mx-auto {:class "md:max-w-[500px]"}
     [:h2.text-lg.font-medium.mb-2 "Report this bug"]
     [:p.text-sm.opacity-70.mb-2
      "Opens a prefilled GitHub issue in your browser — review it and submit it there. No vault or note content is included."]
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
                  :disabled (string/blank? @*description)
                  :on-click #(do (util/open-url (issue-url context error (string/trim @*description)))
                                 (close-fn))}
                 "Open on GitHub →")]]))

(defn open!
  "Opens the report modal. `context` is a short feature tag (e.g. \"peck/chat\");
  `error` is {:title :hint :raw} from llm-handler/humanize-error, or nil for a
  report not attached to a specific error."
  [{:keys [context error]}]
  (state/set-modal! (fn [close-fn] (report-form context error close-fn))))
