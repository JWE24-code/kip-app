(ns frontend.components.drop-source
  "Drag a Markdown / text / Office / PDF file onto the Peck view or the Hatch
  panel and it lands in <graph>/eggs/ as a Hatch source — no need to leave the
  app and use the OS file manager. A .docx / .xlsx / .pptx / .pdf is converted
  to Markdown on the way in (scripts/office-extract.js). `drop-zone` wraps its
  children: an overlay shows while a file is dragged over, a notification
  reports the result on drop."
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            [electron.ipc :as ipc]
            [frontend.components.coop-glossary :as glossary]
            [frontend.config :as config]
            [frontend.handler.coop :as coop]
            [frontend.handler.notification :as notification]
            [frontend.state :as state]
            [promesa.core :as p]
            [rum.core :as rum]))

(def ^:private accepted-exts #{"md" "markdown" "mdown" "txt" "text" "org"})
(def ^:private office-exts #{"docx" "xlsx" "xls" "xlsm" "csv" "tsv" "pptx" "pdf"})
(def ^:private legacy-hints
  {"doc" ".docx" "ppt" ".pptx" "odt" ".docx" "odp" ".pptx" "ods" ".xlsx"
   "rtf" ".docx" "pages" ".docx" "key" ".pptx" "numbers" ".xlsx"})

(defn- vault-root [] (config/get-repo-dir (state/get-current-repo)))

(defn- ext-of [filename]
  (let [i (string/last-index-of (or filename "") ".")]
    (when (and i (pos? i))
      (string/lower-case (subs filename (inc i))))))

(defn- has-files? [^js e]
  (some-> e .-dataTransfer .-types (.includes "Files")))

(defn- files-seq [^js e]
  (some-> e .-dataTransfer .-files js/Array.from seq))

(defn- notify-added!
  ([name] (notify-added! name nil))
  ([name detail]
   (notification/show!
    [:div.text-sm
     [:div "Added " [:b name] " to " (glossary/term "eggs/") "."]
     (when detail [:div.text-xs.opacity-70.mt-0.5 detail])
     [:button.text-xs.underline.opacity-80.hover:opacity-100.mt-1
      {:on-click (fn []
                   (notification/clear-all!)
                   (state/pub-event! [:modal/show-hatch]))}
      "Hatch it now →"]]
    :success false)))

(defn- array-buffer->base64 [buf]
  (let [bytes (js/Uint8Array. buf)
        len   (.-length bytes)
        step  8192]
    (loop [i 0 acc ""]
      (if (< i len)
        (recur (+ i step)
               (str acc (.apply js/String.fromCharCode nil
                                (.subarray bytes i (min len (+ i step))))))
        (js/btoa acc)))))

(defn- add-office! [^js file on-added]
  (let [name (.-name file)]
    (-> (.arrayBuffer file)
        (p/then (fn [buf]
                  (ipc/ipc "wikiAddOfficeSource" (vault-root) name (array-buffer->base64 buf))))
        (p/then (fn [r]
                  (let [{:keys [ok name kind warnings reason]} (bean/->clj r)]
                    (if ok
                      (do (notify-added! name (str "converted from " (or kind "document")
                                                   (when (seq warnings) (str " — " (first warnings)))))
                          (coop/refresh-counts!)
                          (when on-added (on-added name)))
                      (notification/show!
                       (str "Couldn't convert " (.-name file) ": " reason) :error true)))))
        (p/catch (fn [err]
                   (notification/show! (str "Couldn't read " name ": " err) :error true))))))

(defn- add-one! [^js file on-added]
  (let [name (.-name file)
        ext  (ext-of name)]
    (cond
      (contains? office-exts ext)
      (add-office! file on-added)

      (contains? legacy-hints ext)
      (notification/show!
       [:div.text-sm
        "Can't add " [:b name] " — re-save it as a " [:code (get legacy-hints ext)]
        " and drop that instead."]
       :warning true)

      (not (contains? accepted-exts ext))
      (notification/show!
       [:div.text-sm
        "Can't add " [:b name] " — Kip takes Markdown / text ("
        [:code ".md"] ", " [:code ".txt"] ", " [:code ".org"] ") and Office / PDF ("
        [:code ".docx"] ", " [:code ".xlsx"] ", " [:code ".pptx"] ", " [:code ".pdf"] ")."]
       :warning true)

      :else
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
