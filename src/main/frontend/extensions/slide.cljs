(ns frontend.extensions.slide
  (:require [rum.core :as rum]
            [cljs-bean.core :as bean]
            [frontend.loader :as loader]
            [frontend.ui :as ui]
            [frontend.context.i18n :refer [t]]
            [frontend.config :as config]
            [frontend.components.block :as block]
            [clojure.string :as string]
            [frontend.db-mixins :as db-mixins]
            [frontend.db :as db]
            [frontend.mixins :as mixins]
            [frontend.modules.outliner.tree :as outliner-tree]
            [frontend.state :as state]
            [frontend.util :as util]))

(defn loaded? []
  js/window.Reveal)

(defn- with-properties
  [m block]
  (let [properties (:block/properties block)]
    (if (seq properties)
      (merge m
             (update-keys
              properties
              (fn [k]
                (-> (str "data-" (name k))
                    (string/replace "data-data-" "data-")))))
      m)))

(defonce *loading? (atom false))

(defn- fullscreen-element
  []
  (or js/document.fullscreenElement js/document.webkitFullscreenElement))

(defn- request-fullscreen!
  [el]
  (when-let [request (and el (or (.-requestFullscreen el) (.-webkitRequestFullscreen el)))]
    (.call request el)))

(defn- exit-fullscreen!
  []
  (when-let [exit (or (.-exitFullscreen js/document) (.-webkitExitFullscreen js/document))]
    (.call exit js/document)))

(defn- toggle-fullscreen!
  [el]
  (if (fullscreen-element)
    (exit-fullscreen!)
    (request-fullscreen! el)))

(defn render!
  []
  (let [deck (js/window.Reveal.
              (js/document.querySelector ".reveal")
              (bean/->js
               {:embedded true
                :controls true
                :history false
                :center true
                :transition "slide"
                :keyboardCondition "focused"}))]
    (.initialize deck)))

;; reveal.js doesn't support multiple nested sections yet.
;; https://github.com/hakimel/reveal.js/issues/1440
(rum/defc block-container
  [config block level]
  (let [children (:block/children block)
        has-children? (seq children)
        children (when has-children?
                   (map (fn [block]
                          (block-container config block (inc level))) children))
        block-el (block/block-container config (dissoc block :block/children))
        dom-attrs (with-properties {:key (str "slide-block-" (:block/uuid block))} block)]
    (if has-children?
      [:section dom-attrs
       [:section.relative
        block-el]
       children]
      [:section dom-attrs block-el])))

(rum/defcs slide-content < rum/reactive
  (rum/local false ::fullscreen?)
  mixins/event-handler-mixin
  {:did-mount (fn [state]
                (let [*fullscreen? (::fullscreen? state)
                      *reveal-ref (::reveal-ref state)]
                  (mixins/listen state js/document "fullscreenchange"
                                 (fn [_e]
                                   (reset! *fullscreen? (= (fullscreen-element) (rum/deref *reveal-ref)))))
                  (mixins/listen state js/document "webkitfullscreenchange"
                                 (fn [_e]
                                   (reset! *fullscreen? (= (fullscreen-element) (rum/deref *reveal-ref))))))
                state)
   :will-mount (fn [state]
                 (assoc state ::reveal-ref (rum/create-ref)))}
  [state loading? style config blocks]
  (let [*fullscreen? (::fullscreen? state)
        fullscreen? (rum/react *fullscreen?)
        *reveal-ref (::reveal-ref state)]
    [:div
     [:div.flex.items-center.justify-between
      [:p.text-sm
       (t :page/slide-view-tip-go-fullscreen)]
      [:button.button.icon.slide-fullscreen-toggle
       {:title (if fullscreen?
                 (t :page/slide-view-exit-fullscreen)
                 (t :page/slide-view-fullscreen))
        :on-click (fn [e]
                    (util/stop e)
                    (toggle-fullscreen! (rum/deref *reveal-ref)))}
       (ui/icon (if fullscreen? "minimize" "maximize"))]]
     [:div.reveal
      {:ref *reveal-ref
       :style (if fullscreen? {} style)}
      (when loading?
        [:div.ls-center (ui/loading "")])
      [:div.slides
       (map #(block-container config % 1) blocks)]]]))

(rum/defc slide < rum/reactive db-mixins/query
  {:did-mount (fn [state]
                (if (loaded?)
                  (do
                    (reset! *loading? false)
                    (render!))
                  (do
                    (reset! *loading? true)
                    (loader/load
                     (config/asset-uri (if config/publishing? "static/js/reveal.js" "/static/js/reveal.js"))
                     (fn []
                       (reset! *loading? false)
                       (render!)))))
                state)}
  [page-name]
  (let [loading? (rum/react *loading?)
        page (db/entity [:block/name page-name])
        journal? (:journal? page)
        repo (state/get-current-repo)
        blocks (-> (db/get-paginated-blocks repo (:db/id page)
                                            {:limit 1000})
                   (outliner-tree/blocks->vec-tree page-name))
        blocks (if journal?
                 (rest blocks)
                 blocks)
        blocks (map (fn [block]
                      (update block :block/children
                              (fn [children]
                                (->>
                                 (mapcat
                                  (fn [x]
                                    (tree-seq map? :block/children x))
                                  children)
                                 (map #(dissoc % :block/children)))))) blocks)
        config {:id          "slide-reveal-js"
                :slide?      true
                :sidebar?    true
                :page-name   page-name}]
    (slide-content loading? {:height 400} config blocks)))
