(ns frontend.components.paste-source
  "Paste raw text — an article, notes, a transcript that isn't already a file —
  and save it into <graph>/pages/ as a Markdown source, hatchable like any other.
  A title + textarea panel; writes pages/<slug>.md with minimal YAML frontmatter
  via the :wikiAddSource IPC (electron.wiki/add-source!)."
  (:require [clojure.string :as string]
            [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.handler.coop :as coop]
            [frontend.handler.notification :as notification]
            [frontend.state :as state]
            [promesa.core :as p]
            [rum.core :as rum]
            [logseq.shui.ui :as ui]))

(defn- vault-root [] (config/get-repo-dir (state/get-current-repo)))

(defn- slugify [s]
  (-> (str s)
      string/trim
      string/lower-case
      (string/replace #"['’]" "")
      (string/replace #"[^a-z0-9]+" "-")
      (string/replace #"^-+|-+$" "")
      (as-> s (if (string/blank? s) "pasted-note" (subs s 0 (min 80 (count s)))))))

(defn- source-content [title body]
  (str "---\n"
       "title: " (string/replace (string/trim title) #"\n" " ") "\n"
       "added: " (.toISOString (js/Date.)) "\n"
       "source: pasted\n"
       "---\n\n"
       (string/trim body) "\n"))

(defn- save! [*title *body *busy? {:keys [on-saved]}]
  (let [title (string/trim @*title)
        body  (string/trim @*body)]
    (cond
      (string/blank? body)  (notification/show! "Nothing to save — paste some text first." :warning true)
      (string/blank? title) (notification/show! "Give it a title." :warning true)
      :else
      (do
        (reset! *busy? true)
        (-> (ipc/ipc "wikiAddSource" (vault-root) (str (slugify title) ".md") (source-content title body))
            (p/then (fn [r]
                      (let [{:keys [ok name duplicate reason]} (js->clj r :keywordize-keys true)]
                        (cond
                          (and ok duplicate)
                          (notification/show! (str "That text is already in your coop as " duplicate ".") :info true)
                          ok
                          (do (notification/show! (str "Saved as pages/" name ".") :success true)
                              (coop/refresh-counts!)
                              (reset! *title "") (reset! *body "")
                              (when on-saved (on-saved name)))
                          :else
                          (notification/show! (str "Couldn't save: " (or reason "unknown error")) :error true)))))
            (p/catch (fn [e] (notification/show! (str "Couldn't save: " e) :error true)))
            (p/finally (fn [] (reset! *busy? false))))))))

(rum/defcs paste-panel
  < (rum/local "" ::title)
    (rum/local "" ::body)
    (rum/local false ::busy?)
  [state {:keys [on-cancel] :as opts}]
  (let [*title (::title state)
        *body  (::body state)
        *busy? (::busy? state)]
    [:div.my-2.p-3.rounded.border.border-gray-06.bg-gray-02
     [:input.form-input.is-small.w-full.mb-2
      {:type "text" :placeholder "Title — e.g. \"Acme call notes\""
       :value @*title
       :on-change #(reset! *title (.. % -target -value))}]
     [:textarea.form-textarea.is-small.w-full
      {:rows 8 :placeholder "Paste article text, notes, a transcript…"
       :value @*body
       :on-change #(reset! *body (.. % -target -value))}]
     [:div.flex.gap-2.justify-end.mt-2
      (ui/button {:variant :ghost :size :sm :on-click #(when on-cancel (on-cancel))} "Cancel")
      (ui/button {:size :sm :disabled @*busy?
                  :on-click #(save! *title *body *busy? opts)}
                 (if @*busy? "Saving…" "Save to pages/"))]]))
