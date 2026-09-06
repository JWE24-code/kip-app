(ns frontend.components.mindmap
  "Mindmap related components"
  (:require [frontend.components.page :as page]
            [rum.core :as rum]))

(rum/defc mindmap-route
  [route-match]
  ;; A mindmap renders as its plain outline until the dedicated tree renderer
  ;; lands (see the renderer sub-issue); the page itself is the source of truth.
  (page/page route-match))
