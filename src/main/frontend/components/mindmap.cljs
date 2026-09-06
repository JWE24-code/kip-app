(ns frontend.components.mindmap
  "Mindmap tree renderer: renders a `:block/type \"mindmap\"` page as a clean,
   auto-laid-out, right-growing tree over the page's outline. The page title is
   the central topic, top-level blocks are branches, indentation is depth."
  (:require [cljs.math :as math]
            [frontend.components.page :as page]
            [frontend.components.whiteboard :as whiteboard]
            [frontend.config :as config]
            [frontend.context.i18n :refer [t]]
            [frontend.db :as db]
            [frontend.db.model :as model]
            [frontend.db-mixins :as db-mixins]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.mindmap :as mindmap-handler]
            [frontend.handler.route :as route-handler]
            [frontend.rum :refer [use-bounding-client-rect]]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [rum.core :as rum]))

;; Layout constants (px)
(def ^:private node-height 38)
(def ^:private node-pad-x 18)
(def ^:private h-gap 56)
(def ^:private v-gap 14)
(def ^:private max-node-width 204)
(def ^:private font-size 13)
(def ^:private margin 60)
(def ^:private level-x (+ max-node-width h-gap))

;; --- text measurement -------------------------------------------------------

(defonce ^:private measure-ctx (atom nil))

(defn- get-measure-ctx []
  (or @measure-ctx
      (let [canvas (js/document.createElement "canvas")
            ctx (.getContext canvas "2d")]
        (set! (.-font ctx) (str font-size "px 'Inter var', sans-serif"))
        (reset! measure-ctx ctx)
        ctx)))

(defn- measure-width [s]
  (let [s (str s)]
    (try
      (if-let [ctx (get-measure-ctx)]
        (.-width (.measureText ctx s))
        (* font-size 0.6 (count s)))
      (catch :default _e
        (* font-size 0.6 (count s))))))

;; --- tree extraction --------------------------------------------------------

(defn- pre-block? [block]
  (:block/pre-block? block))

(defn- visible-children [node]
  (if (util/collapsed? node) [] (:block/children node)))

(defn- block-children [node]
  (:block/children node))

(defn- build-branches
  "Builds the ordered branch trees (top-level blocks and their descendants) for
   a page, excluding pre-blocks (the `type:: mindmap` properties block)."
  [page blocks]
  (let [blocks (->> blocks (remove pre-block?) vec)
        by-parent (group-by (fn [b] (:db/id (:block/parent b))) blocks)]
    (letfn [(children-of [pid]
              (model/try-sort-by-left (get by-parent pid) {:db/id pid}))
            (build [pid]
              (mapv (fn [c]
                      (assoc c :block/children (build (:db/id c))))
                    (children-of pid)))]
      (build (:db/id page)))))

(defn- central-topic-title [page]
  (let [title-prop (get-in page [:block/properties :title])]
    (cond
      (and (string? title-prop) (seq title-prop)) title-prop
      (model/untitled-page? (:block/name page)) (t :untitled)
      :else (:block/original-name page))))

;; --- layout -----------------------------------------------------------------

(defn- display-content [content]
  (let [s (str (or content ""))
        max-text (- max-node-width (* 2 node-pad-x))]
    (if (<= (measure-width s) max-text)
      s
      (loop [s s]
        (if (<= (measure-width (str s "…")) max-text)
          (str s "…")
          (recur (subs s 0 (dec (count s)))))))))

(defn- node-width [content]
  (-> (+ (measure-width (display-content content)) (* 2 node-pad-x))
      (max 32)
      (min max-node-width)))

(defn- layout-tree [root]
  (let [positions (volatile! {})
        max-depth (volatile! 0)]
    (letfn [(subtree-height [node]
              (let [ch (visible-children node)]
                (if (seq ch)
                  (reduce + (map subtree-height ch))
                  (+ node-height v-gap))))
            (place [node depth top id]
              (let [ch (visible-children node)
                    h (subtree-height node)
                    content (or (:content node) (:block/content node) "")
                    w (node-width content)
                    cy (+ top (/ h 2))
                    x (+ margin (* depth level-x))]
                (vswap! max-depth max depth)
                (vswap! positions assoc id
                        {:x x :y cy :w w
                         :content (display-content content)
                         :collapsed? (boolean (util/collapsed? node))
                         :has-children? (boolean (or (seq (block-children node))
                                                     (util/collapsed? node)))})
                (loop [t top
                       cs (seq ch)]
                  (when cs
                    (let [c (first cs)]
                      (place c (inc depth) t (:block/uuid c)))
                    (recur (+ t (subtree-height (first cs))) (next cs))))))]
      (place root 0 margin ::root)
      {:positions @positions
       :width (+ (* 2 margin) (* (inc @max-depth) level-x))
       :height (+ (* 2 margin) (subtree-height root))})))

