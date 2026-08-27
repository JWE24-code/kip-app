(ns frontend.handler.mindmap
  "Keyboard-driven mindmap editing for whiteboards, layered on top of the tldraw
   App API — no changes to the tldraw state machine itself.

   `handle-key-down` is wired to the whiteboard container's `:on-key-down`
   (frontend.extensions.tldraw). It only acts when the board has exactly one
   node (a box / ellipse / polygon) selected and nothing is being edited;
   otherwise the event passes straight through to tldraw and the global
   shortcut handler untouched.

   | key            | action                                             |
   |----------------|----------------------------------------------------|
   | Tab            | add a child, bound parent -> child, edit it         |
   | Shift+Tab      | select the parent                                   |
   | Enter          | add a sibling (a child on a root), edit it          |
   | Backspace/Del  | on a childless node: delete it + its connector,     |
   |                | reselect the parent (otherwise falls through)       |
   | Arrow keys     | move the selection to the connected node in that    |
   |                | direction (children, parent, siblings)              |
   | F2             | edit the selected node                              |
   | Mod+Shift+M    | auto-arrange the whole tree into a right-growing map |"
  (:require [datascript.core :as d]
            [frontend.state :as state]
            [frontend.util :as util]
            [goog.object :as gobj]))

;; Horizontal gap from a parent's right edge to its children's left edge.
(def ^:private level-gap-x 96)
;; Vertical gap between adjacent sibling subtrees.
(def ^:private sibling-gap-y 28)
;; Vertical gap between separate root trees when auto-arranging.
(def ^:private root-gap-y 72)
;; Vertical gap used when dropping a freshly-added node below its siblings.
(def ^:private new-node-gap-y 24)

