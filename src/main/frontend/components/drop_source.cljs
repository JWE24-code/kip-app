(ns frontend.components.drop-source
  "Drag a Markdown / text file onto the Peck view or the Hatch panel and it
  lands in <graph>/eggs/ as a Hatch source — no need to leave the app and use
  the OS file manager. `drop-zone` wraps its children: an overlay shows while a
  file is dragged over, a notification reports the result on drop. PDF/Word are
  rejected with a note (the retrieval layer can't read them yet)."
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.handler.coop :as coop]
            [frontend.handler.notification :as notification]
            [frontend.state :as state]
            [promesa.core :as p]
            [rum.core :as rum]))

(def ^:private accepted-exts #{"md" "markdown" "mdown" "txt" "text" "org"})

(defn- vault-root [] (config/get-repo-dir (state/get-current-repo)))

(defn- ext-of [filename]
  (let [i (string/last-index-of (or filename "") ".")]
    (when (and i (pos? i))
      (string/lower-case (subs filename (inc i))))))

(defn- has-files? [^js e]
  (some-> e .-dataTransfer .-types (.includes "Files")))

(defn- files-seq [^js e]
  (some-> e .-dataTransfer .-files js/Array.from seq))

(defn- notify-added! [name]
  (notification/show!
   [:div.text-sm
    [:div "Added " [:b name] " to " [:code "eggs/"] "."]
    [:button.text-xs.underline.opacity-80.hover:opacity-100.mt-1
     {:on-click (fn []
                  (notification/clear-all!)
                  (state/pub-event! [:modal/show-hatch]))}
     "Hatch it now →"]]
   :success false))

(defn- add-one! [^js file on-added]
  (let [name (.-name file)
        ext  (ext-of name)]
    (if-not (contains? accepted-exts ext)
      (notification/show!
       [:div.text-sm
        "Can't add " [:b name] " — Kip reads Markdown and text files ("
        [:code ".md"] ", " [:code ".txt"] ", " [:code ".org"] "). "
        "PDF and Word support is on the way."]
       :warning true)
      (-> (.text file)
          (p/then #(ipc/ipc "wikiAddEgg" (vault-root) name %))
          (p/then (fn [r]
                    (let [{:keys [ok name duplicate reason]} (bean/->clj r)]
                      (cond
                        (and ok duplicate)
                        (notification/show! (str name " is already in your coop.") :info true)

                        ok
                        (do (notify-added! name)
                            (coop/refresh-counts!)
                            (when on-added (on-added name)))

                        (= reason "no-graph")
                        (notification/show! "Open a graph first, then drop your file." :warning true)

                        :else
                        (notification/show! (str "Couldn't add " name ": " reason) :error true)))))
          (p/catch (fn [err]
                     (notification/show! (str "Couldn't read " name ": " err) :error true)))))))

(defn- handle-drop! [^js e on-added]
  (.preventDefault e)
  (doseq [file (files-seq e)]
    (add-one! file on-added)))

(rum/defcs drop-zone
  "Wrap content in a file-drop target. opts: {:on-added (fn [name]) :class
  <string> :style <map>}. The wrapper is `position: relative` so the
  drag-over overlay can fill it; pass :style to control how it sizes in its
  parent."
  < (rum/local false ::over?)
  [state opts & children]
  (let [*over? (::over? state)
        {:keys [on-added class style]} opts]
    [:div.relative
     {:class class
      :style style
      :on-drag-over (fn [e]
                      (when (has-files? e)
                        (.preventDefault e)
                        (reset! *over? true)))
      :on-drag-leave (fn [^js e]
                       (let [ct (.-currentTarget e)
                             rt (.-relatedTarget e)]
                         (when (or (nil? rt) (not (.contains ct rt)))
                           (reset! *over? false))))
      :on-drop (fn [e]
                 (reset! *over? false)
                 (handle-drop! e on-added))}
     (into [:<>] children)
     (when @*over?
       [:div.absolute.inset-0.z-20.flex.items-center.justify-center.pointer-events-none
        {:class "bg-gray-01"
         :style {:opacity 0.92}}
        [:div.border-2.border-dashed.rounded-lg.px-6.py-4.text-sm.font-medium.opacity-90
         {:style {:border-color "var(--ls-active-primary-color, #10b981)"}}
         "Drop to add to your coop"]])]))
