(ns frontend.extensions.excalidraw
  (:require [clojure.string :as string]
            ;; NOTE: Always use production build of excalidraw
            ;; See-also: https://github.com/excalidraw/excalidraw/pull/3330
            ["@excalidraw/excalidraw/dist/excalidraw.production.min" :refer [Excalidraw serializeAsJSON exportToSvg]]
            [frontend.config :as config]
            [frontend.db :as db]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.draw :as draw]
            [frontend.handler.notification :as notification]
            [frontend.handler.ui :as ui-handler]
            [frontend.handler.whiteboard :as whiteboard-handler]
            [frontend.rum :as r]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [goog.object :as gobj]
            [goog.functions :refer [debounce]]
            [rum.core :as rum]
            [promesa.core :as p]
            [frontend.mobile.util :as mobile-util]))

(def excalidraw (r/adapt-class Excalidraw))

(defn from-json
  [text]
  (when-not (string/blank? text)
    (try
      (js/JSON.parse text)
      (catch :default e
        (println "from json error:")
        (js/console.dir e)
        (notification/show!
         (util/format "Could not load this invalid excalidraw file")
         :error)))))

(defn- update-draw-content-width
  [state]
  (when-let [el ^js (rum/dom-node state)]
    (loop [el (.querySelector el ".draw-wrap")]
      (cond
        (or (nil? el) (undefined? el) (undefined? (.-classList el)))
        nil

        (..  el -classList (contains "block-content"))
        (let [client-width (.-clientWidth el)
              width (if (zero? client-width)
                      (.-width (.-getBoundingClientRect el))
                      client-width)]
          (reset! (::draw-width state) width))

        :else
        (recur (.-parentNode el))))
    state))

(defn excalidraw-theme [ui-theme]
  ;; One of these constants are meant to be used as a 'theme' argument for escalidraw:
  ;; https://github.com/excalidraw/excalidraw/blob/master/src/constants.ts#L75
  ;; But they are missing from the prod build of excalidraw we're using.
  ;; They map to "light" and "dark", happens that :ui/theme uses same values, so we are safe to pass it directly, for now.
  ;; Escalidraw may migrate to different values for these constants in future versions,
  ;; so, in order to not watch out for it every time we bump a new version we better migrate to constants as soon as they appear in a prod build.
  ui-theme)