(def ^:private node-types #{"box" "ellipse" "polygon"})

;; ---------------------------------------------------------------------------
;; reading the board
;; ---------------------------------------------------------------------------

(defn- current-app [] (state/active-tldraw-app))
(defn- app-api [^js a] (gobj/get a "api"))

(defn- node-shape?
  [^js s]
  (contains? node-types (.. s -props -type)))

(defn- selected-node
  "The single selected node shape, or nil — also nil while a shape is being
   edited or when the selection isn't exactly one node."
  [^js a]
  (when (and a (not (.-editingShape a)))
    (let [sel (.-selectedShapesArray a)]
      (when (and sel (= 1 (alength sel)) (node-shape? (aget sel 0)))
        (aget sel 0)))))

(defn- shape-xy [^js s]
  (let [p (.. s -props -point)] [(aget p 0) (aget p 1)]))

(defn- shape-wh [^js s]
  (let [z (.. s -props -size)] [(aget z 0) (aget z 1)]))

(defn- shape-center [^js s]
  (let [c (.-center s)] [(aget c 0) (aget c 1)]))

(defn- board-graph
  "Directed parent -> child graph of the current page's nodes.
   {:nodes {id shape}, :children {id [child-id ...]}, :parent {child-id parent-id}}"
  [^js a]
  (let [^js page (.-currentPage a)
        shapes (.-shapes page)
        bindings (.-bindings page)
        nodes (persistent!
               (areduce shapes i m (transient {})
                        (let [^js s (aget shapes i)]
                          (if (node-shape? s) (assoc! m (.-id s) s) m))))
        node-for-binding (fn [bid]
                           (when bid
                             (when-let [b (gobj/get bindings bid)]
                               (let [to (gobj/get b "toId")]
                                 (when (contains? nodes to) to)))))
        edges (persistent!
               (areduce shapes i acc (transient [])
                        (let [^js s (aget shapes i)]
                          (if (not= "line" (.. s -props -type))
                            acc
                            (let [h (.. s -props -handles)
                                  a-id (node-for-binding (some-> h .-start .-bindingId))
                                  b-id (node-for-binding (some-> h .-end .-bindingId))]
                              (if (or (nil? a-id) (nil? b-id) (= a-id b-id))
                                acc
                                (let [decs (.. s -props -decorations)
                                      end-arrow? (and decs (= "arrow" (gobj/get decs "end")))
                                      start-arrow? (and decs (= "arrow" (gobj/get decs "start")))]
                                  (conj! acc
                                         (cond
                                           (and end-arrow? (not start-arrow?)) [a-id b-id]
                                           (and start-arrow? (not end-arrow?)) [b-id a-id]
                                           ;; undirected: the left / upper node is the parent
                                           :else
                                           (let [[ax ay] (shape-xy (nodes a-id))
                                                 [bx by] (shape-xy (nodes b-id))]
                                             (if (or (< ax bx) (and (== ax bx) (<= ay by)))
                                               [a-id b-id] [b-id a-id])))))))))))
        children (reduce (fn [m [p c]] (update m p (fnil conj []) c)) {} edges)
        parent (reduce (fn [m [p c]] (cond-> m (not (contains? m c)) (assoc c p))) {} edges)]
    {:nodes nodes :children children :parent parent}))

(defn- children-of [g id] (get-in g [:children id] []))
(defn- parent-of [g id] (get-in g [:parent id]))

(defn- lowest-edge
  "Largest maxY across `ids` (their visual bottom), or nil when empty."
  [g ids]
  (when (seq ids)
    (reduce max (map (fn [id]
                       (let [^js s (get-in g [:nodes id])]
                         (.. s -bounds -maxY)))
                     ids))))

;; ---------------------------------------------------------------------------
;; creating nodes
;; ---------------------------------------------------------------------------

(defn- node-model
  "A fresh node shape modelled on `src` (same type / size / style), at `xy`."
  [^js src [x y]]
  (js/Object.assign #js {} (.-serialized src)
                    #js {:id (str (d/squuid))
                         :type (.. src -props -type)
                         :point #js [x y]
                         :label ""
                         :nonce (js/Date.now)
                         :refs #js []}))

(defn- add-node!
  "Create a node modelled on `parent-shape` at `xy`, bind parent -> new (arrow),
   then select and start editing the new node. One undo step (mirrors the
   built-in clone-node shortcut)."
  [^js a ^js the-api ^js parent-shape xy]
  (let [^js hist (.-history a)
        model (node-model parent-shape xy)
        new-id (gobj/get model "id")]
    (.pause hist)
    (.addShapes (.-currentPage a) model)
    (.createNewLineBinding the-api (.-id parent-shape) new-id)
    (.resume hist)
    (.persist a)
    (.selectShapes the-api new-id)
    (js/setTimeout
     (fn [] (when-let [^js s (.getShapeById a new-id)] (.editShape the-api s)))
     0)
    new-id))

(defn- add-child! [^js a]
  (when-let [^js sel (selected-node a)]
    (let [g (board-graph a)
          id (.-id sel)
          ^js b (.-bounds sel)
          kids (children-of g id)
          y (if-let [bottom (lowest-edge g kids)]
              (+ bottom new-node-gap-y)
              (.-minY b))]
      (add-node! a (app-api a) sel [(+ (.-maxX b) level-gap-x) y])
      true)))

(defn- add-sibling! [^js a]
  (when-let [^js sel (selected-node a)]
    (let [g (board-graph a)
          id (.-id sel)
          pid (parent-of g id)]
      (if-not pid
        ;; a root has no siblings — Enter behaves like Tab
        (add-child! a)
        (let [^js parent (get-in g [:nodes pid])
              ^js pb (.-bounds parent)
              sibs (children-of g pid)
              bottom (or (lowest-edge g sibs) (.. ^js sel -bounds -maxY))]
          ;; bind from the *parent*, so the new node is a sibling of `sel`
          (add-node! a (app-api a) parent [(+ (.-maxX pb) level-gap-x) (+ bottom new-node-gap-y)])
          true)))))

(defn- edit-selected! [^js a]
  (when-let [^js sel (selected-node a)]
    (.editShape (app-api a) sel)
    true))

(defn- delete-node! [^js a]
  (when-let [^js sel (selected-node a)]
    (let [g (board-graph a)
          id (.-id sel)
          pid (parent-of g id)]
      ;; only take over the delete for a childless node with a parent; anything
      ;; else falls through to the normal "delete selection" shortcut
      (when (and pid (empty? (children-of g id)))
        (.deleteShapes (app-api a) id) ;; also removes the dangling connector
        (.selectShapes (app-api a) pid)
        true))))

;; ---------------------------------------------------------------------------
;; navigation
;; ---------------------------------------------------------------------------

(defn- navigate! [^js a dir]
  (when-let [^js sel (selected-node a)]
    (let [g (board-graph a)
          id (.-id sel)
          [cx cy] (shape-center sel)
          pid (parent-of g id)
          candidates (distinct (concat (children-of g id)
                                       (when pid [pid])
                                       (when pid (remove #(= % id) (children-of g pid)))))
          scored (keep (fn [cid]
                         (when-let [^js s (get-in g [:nodes cid])]
                           (let [[x y] (shape-center s)
                                 dx (- x cx) dy (- y cy)
                                 score (case dir
                                         :right (when (> dx 1) (- dx (js/Math.abs dy)))
                                         :left (when (< dx -1) (- (- dx) (js/Math.abs dy)))
                                         :down (when (> dy 1) (- dy (js/Math.abs dx)))
                                         :up (when (< dy -1) (- (- dy) (js/Math.abs dx))))]
                             (when score [score cid]))))
                       candidates)]
      (when (seq scored)
        (.selectShapes (app-api a) (second (apply max-key first scored)))
        true))))

;; ---------------------------------------------------------------------------
;; auto-arrange
;; ---------------------------------------------------------------------------

(defn- band-height
  "Total vertical space a node's subtree needs."
  [g id seen]
  (if (contains? seen id)
    0
    (let [seen (conj seen id)
          [_ h] (shape-wh (get-in g [:nodes id]))
          kids (remove seen (children-of g id))]
      (if (empty? kids)
        h
        (max h (+ (reduce + (map #(band-height g % seen) kids))
                  (* (dec (count kids)) sibling-gap-y)))))))

(defn- layout-tree
  "Seq of {:id :typ :point} placing `id` and its subtree. `x` = the node's
   left edge, `top` = the top of its band."
  [g id x top seen]
  (if (contains? seen id)
    nil
    (let [seen (conj seen id)
          ^js s (get-in g [:nodes id])
          [w h] (shape-wh s)
          typ (.. s -props -type)
          kids (remove seen (children-of g id))]
      (if (empty? kids)
        [{:id id :typ typ :point [x top]}]
        (let [child-x (+ x w level-gap-x)
              heights (mapv #(band-height g % seen) kids)
              total (+ (reduce + heights) (* (dec (count kids)) sibling-gap-y))
              node-y (+ top (/ (- total h) 2))]
          (loop [ks kids
                 hs heights
                 cy top
                 acc [{:id id :typ typ :point [x node-y]}]]
            (if (empty? ks)
              acc
              (recur (rest ks) (rest hs) (+ cy (first hs) sibling-gap-y)
                     (into acc (layout-tree g (first ks) child-x cy seen))))))))))

(defn- arrange! [^js a]
  (let [g (board-graph a)
        {:keys [nodes parent]} g
        roots (->> (keys nodes)
                   (remove #(contains? parent %))
                   (filter #(seq (children-of g %)))
                   (sort-by #(second (shape-xy (nodes %)))))]
    (when (seq roots)
      (let [[ox oy] (shape-xy (nodes (first roots)))
            updates (loop [rs roots, top oy, acc []]
                      (if (empty? rs)
                        acc
                        (recur (rest rs)
                               (+ top (band-height g (first rs) #{}) root-gap-y)
                               (into acc (layout-tree g (first rs) ox top #{})))))]
        (when (seq updates)
          (apply (.-updateShapes (app-api a))
                 (map (fn [{:keys [id typ point]}]
                        #js {:id id :type typ :point #js [(first point) (second point)]})
                      updates))
          (.persist a)
          true)))))

;; ---------------------------------------------------------------------------
;; dispatch
;; ---------------------------------------------------------------------------

(defn handle-key-down
  "Whiteboard `:on-key-down` hook. Returns true when the key was handled as a
   mindmap action (and stops the event); returns nil to let it pass through."
  [^js e]
  (when-let [^js a (current-app)]
    (when (selected-node a)
      (let [k (.-key e)
            shift? (.-shiftKey e)
            mod? (if util/mac? (.-metaKey e) (.-ctrlKey e))
            alt? (.-altKey e)
            handled?
            (cond
              (and mod? shift? (or (= k "m") (= k "M"))) (arrange! a)
              (or mod? alt?) nil ;; leave other modifier combos to their owners
              (= k "Tab") (if shift? (navigate! a :left) (add-child! a))
              (= k "Enter") (add-sibling! a)
              (or (= k "Backspace") (= k "Delete")) (delete-node! a)
              (= k "ArrowRight") (or (navigate! a :right) true)
              (= k "ArrowLeft") (or (navigate! a :left) true)
              (= k "ArrowUp") (or (navigate! a :up) true)
              (= k "ArrowDown") (or (navigate! a :down) true)
              (= k "F2") (edit-selected! a)
              :else nil)]
        (when handled?
          (.preventDefault e)
          (.stopPropagation e)
          (when-let [ne (.-nativeEvent e)] (.stopPropagation ne))
          true)))))
