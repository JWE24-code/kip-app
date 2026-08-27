(ns frontend.components.exports
  "The Exports panel (right-sidebar entry, see :exports in
  frontend.components.right-sidebar): a live list of <coop>/exports/ — the
  files Peck's docx / pptx / … skills produce. Each row opens the file in the
  OS default app, reveals it in the file manager, saves a copy elsewhere, or
  moves it to the system trash. Backed by the :wikiExport* IPC channels
  (electron.wiki) — the renderer only ever passes a filename, the main
  process re-validates it against exports/."
  (:require [cljs-bean.core :as bean]
            [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [promesa.core :as p]
            [rum.core :as rum]))

(def ^:private poll-ms 4000)

(defn- vault-root [] (config/get-repo-dir (state/get-current-repo)))

(defn- fmt-size [bytes]
  (cond
    (< bytes 1024) (str bytes " B")
    (< bytes (* 1024 1024)) (str (js/Math.round (/ bytes 1024)) " KB")
    :else (str (.toFixed (/ bytes (* 1024 1024)) 1) " MB")))

(defn- fmt-time [ms]
  (let [d (js/Date. ms)]
    (if (= (.toDateString d) (.toDateString (js/Date.)))
      (.toLocaleTimeString d js/undefined #js {:hour "2-digit" :minute "2-digit"})
      (.toLocaleDateString d))))

(def ^:private ext->icon
  {"docx" "file-text" "doc" "file-text" "md" "file-text" "txt" "file-text" "pdf" "file-text"
   "pptx" "presentation" "ppt" "presentation"
   "xlsx" "table" "xls" "table" "csv" "table"
   "png" "photo" "jpg" "photo" "jpeg" "photo" "gif" "photo" "svg" "photo" "webp" "photo"})

(defn- fetch! [*files *loading?]
  (reset! *loading? true)
  (-> (ipc/ipc "wikiExportsList" (vault-root))
      (p/then (fn [r] (reset! *files (vec (bean/->clj r)))))
      (p/catch (fn [_] (reset! *files [])))
      (p/finally (fn [] (reset! *loading? false)))))

(defn- act!
  "Fire a :wikiExport* action for one file; run `after` on completion."
  ([channel filename] (act! channel filename nil))
  ([channel filename after]
   (-> (ipc/ipc channel (vault-root) filename)
       (p/finally (fn [] (when after (after)))))))

(defn- confirm-trash! [filename on-yes]
  (state/set-modal!
   (ui/make-confirm-modal
    {:title (str "Delete " filename "?")
     :sub-title "It moves to your system trash — you can still restore it from there."
     :on-confirm (fn [_e {:keys [close-fn]}]
                   (close-fn)
                   (on-yes))})))

(rum/defc file-row < rum/static
  [{:keys [name size mtime ext]} refresh]
  [:div.flex.items-center.gap-2.py-1.px-1.rounded.group
   {:class "hover:bg-gray-03"}
   (ui/icon (get ext->icon ext "file") {:class "opacity-50 shrink-0" :size 17})
   [:div.flex-1.min-w-0
    [:button.block.w-full.text-left.text-sm.truncate.hover:underline
     {:title (str "Open " name) :on-click #(act! "wikiExportOpen" name)}
     name]
    [:div.text-xs.opacity-40.tabular-nums (str (fmt-size size) "  ·  " (fmt-time mtime))]]
   [:div.flex.items-center.shrink-0.opacity-0.group-hover:opacity-100
    (ui/button {:icon "folder" :icon-props {:size 15} :variant :ghost :size :xs
                :title "Reveal in folder" :on-click #(act! "wikiExportReveal" name)})
    (ui/button {:icon "download" :icon-props {:size 15} :variant :ghost :size :xs
                :title "Save a copy…" :on-click #(act! "wikiExportSaveAs" name)})
    (ui/button {:icon "trash" :icon-props {:size 15} :variant :ghost :size :xs
                :title "Delete"
                :on-click #(confirm-trash! name (fn [] (act! "wikiExportTrash" name refresh)))})]])

(rum/defcs exports-panel
  < rum/reactive
  (rum/local nil ::files)
  (rum/local false ::loading?)
  (rum/local nil ::poll-id)
  {:will-mount (fn [state]
                 (fetch! (::files state) (::loading? state))
                 (reset! (::poll-id state)
                         (js/setInterval #(fetch! (::files state) (::loading? state)) poll-ms))
                 state)
   :will-unmount (fn [state]
                   (when-let [id @(::poll-id state)] (js/clearInterval id))
                   state)}
  [state]
  (let [*files (::files state)
        *loading? (::loading? state)
        files @*files
        refresh #(fetch! *files *loading?)
        n (count files)]
    [:div.flex.flex-col {:style {:height "100%"}}
     [:div.flex.items-center.justify-between.px-1.pb-1
      [:span.text-xs.opacity-50
       (cond
         (and (nil? files) @*loading?) "Loading…"
         (nil? files) ""
         :else (str n " file" (when (not= n 1) "s") " in exports/"))]
      (ui/button {:icon "refresh" :icon-props {:size 15} :variant :ghost :size :xs
                  :title "Refresh" :on-click refresh})]
     [:div.flex-1.overflow-y-auto {:class "overflow-x-hidden"}
      (cond
        (and (some? files) (empty? files))
        [:div.text-sm.opacity-50.px-2.py-6.leading-relaxed
         "Nothing here yet. Ask Peck to build a Word doc or a slide deck "
         "(\"make me a doc from the Q3 sheet\") and it lands in this folder."]

        (some? files)
        (for [f files]
          (rum/with-key (file-row f refresh) (:name f)))

        :else nil)]]))