(rum/defcs draw-inner < rum/reactive
  (rum/local 800 ::draw-width)
  (rum/local true ::zen-mode?)
  (rum/local false ::view-mode?)
  (rum/local false ::grid-mode?)
  (rum/local nil ::elements)
  (rum/local nil ::resize-observer)
  {:did-mount (fn [state]
                (reset! (::resize-observer state) (js/ResizeObserver. (debounce #(reset! (::draw-width state) 0) 300)))
                (.observe @(::resize-observer state) (ui/main-node))
                (update-draw-content-width state))
   :did-update update-draw-content-width
   :will-unmount (fn [state] (.disconnect @(::resize-observer state)))}
  [state data option]
  (let [ref (rum/create-ref)
        *draw-width (get state ::draw-width)
        *zen-mode? (get state ::zen-mode?)
        *view-mode? (get state ::view-mode?)
        *grid-mode? (get state ::grid-mode?)
        wide-mode? (state/sub :ui/wide-mode?)
        *elements (get state ::elements)
        {:keys [file block-uuid]} option]
    (when data
      [:div.overflow-hidden {:on-mouse-down (fn [e] (util/stop e))}
       [:div.my-1 {:style {:font-size 10}}
        [:a.mr-2 {:on-click ui-handler/toggle-wide-mode!}
         (util/format "Wide Mode (%s)" (if wide-mode? "ON" "OFF"))]
        [:a.mr-2 {:on-click #(swap! *zen-mode? not)}
         (util/format "Zen Mode (%s)" (if @*zen-mode? "ON" "OFF"))]
        [:a.mr-2 {:on-click #(swap! *view-mode? not)}
         (util/format "View Mode (%s)" (if @*view-mode? "ON" "OFF"))]
        [:a.mr-2 {:on-click #(swap! *grid-mode? not)}
         (util/format "Grid Mode (%s)" (if @*grid-mode? "ON" "OFF"))]
        [:a.mr-2 {:on-click #(when-let [block (db/pull [:block/uuid block-uuid])]
                               (editor-handler/edit-block! block :max block-uuid))}
         "Edit Block"]]
       [:div.draw-wrap
        {:ref ref
         :on-mouse-down (fn [e]
                          (util/stop e)
                          (state/set-block-component-editing-mode! true))
         :on-blur #(state/set-block-component-editing-mode! false)
         :style {:width  @*draw-width
                 :height (if wide-mode? 650 500)}}
        (excalidraw
         (merge
          {:on-change (fn [elements app-state files]
                        (when-not (or (= "down" (gobj/get app-state "cursorButton"))
                                      (gobj/get app-state "draggingElement")
                                      (gobj/get app-state "editingElement")
                                      (gobj/get app-state "editingGroupId")
                                      (gobj/get app-state "editingLinearElement"))
                          (let [elements->clj (js->clj elements {:keywordize-keys true})]
                            (when (and (seq elements->clj)
                                       (not= elements->clj @*elements)) ;; not= requires clj collections
                              (reset! *elements elements->clj)
                              (draw/save-excalidraw!
                               file
                               (serializeAsJSON elements app-state files "local"))))))

           :zen-mode-enabled @*zen-mode?
           :view-mode-enabled @*view-mode?
           :grid-mode-enabled @*grid-mode?
           :on-pointer-down #(.. (rum/deref ref) -firstChild focus)
           :initial-data data
           :theme (excalidraw-theme (state/sub :ui/theme))}))]])))

(rum/defcs draw-container < rum/reactive
  {:init (fn [state]
           (let [[option] (:rum/args state)
                 file (:file option)
                 *data (atom nil)
                 *loading? (atom true)]
             (when file
               (draw/load-excalidraw-file
                file
                (fn [data]
                  (let [data (from-json data)]
                    (reset! *data data)
                    (reset! *loading? false)))))
             (assoc state
                    ::data *data
                    ::loading? *loading?)))}
  [state option]
  (let [*data (get state ::data)
        *loading? (get state ::loading?)
        loading? (rum/react *loading?)
        data (rum/react *data)
        db-restoring? (state/sub :db/restoring?)]
    (when (:file option)
      (cond
        db-restoring?
        [:div.ls-center (ui/loading)]

        (false? loading?)
        (draw-inner data option)

        :else
        nil))))

(rum/defc draw < rum/reactive
  [option]
  (let [repo (state/get-current-repo)
        granted? (state/sub [:nfs/user-granted? repo])]
    ;; Web granted
    (when-not (and (config/local-db? repo)
                   (not granted?)
                   (not (util/electron?))
                   (not (mobile-util/native-platform?)))
      (draw-container option))))

;;;
;;; Whiteboard editor (headless excalidraw)
;;;
;;; The excalidraw chrome is hidden (zen mode) and replaced by our own toolbar.
;;; The canvas is driven through the excalidrawAPI handle. The .excalidraw json
;;; file is the source of truth; on every change (debounced) it is written back
;;; together with a sibling svg that embeds the scene, which is what pages use
;;; for embeds and previews.
;;;

(defonce ^:private *excalidraw-api (atom nil))
(defonce ^:private *active-tool (atom "selection"))
(defonce ^:private *scene-elements (atom nil))

(defn- parse-json-silent
  [text]
  (when-not (string/blank? text)
    (try
      (js/JSON.parse text)
      (catch :default _ nil))))

(defn export-svg
  "Returns a promise resolving to the svg (html string) of the given scene,
  with the scene embedded so it can be re-imported for editing."
  [elements app-state files]
  (p/let [^js svg (exportToSvg (clj->js {:elements elements
                                         :appState app-state
                                         :files files
                                         :exportEmbedScene true
                                         :exportPadding 16}))]
    (.-outerHTML svg)))

(defn whiteboard-preview-svg
  "Returns a promise resolving to the svg (html string) of a whiteboard file."
  [file-path]
  (p/let [text (p/create (fn [resolve _reject]
                           (draw/load-excalidraw-file file-path resolve)))
          data (parse-json-silent text)]
    (when data
      (export-svg (.-elements data) (.-appState data) (.-files data)))))

(defn- set-active-tool!
  [type]
  (when-let [api @*excalidraw-api]
    (.setActiveTool api (clj->js {:type type}))))

(defn- fit-to-content!
  []
  (when-let [api @*excalidraw-api]
    (when (and @*scene-elements (pos? (.-length @*scene-elements)))
      (.scrollToContent api @*scene-elements true))))

(defn- copy-embed!
  [file-path]
  (util/copy-to-clipboard!
   (util/format "![](%s)" (whiteboard-handler/svg-path-for file-path))))

(defn- tool-icon
  [type]
  [:svg {:viewBox "0 0 24 24" :width 18 :height 18
         :fill "none" :stroke "currentColor" :stroke-width 1.7
         :stroke-linecap "round" :stroke-linejoin "round"}
   (case type
     "selection" [:path {:d "M5 3l14 8-6.5 1.5L9.5 19z"}]
     "rectangle" [:rect {:x 4 :y 6 :width 16 :height 12 :rx 1}]
     "ellipse" [:circle {:cx 12 :cy 12 :r 8}]
     "diamond" [:path {:d "M12 3l9 9-9 9-9-9z"}]
     "arrow" [:path {:d "M5 19L19 5M9 5h10v10"}]
     "line" [:path {:d "M5 19L19 5"}]
     "freedraw" [:path {:d "M4 20l1-4L16.5 4.5a2.1 2.1 0 0 1 3 3L8 19l-4 1z"}]
     "text" [:path {:d "M5 7V5h14v2M12 5v14M9 19h6"}]
     [:path {:d "M5 3l14 8-6.5 1.5L9.5 19z"}])])

(def ^:private toolbar-tools
  [{:type "selection" :label "Select"}
   {:type "rectangle" :label "Rectangle"}
   {:type "ellipse" :label "Ellipse"}
   {:type "diamond" :label "Diamond"}
   {:type "arrow" :label "Arrow"}
   {:type "line" :label "Line"}
   {:type "freedraw" :label "Draw"}
   {:type "text" :label "Text"}])

;;;
;;; Style panel for the current selection (our replacement for excalidraw's
;;; own property panel, which is unavailable headless — on narrow containers
;;; excalidraw renders its mobile chrome, where the panel does not exist).
;;;

(defonce ^:private *selection-styles (atom nil))

(def ^:private stroke-palette
  ["#1e1e1e" "#e03131" "#e8590c" "#f08c00" "#2f9e44" "#1971c2" "#6741d9"])

(def ^:private bg-palette
  ["transparent" "#ffffff" "#ffc9c9" "#ffec99" "#b2f2bb" "#a5d8ff" "#d0bfff"])

(defn- apply-style!
  "Patches the given style props (string keys) onto all selected elements."
  [props]
  (when-let [^js api @*excalidraw-api]
    (let [els (js/Array.from (.getSceneElements api))
          sel-ids (set (js/Object.keys (gobj/get (.getAppState api) "selectedElementIds")))
          patched (into-array
                   (map (fn [el]
                          (if (contains? sel-ids (.-id el))
                            (let [clone (js/Object.assign #js {} el)]
                              (doseq [[k v] props]
                                (gobj/set clone k v))
                              (gobj/set clone "updated" (js/Date.now))
                              clone)
                            el))
                        els))]
      (.updateScene api (clj->js {:elements patched})))))

(defn- compute-selection-styles
  [elements sel-ids]
  (when-let [sel (->> (js/Array.from elements)
                      (remove #(gobj/get % "isDeleted"))
                      (filter #(contains? sel-ids (.-id %)))
                      not-empty)]
    (let [first-el (first sel)
          common? (fn [k]
                    (let [v (gobj/get first-el k)]
                      (every? #(= v (gobj/get % k)) sel)))]
      {:count (count sel)
       :stroke-color (when (common? "strokeColor") (gobj/get first-el "strokeColor"))
       :bg-color (when (common? "backgroundColor") (gobj/get first-el "backgroundColor"))
       :fill-style (when (common? "fillStyle") (gobj/get first-el "fillStyle"))
       :stroke-width (when (common? "strokeWidth") (gobj/get first-el "strokeWidth"))
       :stroke-style (when (common? "strokeStyle") (gobj/get first-el "strokeStyle"))
       :opacity (when (common? "opacity") (gobj/get first-el "opacity"))})))

(defn- style-btn
  [active? label on-click children]
  [:button
   {:title label
    :aria-label label
    :on-click (fn [] (on-click))
    :style {:display "flex"
            :align-items "center"
            :justify-content "center"
            :min-width 26
            :height 26
            :border "none"
            :border-radius 5
            :padding "0 4px"
            :cursor "pointer"
            :color "var(--ls-primary-text-color)"
            :background (if active?
                          "var(--ls-secondary-background-color)"
                          "transparent")}}
   children])

(defn- swatch
  [color active? on-pick]
  [:button
   {:key (str "swatch-" color)
    :title color
    :aria-label color
    :on-click (fn [] (on-pick))
    :style {:width 18
            :height 18
            :border-radius 4
            :border (if active?
                      "2px solid var(--ls-primary-text-color)"
                      "1px solid var(--ls-border-color)")
            :background (if (= color "transparent")
                          "var(--ls-primary-background-color)"
                          color)
            :cursor "pointer"
            :padding 0
            :background-image (when (= color "transparent")
                                "linear-gradient(45deg, var(--ls-border-color) 25%, transparent 25%, transparent 75%, var(--ls-border-color) 75%)")
            :background-size "8px 8px"}}])

(rum/defc style-panel < rum/reactive
  []
  (let [{:keys [count stroke-color bg-color fill-style stroke-width stroke-style opacity]}
        (rum/react *selection-styles)]
    (when (and count (pos? count))
      [:div.whiteboard-style-panel
       {:style {:position "absolute"
                :bottom 64
                :left "50%"
                :transform "translateX(-50%)"
                :z-index 20
                :display "flex"
                :flex-wrap "wrap"
                :align-items "center"
                :gap "8px 14px"
                :max-width "92%"
                :padding "8px 12px"
                :border-radius 8
                :background "var(--ls-primary-background-color)"
                :border "1px solid var(--ls-border-color)"
                :box-shadow "0 2px 8px rgba(0,0,0,0.12)"
                :font-size 11
                :color "var(--ls-primary-text-color)"}}
       ;; stroke color
       (when stroke-color
         [:div {:style {:display "flex" :align-items "center" :gap 4}}
          (for [c stroke-palette]
            (swatch c (= c stroke-color) #(apply-style! {"strokeColor" c})))
          [:input {:type "color"
                   :value stroke-color
                   :title "Custom stroke color"
                   :on-input (fn [e] (apply-style! {"strokeColor" (gobj/get e "target" "value")}))
                   :style {:width 20 :height 20 :padding 0 :border "none"
                           :background "transparent" :cursor "pointer"}}]])
       ;; background color
       [:div {:style {:display "flex" :align-items "center" :gap 4}}
        (for [c bg-palette]
          (swatch c (= c bg-color) #(apply-style! {"backgroundColor" c})))
        [:input {:type "color"
                 :value (if (= "transparent" bg-color) "#ffffff" bg-color)
                 :title "Custom background color"
                 :on-input (fn [e] (apply-style! {"backgroundColor" (gobj/get e "target" "value")}))
                 :style {:width 20 :height 20 :padding 0 :border "none"
                         :background "transparent" :cursor "pointer"}}]]
       ;; fill style
       (when (and bg-color (not= "transparent" bg-color))
         [:div {:style {:display "flex" :align-items "center" :gap 2}}
          (style-btn (= fill-style "hachure") "Hachure fill" #(apply-style! {"fillStyle" "hachure"})
                     [:svg {:viewBox "0 0 24 24" :width 14 :height 14 :fill "none"
                            :stroke "currentColor" :stroke-width 1.7}
                      [:rect {:x 4 :y 6 :width 16 :height 12 :rx 1}]
                      [:path {:d "M5 14l9-7M8 18l11-8"}]])
          (style-btn (= fill-style "cross-hatch") "Cross-hatch fill" #(apply-style! {"fillStyle" "cross-hatch"})
                     [:svg {:viewBox "0 0 24 24" :width 14 :height 14 :fill "none"
                            :stroke "currentColor" :stroke-width 1.7}
                      [:rect {:x 4 :y 6 :width 16 :height 12 :rx 1}]
                      [:path {:d "M4 10l12-4M6 18l14-9"}]])
          (style-btn (= fill-style "solid") "Solid fill" #(apply-style! {"fillStyle" "solid"})
                     [:svg {:viewBox "0 0 24 24" :width 14 :height 14
                            :fill "currentColor" :stroke "none"}
                      [:rect {:x 4 :y 6 :width 16 :height 12 :rx 1}]])])
       ;; stroke width
       [:div {:style {:display "flex" :align-items "center" :gap 2}}
        (style-btn (= 1 stroke-width) "Thin stroke" #(apply-style! {"strokeWidth" 1})
                   [:svg {:viewBox "0 0 24 24" :width 14 :height 14
                          :stroke "currentColor" :stroke-width 1.5 :stroke-linecap "round"}
                    [:path {:d "M4 12h16"}]])
        (style-btn (= 2 stroke-width) "Bold stroke" #(apply-style! {"strokeWidth" 2})
                   [:svg {:viewBox "0 0 24 24" :width 14 :height 14
                          :stroke "currentColor" :stroke-width 3 :stroke-linecap "round"}
                    [:path {:d "M4 12h16"}]])
        (style-btn (= 4 stroke-width) "Extra bold stroke" #(apply-style! {"strokeWidth" 4})
                   [:svg {:viewBox "0 0 24 24" :width 14 :height 14
                          :stroke "currentColor" :stroke-width 5 :stroke-linecap "round"}
                    [:path {:d "M4 12h16"}]])]
       ;; stroke style
       [:div {:style {:display "flex" :align-items "center" :gap 2}}
        (style-btn (= "solid" stroke-style) "Solid" #(apply-style! {"strokeStyle" "solid"})
                   [:svg {:viewBox "0 0 24 24" :width 14 :height 14
                          :stroke "currentColor" :stroke-width 2 :stroke-linecap "round"}
                    [:path {:d "M4 12h16"}]])
        (style-btn (= "dashed" stroke-style) "Dashed" #(apply-style! {"strokeStyle" "dashed"})
                   [:svg {:viewBox "0 0 24 24" :width 14 :height 14
                          :stroke "currentColor" :stroke-width 2 :stroke-linecap "round"}
                    [:path {:d "M4 12h4M11 12h4M18 12h2"}]])
        (style-btn (= "dotted" stroke-style) "Dotted" #(apply-style! {"strokeStyle" "dotted"})
                   [:svg {:viewBox "0 0 24 24" :width 14 :height 14
                          :stroke "currentColor" :stroke-width 2.6 :stroke-linecap "round"}
                    [:path {:d "M5 12h1M11 12h1M17 12h1"}]])]
       ;; opacity
       [:div {:style {:display "flex" :align-items "center" :gap 6}}
        [:span {:style {:opacity 0.7}} (str (or opacity 100) "%")]
        [:input {:type "range"
                 :min 10 :max 100 :step 10
                 :value (or opacity 100)
                 :title "Opacity"
                 :on-input (fn [e] (apply-style! {"opacity" (js/parseInt (gobj/get e "target" "value") 10)}))
                 :style {:width 80}}]]])))

(rum/defc toolbar < rum/reactive
  [file-path]
  (let [active (rum/react *active-tool)]
    [:div.whiteboard-toolbar
     {:style {:position "absolute"
              :bottom 14
              :left "50%"
              :transform "translateX(-50%)"
              :z-index 20
              :display "flex"
              :align-items "center"
              :gap 2
              :padding "4px 6px"
              :border-radius 8
              :background "var(--ls-primary-background-color)"
              :border "1px solid var(--ls-border-color)"
              :box-shadow "0 2px 8px rgba(0,0,0,0.12)"}}
     (for [{:keys [type label]} toolbar-tools]
       [:button
        {:key type
         :title label
         :aria-label label
         :on-click (fn [] (set-active-tool! type))
         :style {:display "flex"
                 :align-items "center"
                 :justify-content "center"
                 :width 30
                 :height 30
                 :border "none"
                 :border-radius 6
                 :padding 0
                 :cursor "pointer"
                 :color "var(--ls-primary-text-color)"
                 :background (if (= active type)
                               "var(--ls-secondary-background-color)"
                               "transparent")}}
        (tool-icon type)])
     [:span {:style {:width 1 :height 20 :margin "0 4px"
                     :background "var(--ls-border-color)"}}]
     [:button
      {:title "Fit to content" :aria-label "Fit to content"
       :on-click (fn [] (fit-to-content!))
       :style {:display "flex" :align-items "center" :justify-content "center"
               :width 30 :height 30 :border "none" :border-radius 6
               :padding 0 :cursor "pointer"
               :color "var(--ls-primary-text-color)" :background "transparent"}}
      [:svg {:viewBox "0 0 24 24" :width 18 :height 18
             :fill "none" :stroke "currentColor" :stroke-width 1.7
             :stroke-linecap "round" :stroke-linejoin "round"}
       [:path {:d "M9 4H5a1 1 0 0 0-1 1v4M15 4h4a1 1 0 0 1 1 1v4M9 20H5a1 1 0 0 1-1-1v-4M15 20h4a1 1 0 0 0 1-1v-4"}]]]
     [:button
      {:title "Copy embed" :aria-label "Copy embed"
       :on-click (fn [] (copy-embed! file-path))
       :style {:display "flex" :align-items "center" :justify-content "center"
               :width 30 :height 30 :border "none" :border-radius 6
               :padding 0 :cursor "pointer"
               :color "var(--ls-primary-text-color)" :background "transparent"}}
      [:svg {:viewBox "0 0 24 24" :width 18 :height 18
             :fill "none" :stroke "currentColor" :stroke-width 1.7
             :stroke-linecap "round" :stroke-linejoin "round"}
       [:path {:d "M10 14a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.5 1.5M14 10a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.5-1.5"}]]]]))

(rum/defcs whiteboard-editor < rum/reactive
  {:init (fn [state]
           (let [[page-name] (:rum/args state)
                 file-path (whiteboard-handler/whiteboard-file-path page-name)
                 *data (atom nil)
                 *loading? (atom true)
                 save! (util/debounce
                        800
                        (fn [elements app-state files]
                          (let [json (serializeAsJSON elements app-state files "local")
                                empty? (or (nil? elements) (zero? (.-length elements)))]
                            (if empty?
                              (whiteboard-handler/save-whiteboard! file-path json)
                              (p/let [svg (export-svg elements app-state files)]
                                (whiteboard-handler/save-whiteboard! file-path json svg))))))]
             (when file-path
               (draw/load-excalidraw-file file-path
                 (fn [text]
                   (reset! *data (parse-json-silent text))
                   (reset! *loading? false))))
             (assoc state
                    ::file-path file-path
                    ::data *data
                    ::loading? *loading?
                    ::save! save!)))
   :will-unmount (fn [state]
                   (reset! *excalidraw-api nil)
                   (reset! *scene-elements nil)
                   (reset! *selection-styles nil)
                   state)}
  [state page-name]
  (let [*data (::data state)
        *loading? (::loading? state)
        loading? (rum/react *loading?)
        data (rum/react *data)
        file-path (::file-path state)
        ref (rum/create-ref)
        db-restoring? (state/sub :db/restoring?)]
    [:div.whiteboard-editor
     {:style {:position "absolute" :top 0 :right 0 :bottom 0 :left 0}}
     (cond
       db-restoring?
       [:div.ls-center (ui/loading)]

       (false? loading?)
       (when data
         [:<>
          ;; headless: hide excalidraw's chrome — our toolbar replaces it. On
          ;; narrow containers excalidraw renders its mobile variant, which uses
          ;; its own classes (App-toolbar/App-bottom-bar) outside the
          ;; layer-ui__wrapper, so hide both families. The App-bottom-bar stays
          ;; visible though: on selection it carries excalidraw's style panel
          ;; (stroke, fill, opacity...); its main-menu button is removed.
          [:style
           ".whiteboard-editor .excalidraw .layer-ui__wrapper,"
           ".whiteboard-editor .excalidraw .App-toolbar-container,"
           ".whiteboard-editor .excalidraw .mobile-misc-tools-container,"
           ".whiteboard-editor .excalidraw .dropdown-menu-button { display: none; }"]
          (toolbar file-path)
          (style-panel)
          [:div.whiteboard-canvas
           {:ref ref
            :style {:position "absolute" :top 0 :right 0 :bottom 0 :left 0}
            :on-mouse-down (fn [e]
                             (when-let [^js canvas (.-firstChild (rum/deref ref))]
                               (.focus canvas)))}
           (excalidraw
            {:initial-data data
             :theme (excalidraw-theme (state/sub :ui/theme))
             :zen-mode-enabled true ; hide the excalidraw chrome, we render our own toolbar
             :grid-mode-enabled false
             :view-mode-enabled false
             :UIOptions (clj->js {:canvasActions
                                  {:clearCanvas false
                                   :export false
                                   :load false
                                   :saveToActiveFile false
                                   :saveAsImage false
                                   :toggleTheme false
                                   :changeCanvasBackground false}})
             ;; 0.16 exposes the imperative api via ref; the excalidrawAPI
             ;; prop only exists from 0.17 on
             :ref (fn [api]
                    (when api
                      (reset! *excalidraw-api api)
                      (reset! *active-tool "selection")))
             :on-change (fn [elements app-state files]
                          (reset! *scene-elements elements)
                          (when-let [type (some-> (gobj/get app-state "activeTool")
                                                  (gobj/get "type"))]
                            (reset! *active-tool type))
                          (reset! *selection-styles
                                  (compute-selection-styles
                                   elements
                                   (set (js/Object.keys (gobj/get app-state "selectedElementIds" #js {})))))
                          (when-not (or (= "down" (gobj/get app-state "cursorButton"))
                                        (gobj/get app-state "draggingElement")
                                        (gobj/get app-state "editingElement")
                                        (gobj/get app-state "editingGroupId")
                                        (gobj/get app-state "editingLinearElement"))
                            ((::save! state) elements app-state files)))})]])

       :else
       [:div.ls-center (ui/loading)])]))
