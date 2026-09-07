(ns frontend.components.mindmap
  "Mindmap tree renderer: renders a `:block/type \"mindmap\"` page as a clean,
   auto-laid-out, right-growing tree over the page's outline. The page title is
   the central topic, top-level blocks are branches, indentation is depth."
  (:require [cljs.math :as math]
            [clojure.string :as string]
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
            [frontend.util.marker :as marker]
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

;; --- styling vocabulary ---------------------------------------------------

(def ^:private palette
  "Per-topic colours a user can pick. Stored by name as a `mindmap-color::`
   block property; rendered via `[data-mm-color]` in the stylesheet."
  ["red" "orange" "yellow" "green" "blue" "purple" "gray"])

(def ^:private shapes
  "Node outline shapes, cycled with the toolbar; stored as `mindmap-shape::`."
  ["rounded" "rectangle" "pill"])

(def ^:private themes
  "Map-wide presets, stored as a `mindmap-theme::` page property."
  ["default" "blueprint" "forest" "sunset" "mono"])

(defn- next-shape [shape]
  (let [i (or (->> shapes (keep-indexed (fn [i s] (when (= s shape) i))) first) 0)]
    (nth shapes (mod (inc i) (count shapes)))))

(defn- node-url [s]
  (some-> (re-find #"https?://[^\s<>()\[\]]+" (str s))
          (string/replace #"[.,;:!?]+$" "")))

(defn- clean-title
  "Strips the task marker, priority cookie and Markdown link syntax off the
   first line so a node shows just its text; markers/links get their own chips."
  [edit-text]
  (let [s (-> (str (or edit-text ""))
              string/split-lines first str
              (string/replace marker/bare-marker-pattern "")
              (string/replace #"^\s*\[#[A-Za-z]\]\s*" "")
              (string/replace #"\[([^\]]+)\]\((?:[^)]+)\)" "$1")
              string/trim)]
    (if (string/blank? s) (string/trim (str (or edit-text ""))) s)))

(defn- topic-data
  "View-model for one node, derived from its block."
  [node]
  (if (:content node)
    {:title (:content node) :edit-text (:content node)}
    (let [props (:block/properties node)
          edit-text (mindmap-handler/editable-text node)
          marker' (:block/marker node)]
      {:edit-text edit-text
       :title (clean-title edit-text)
       :marker marker'
       :priority (:block/priority node)
       :done? (= "DONE" marker')
       :scheduled? (boolean (or (:block/scheduled node)
                                (:block/deadline node)
                                (:reminder props)))
       :color (some-> (:mindmap-color props) str string/lower-case not-empty)
       :shape (some-> (:mindmap-shape props) str string/lower-case not-empty)
       :note (some-> (or (get (:block/properties-text-values node) :mindmap-note)
                         (:mindmap-note props))
                     str not-empty)
       :url (node-url edit-text)})))

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
                    {:keys [title] :as data} (topic-data node)
                    ;; leave room for a marker / link / note chip
                    extra (+ (if (:marker data) 34 0)
                             (if (or (:url data) (:note data) (:scheduled? data)) 20 0))
                    w (min max-node-width (+ (node-width title) extra))
                    cy (+ top (/ h 2))
                    x (+ margin (* depth level-x))]
                (vswap! max-depth max depth)
                (vswap! positions assoc id
                        (merge data
                               {:x x :y cy :w w
                                :content (display-content title)
                                :collapsed? (boolean (util/collapsed? node))
                                :has-children? (boolean (or (seq (block-children node))
                                                            (util/collapsed? node)))}))
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

;; --- navigation over the visible tree --------------------------------------

(defn- node-id [node]
  (or (:block/uuid node) ::root))

(defn- build-nav
  "Flattens the visible tree into the structures the keyboard/toolbar edits need:
     :order    ids in top-to-bottom visual order
     :idx      id -> position in :order
     :parent   id -> parent id
     :children id -> vector of visible child ids
     :nodes    id -> node (for collapsed?/expand checks)"
  [root]
  (let [order    (volatile! (transient []))
        parent   (volatile! (transient {}))
        children (volatile! (transient {}))
        nodes    (volatile! (transient {}))]
    (letfn [(walk [node pid]
              (let [id (node-id node)
                    ch (visible-children node)]
                (vswap! order conj! id)
                (vswap! nodes assoc! id node)
                (when pid (vswap! parent assoc! id pid))
                (vswap! children assoc! id (mapv node-id ch))
                (doseq [c ch] (walk c id))))]
      (walk root nil))
    (let [order (persistent! @order)]
      {:order order
       :idx (zipmap order (range))
       :parent (persistent! @parent)
       :children (persistent! @children)
       :nodes (persistent! @nodes)})))

;; --- components -----------------------------------------------------------

(defn- node-box-style [{:keys [x y w]}]
  {:left (str x "px")
   :top (str y "px")
   :width (str w "px")
   :height (str node-height "px")})

(defn- theme-name [theme]
  (let [t' (some-> theme str string/lower-case)]
    (if (contains? (set themes) t') t' "default")))

(rum/defcs mindmap-notes-panel <
  (rum/local nil ::text)
  {:will-mount (fn [state]
                 (reset! (::text state) (:initial (last (:rum/args state))))
                 state)}
  "Side panel holding a long per-topic description, persisted as the
   `mindmap-note::` block property. Wrapped with `rum/with-key` on the topic id
   so it re-initialises when the selection changes."
  [state {:keys [title initial on-save on-close]}]
  (let [*text (::text state)
        value (or @*text initial "")
        commit! (fn [] (when (not= (str value) (str initial)) (on-save value)))]
    [:div.mindmap-notes-panel
     [:div.mindmap-notes-head
      [:span.mindmap-notes-title title]
      [:button.mindmap-toolbar-btn
       {:title (t :cancel) :on-click (fn [e] (util/stop e) (on-close))}
       (ui/icon "x")]]
     [:textarea.mindmap-notes-text
      {:value value
       :placeholder (t :mindmap/note-placeholder)
       :auto-focus true
       :on-change (fn [e] (reset! *text (.. e -target -value)))
       :on-blur (fn [_e] (commit!))}]]))

(rum/defc mindmap-canvas
  "Interactive mindmap surface. Holds the transient view state (selection, the
   node being text-edited, the current drag target); every actual mutation goes
   through `frontend.handler.mindmap` so it lands on the outline blocks and is
   undoable."
  [{:keys [page-name page-uuid positions edges nav width height empty? theme]}]
  (let [{:keys [order idx parent children nodes]} nav
        [selected set-selected!] (rum/use-state ::root)
        [editing set-editing!]   (rum/use-state nil)
        [draft set-draft!]       (rum/use-state "")
        [drop-target set-drop-target!] (rum/use-state nil)
        [notes-open? set-notes-open!] (rum/use-state false)
        *canvas   (rum/use-ref nil)
        *textarea (rum/use-ref nil)
        *drag-id  (rum/use-ref nil)
        *cancel   (rum/use-ref nil)
        theme     (theme-name theme)
        valid?    (set order)
        selected  (if (valid? selected) selected ::root)
        sel-pos   (get positions selected)
        real-sel? (and (not= selected ::root) sel-pos)
        focus-canvas! (fn [] (some-> (rum/deref *canvas) (.focus)))
        select!   (fn [id] (set-selected! id) (focus-canvas!))
        start-edit! (fn [id]
                      (when (and id (not= id ::root))
                        (set-draft! (str (:edit-text (get positions id))))
                        (rum/set-ref! *cancel nil)
                        (set-editing! id)))
        stop-edit! (fn [] (set-editing! nil) (focus-canvas!))
        set-color!   (fn [id c] (when (not= id ::root)
                                  (mindmap-handler/set-topic-property! id :mindmap-color c)
                                  (focus-canvas!)))
        cycle-shape! (fn [id] (when (not= id ::root)
                                (mindmap-handler/set-topic-property!
                                 id :mindmap-shape (next-shape (:shape (get positions id))))
                                (focus-canvas!)))
        toggle-marker! (fn [id] (when (not= id ::root)
                                  (mindmap-handler/cycle-topic-marker! id)
                                  (focus-canvas!)))
        set-note!    (fn [id note]
                       ;; block properties are single-line — fold hard breaks to spaces
                       (when (not= id ::root)
                         (mindmap-handler/set-topic-property!
                          id :mindmap-note (some-> note not-empty (string/replace #"\s*\n\s*" " ")))))
        open-url!    (fn [url] (when url (util/open-url url)))
        add-sibling! (fn [id]
                       (when (not= id ::root)
                         (when-let [nid (mindmap-handler/add-topic! id {:sibling? true})]
                           (set-selected! nid)
                           (start-edit! nid))))
        add-child! (fn [id]
                     (when-let [nid (if (= id ::root)
                                      (mindmap-handler/add-root-topic! page-name)
                                      (mindmap-handler/add-topic! id {:sibling? false}))]
                       (set-selected! nid)
                       (start-edit! nid)))
        delete! (fn [id]
                  (when (and (not= id ::root) (valid? id))
                    (mindmap-handler/delete-topic! id)
                    (select! (get parent id ::root))))
        outdent! (fn [id]
                   (when (and (not= id ::root) (get parent id))
                     (mindmap-handler/outdent-topic! id)))
        toggle-collapse! (fn [id]
                           (let [node (get nodes id)]
                             (if (util/collapsed? node)
                               (editor-handler/expand-block! id)
                               (editor-handler/collapse-block! id))))
        canvas-key-down
        (fn [e]
          (when-not editing
            (let [k (.-key e) shift? (.-shiftKey e)
                  mod? (or (.-metaKey e) (.-ctrlKey e) (.-altKey e))
                  id selected
                  i (get idx id 0)]
              (case k
                "ArrowUp"    (do (.preventDefault e) (when (pos? i) (select! (get order (dec i)))))
                "ArrowDown"  (do (.preventDefault e) (when (< i (dec (count order))) (select! (get order (inc i)))))
                "ArrowLeft"  (do (.preventDefault e) (when-let [p (get parent id)] (select! p)))
                "ArrowRight" (do (.preventDefault e)
                                 (if (util/collapsed? (get nodes id))
                                   (editor-handler/expand-block! id)
                                   (when-let [c (first (get children id))] (select! c))))
                "Enter"      (do (.preventDefault e) (if (= id ::root) (add-child! id) (add-sibling! id)))
                "Tab"        (do (.preventDefault e) (if shift? (outdent! id) (add-child! id)))
                ("Delete" "Backspace") (do (.preventDefault e) (delete! id))
                "F2"         (do (.preventDefault e) (start-edit! id))
                " "          (when (and (not mod?) (not= id ::root)
                                        (get-in positions [id :has-children?]))
                               (.preventDefault e) (toggle-collapse! id))
                nil))))
        edit-key-down
        (fn [e]
          (let [k (.-key e) shift? (.-shiftKey e)
                id editing v (.. e -target -value)]
            (case k
              "Escape" (do (.preventDefault e) (.stopPropagation e)
                           (rum/set-ref! *cancel id) (stop-edit!))
              "Enter"  (when-not shift?
                         (.preventDefault e) (.stopPropagation e)
                         (mindmap-handler/set-topic-content! id v)
                         (set-editing! nil)
                         (add-sibling! id))
              "Tab"    (do (.preventDefault e) (.stopPropagation e)
                           (mindmap-handler/set-topic-content! id v)
                           (set-editing! nil)
                           (if shift?
                             (do (outdent! id) (select! id))
                             (add-child! id)))
              (.stopPropagation e))))
        edit-blur
        (fn [e]
          (let [id editing v (.. e -target -value)]
            (when-not (= (rum/deref *cancel) id)
              (mindmap-handler/set-topic-content! id v))
            (rum/set-ref! *cancel nil)
            (set-editing! (fn [cur] (if (= cur id) nil cur)))))]

    (rum/use-effect!
     (fn []
       (when editing
         (when-let [ta (rum/deref *textarea)]
           (.focus ta)
           (.select ta)))
       js/undefined)
     [editing])

    [:div.mindmap-container {:data-mm-theme theme}
     [:div.mindmap-toolbar
      [:div.mindmap-toolbar-group
       [:button.mindmap-toolbar-btn
        {:title (t :mindmap/edit-outline)
         :on-click (fn [e] (util/stop e) (route-handler/redirect-to-page! page-name))}
        (ui/icon "list")]]
      [:div.mindmap-toolbar-group
       [:button.mindmap-toolbar-btn
        {:title (t :mindmap/add-sibling) :disabled (= selected ::root)
         :on-click (fn [e] (util/stop e) (add-sibling! selected))}
        (ui/icon "plus")]
       [:button.mindmap-toolbar-btn
        {:title (t :mindmap/add-child)
         :on-click (fn [e] (util/stop e) (add-child! selected))}
        (ui/icon "subtask")]
       [:button.mindmap-toolbar-btn
        {:title (t :mindmap/outdent) :disabled (or (= selected ::root) (nil? (get parent selected)))
         :on-click (fn [e] (util/stop e) (outdent! selected))}
        (ui/icon "indent-decrease")]
       [:button.mindmap-toolbar-btn.mindmap-toolbar-btn--danger
        {:title (t :mindmap/delete) :disabled (= selected ::root)
         :on-click (fn [e] (util/stop e) (delete! selected))}
        (ui/icon "trash")]]
      [:div.mindmap-toolbar-group
       [:button.mindmap-toolbar-btn
        {:title (t :mindmap/task) :disabled (not real-sel?)
         :class (when (:marker sel-pos) "is-active")
         :on-click (fn [e] (util/stop e) (toggle-marker! selected))}
        (ui/icon "checkbox")]
       [:button.mindmap-toolbar-btn
        {:title (t :mindmap/shape) :disabled (not real-sel?)
         :on-click (fn [e] (util/stop e) (cycle-shape! selected))}
        (ui/icon "shape")]
       (for [c palette]
         [:button.mindmap-swatch
          {:key c :title c :data-mm-color c :disabled (not real-sel?)
           :class (when (= c (:color sel-pos)) "is-active")
           :on-click (fn [e] (util/stop e)
                       (set-color! selected (when (not= c (:color sel-pos)) c)))}])
       [:button.mindmap-toolbar-btn
        {:title (t :mindmap/note) :disabled (not real-sel?)
         :class (when notes-open? "is-active")
         :on-click (fn [e] (util/stop e) (set-notes-open! not))}
        (ui/icon "notes")]]
      [:div.mindmap-toolbar-group
       [:select.mindmap-theme-select
        {:value theme :title (t :mindmap/theme)
         :on-click util/stop-propagation
         :on-change (fn [e]
                      (mindmap-handler/set-map-theme! page-name (.. e -target -value))
                      (focus-canvas!))}
        (for [th themes]
          [:option {:key th :value th} (string/capitalize th)])]]]

     (when (and notes-open? real-sel?)
       (rum/with-key
         (mindmap-notes-panel {:title (:title sel-pos)
                               :initial (str (or (:note sel-pos) ""))
                               :on-save (fn [v] (set-note! selected v))
                               :on-close (fn [] (set-notes-open! false) (focus-canvas!))})
         (str selected)))

     [:div.mindmap-scroll
      {:ref *canvas :tab-index 0 :on-key-down canvas-key-down
       :on-click (fn [_e] (set-selected! ::root))}
      [:div.mindmap-canvas {:style {:width (str width "px") :height (str height "px")}}
       [:svg.mindmap-edges-svg {:width width :height height}
        (for [[pid cid] edges
              :let [p (get positions pid) c (get positions cid)]
              :when (and p c)]
          [:path {:key (str pid "-" cid) :class "mindmap-edge" :d (edge-path p c)}])]
       (when empty?
         [:div.mindmap-empty-hint
          {:style {:left (str (+ (get-in positions [::root :x])
                                 (get-in positions [::root :w]) 40) "px")
                   :top (str (get-in positions [::root :y]) "px")}}
          (t :mindmap/empty-hint)])
       (for [[id {:keys [content collapsed? has-children? marker priority done?
                         scheduled? url note color shape] :as pos}] positions
             :let [root? (= id ::root)
                   editing? (= editing id)]]
         [:div {:key (str id)
                :class (str "mindmap-node"
                            (when root? " mindmap-node--root")
                            (when done? " mindmap-node--done")
                            (when (= selected id) " mindmap-node--selected")
                            (when (= drop-target id) " mindmap-node--drop"))
                :data-mm-color (when (and color (not root?)) color)
                :data-mm-shape (when (and shape (not root?)) shape)
                :style (node-box-style pos)
                :draggable (and (not root?) (not editing?))
                :on-click (fn [e] (util/stop e) (select! id))
                :on-double-click (fn [e] (util/stop e) (start-edit! id))
                :on-drag-start (fn [e]
                                 (rum/set-ref! *drag-id id)
                                 (set! (.. e -dataTransfer -effectAllowed) "move")
                                 (.setData (.-dataTransfer e) "text/plain" (str id)))
                :on-drag-end (fn [_e] (rum/set-ref! *drag-id nil) (set-drop-target! nil))
                :on-drag-over (fn [e]
                                (when-let [src (rum/deref *drag-id)]
                                  (when (not= src id)
                                    (.preventDefault e)
                                    (set-drop-target! id))))
                :on-drag-leave (fn [_e] (set-drop-target! (fn [cur] (if (= cur id) nil cur))))
                :on-drop (fn [e]
                           (.preventDefault e)
                           (let [src (rum/deref *drag-id)]
                             (when (and src (not= src id))
                               (mindmap-handler/reparent-topic!
                                src (if root? page-uuid id) page-uuid)
                               (set-selected! src)))
                           (set-drop-target! nil)
                           (rum/set-ref! *drag-id nil))}
          (if editing?
            [:textarea.mindmap-node-input
             {:ref *textarea
              :value draft
              :rows 1
              :on-change (fn [e] (set-draft! (.. e -target -value)))
              :on-key-down edit-key-down
              :on-blur edit-blur
              :on-click util/stop-propagation
              :on-double-click util/stop-propagation}]
            [:<>
             (when (and marker (not root?))
               [:span.mindmap-node-marker
                {:data-marker marker
                 :on-click (fn [e] (util/stop e) (toggle-marker! id))}
                marker])
             (when (and priority (not root?))
               [:span.mindmap-node-priority (str "[#" priority "]")])
             [:span.mindmap-node-label content]
             (when (and scheduled? (not root?)) [:span.mindmap-node-icon (ui/icon "clock")])
             (when (and note (not root?))
               [:span.mindmap-node-icon.mindmap-node-icon--note
                {:on-click (fn [e] (util/stop e) (select! id) (set-notes-open! true))}
                (ui/icon "notes")])
             (when (and url (not root?))
               [:span.mindmap-node-icon.mindmap-node-icon--link
                {:on-click (fn [e] (util/stop e) (open-url! url))}
                (ui/icon "external-link")])])
          (when (and has-children? (not root?) (not editing?))
            [:button.mindmap-node-badge
             {:title (if collapsed? "Expand" "Collapse")
              :on-click (fn [e] (util/stop e) (toggle-collapse! id))}
             (if collapsed? "+" "−")])])]]]))

(rum/defc mindmap-page < rum/reactive db-mixins/query
  [page-name]
  (let [repo (state/get-current-repo)
        page (db/entity [:block/name (util/page-name-sanity-lc page-name)])]
    (if-not page
      [:div.mindmap-scroll]
      (let [blocks (db/get-paginated-blocks repo (:db/id page))
            ;; the theme lives on the page's properties block; a property set on
            ;; a pre-block doesn't propagate to the page entity, so read it back
            ;; from the same `blocks` collection that drives this reactivity
            pre-block (some #(when (pre-block? %) %) blocks)
            branches (build-branches page (or blocks []))
            root {:content (central-topic-title page) :block/children branches}
            {:keys [positions width height]} (layout-tree root)
            edges (layout-edges root)
            nav (build-nav root)]
        (mindmap-canvas {:page-name page-name
                         :page-uuid (:block/uuid page)
                         :positions positions
                         :edges edges
                         :nav nav
                         :width width
                         :height height
                         :theme (get-in pre-block [:block/properties :mindmap-theme])
                         :empty? (empty? branches)})))))

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