(defn- layout-edges [root]
  (let [edges (volatile! (transient []))]
    (letfn [(walk [node pid]
              (doseq [c (visible-children node)]
                (let [cid (:block/uuid c)]
                  (vswap! edges conj! [pid cid])
                  (walk c cid))))]
      (walk root ::root))
    (persistent! @edges)))

(defn- edge-path [parent child]
  (let [px (+ (:x parent) (:w parent))
        py (:y parent)
        cx (:x child)
        cy (:y child)
        mx (+ px (/ (- cx px) 2))]
    (str "M " px " " py " H " mx " V " cy " H " cx)))

;; --- components -------------------------------------------------------------

(rum/defc mindmap-node
  [id {:keys [x y w content collapsed? has-children?]} root?]
  (let [cx (+ x (/ w 2))
        toggle? (and has-children? (not root?))
        on-click (fn [e]
                   (util/stop e)
                   (when toggle?
                     (if collapsed?
                       (editor-handler/expand-block! id)
                       (editor-handler/collapse-block! id))))]
    [:g.mindmap-node
     {:on-click on-click
      :style {:cursor (when toggle? "pointer")}}
     [:rect {:x x :y (- y (/ node-height 2)) :width w :height node-height :rx 9
             :class (if root? "mindmap-root-rect" "mindmap-node-rect")}]
     [:text {:x cx :y y :dy "0.35em" :text-anchor "middle"
             :class (if root? "mindmap-root-text" "mindmap-node-text")}
      content]
     (when toggle?
       (let [bx (+ x w) by y]
         [:<>
          [:circle {:cx bx :cy by :r 10 :class "mindmap-collapse-badge"}]
          [:text {:x bx :y by :dy "0.35em" :text-anchor "middle" :class "mindmap-collapse-badge-text"}
           (if collapsed? "+" "−")]]))]))

(rum/defc mindmap-page < rum/reactive db-mixins/query
  [page-name]
  (let [repo (state/get-current-repo)
        page (db/entity [:block/name (util/page-name-sanity-lc page-name)])]
    (if-not page
      [:div.mindmap-scroll]
      (let [blocks (db/get-paginated-blocks repo (:db/id page))
            branches (build-branches page (or blocks []))
            root {:content (central-topic-title page) :block/children branches}
            {:keys [positions width height]} (layout-tree root)
            edges (layout-edges root)]
        [:div.absolute.w-full.h-full.mindmap-scroll
         [:svg.mindmap {:width width :height height}
          [:g.mindmap-edges
           (for [[pid cid] edges
                 :let [p (get positions pid)
                       c (get positions cid)]
                 :when (and p c)]
             [:path {:key (str pid "-" cid) :class "mindmap-edge" :d (edge-path p c)}])]
          [:g.mindmap-nodes
           (for [[id n] positions]
             (mindmap-node id n (= id ::root)))]]]))))

(rum/defc mindmap-route
  [route-match]
  (let [name (get-in route-match [:parameters :path :name])]
    (mindmap-page name)))

;; --- dashboard ---------------------------------------------------------------

(defn- mindmap-display-name [page-name]
  (let [page (db/entity [:block/name (util/page-name-sanity-lc page-name)])]
    (or (get-in page [:block/properties :title])
        (:block/original-name page)
        page-name)))

(defn- mindmap-human-update-time [page-name]
  (let [{:block/keys [updated-at created-at]}
        (db/entity [:block/name (util/page-name-sanity-lc page-name)])]
    (str (if (= created-at updated-at)
           (t :mindmap/dashboard-card-created)
           (t :mindmap/dashboard-card-edited))
         (util/time-ago (js/Date. updated-at)))))

(rum/defc mindmap-preview < rum/reactive db-mixins/query
  [page-name]
  (let [repo (state/get-current-repo)
        page (db/entity [:block/name (util/page-name-sanity-lc page-name)])]
    (when page
      (let [blocks (db/get-paginated-blocks repo (:db/id page))
            branches (build-branches page (or blocks []))
            root {:content (central-topic-title page) :block/children branches}
            {:keys [positions width height]} (layout-tree root)
            edges (layout-edges root)]
        [:svg.mindmap-preview-svg {:viewBox (str "0 0 " width " " height)
                                   :width "100%" :height "100%"
                                   :preserveAspectRatio "xMidYMid meet"}
         [:g
          (for [[pid cid] edges
                :let [p (get positions pid)
                      c (get positions cid)]
                :when (and p c)]
            [:path {:key (str pid "-" cid) :class "mindmap-edge" :d (edge-path p c)}])
          (for [[id {:keys [x y w content]}] positions]
            [:g {:key (str id)}
             [:rect {:x x :y (- y (/ node-height 2)) :width w :height node-height :rx 9
                     :class (if (= id ::root) "mindmap-root-rect" "mindmap-node-rect")}]
             [:text {:x (+ x (/ w 2)) :y y :dy "0.35em" :text-anchor "middle"
                     :class (if (= id ::root) "mindmap-root-text" "mindmap-node-text")}
              content]])]]))))

(rum/defc mindmap-dashboard-card
  [page-name {:keys [checked on-checked-change show-checked?]}]
  [:div.dashboard-card.dashboard-preview-card.cursor-pointer.hover:shadow-lg
   {:data-checked checked
    :style {:filter (if (and show-checked? (not checked)) "opacity(0.5)" "none")}
    :on-click
    (fn [e]
      (util/stop e)
      (if show-checked?
        (on-checked-change (not checked))
        (route-handler/redirect-to-mindmap! page-name)))}
   [:div.dashboard-card-title
    [:div.flex.w-full.items-center
     [:div.dashboard-card-title-name.font-bold
      (if (model/untitled-page? page-name)
        [:span.opacity-50 (t :untitled)]
        (mindmap-display-name page-name))]
     [:div.flex-1]
     [:div.dashboard-card-checkbox
      {:tab-index -1
       :style {:visibility (when show-checked? "visible")}
       :on-click util/stop-propagation}
      (ui/checkbox {:checked checked
                    :on-change (fn [] (on-checked-change (not checked)))})]]
    [:div.flex.w-full.opacity-50
     [:div (mindmap-human-update-time page-name)]]]
   [:div.p-4.h-64.flex.justify-center
    (mindmap-preview page-name)]])

(rum/defc mindmap-dashboard
  []
  (let [mindmaps (->> (model/get-all-mindmaps (state/get-current-repo))
                      (sort-by :block/updated-at)
                      reverse)
        mindmap-names (map :block/name mindmaps)
        [ref rect] (use-bounding-client-rect)
        container-width (some-> rect .-width)
        cols (cond (< container-width 600) 1
                   (< container-width 900) 2
                   (< container-width 1200) 3
                   :else 4)
        total-mindmaps (count mindmaps)
        empty-cards (- (max (* (math/ceil (/ (+ 2 total-mindmaps) cols)) cols) (* 2 cols))
                       (+ 2 total-mindmaps))
        [checked-page-names set-checked-page-names] (rum/use-state #{})
        has-checked? (not-empty checked-page-names)]
    [:<>
     [:h1.select-none.flex.items-center.whiteboard-dashboard-title.title
      [:div (t :all-mindmaps)
       [:span.opacity-50 (str " · " total-mindmaps)]]
      [:div.flex-1]
      (when has-checked?
        (ui/button
         (count checked-page-names)
         {:icon "trash"
          :on-click
          (fn []
            (state/set-modal! (page/batch-delete-dialog
                               (map (fn [name]
                                      (some (fn [m] (when (= (:block/name m) name) m)) mindmaps))
                                    checked-page-names)
                               false route-handler/redirect-to-mindmap-dashboard!)))}))]
     [:div
      {:ref ref}
      [:div.gap-8.grid.grid-rows-auto
       {:style {:visibility (when (nil? container-width) "hidden")
                :grid-template-columns (str "repeat(" cols ", minmax(0, 1fr))")}}
       (when-not config/publishing?
         (whiteboard/dashboard-create-card "tl-create-mindmap"
                                           (t :mindmap/dashboard-card-new-mindmap)
                                           mindmap-handler/create-new-mindmap-and-redirect!))
       (for [mindmap-name mindmap-names]
         [:<> {:key mindmap-name}
          (mindmap-dashboard-card mindmap-name
                                  {:show-checked? has-checked?
                                   :checked (boolean (checked-page-names mindmap-name))
                                   :on-checked-change (fn [checked]
                                                        (set-checked-page-names (if checked
                                                                                  (conj checked-page-names mindmap-name)
                                                                                  (disj checked-page-names mindmap-name))))})])
       (for [n (range empty-cards)]
         [:div.dashboard-card.dashboard-bg-card {:key n}])]]]))
