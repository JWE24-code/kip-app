(ns frontend.components.kip-brand
  "Shared bits of Kip's identity — the Split Roost logo and the slogan — so the
  Peck empty state and the welcome card show the same mark as the website.")

(defn egg-logo
  "The Split Roost mark (cobalt tile + white egg) at `size` px. Matches the PWA
  app icon and the website favicon."
  [size]
  [:svg {:width size :height size :viewBox "0 0 24 24" :aria-hidden "true"}
   [:rect {:width 24 :height 24 :rx 6 :fill "#0148c6"}]
   [:path {:d "M12 5.2c-2.2 0-4.6 3.4-4.6 7 0 2.8 2 4.6 4.6 4.6s4.6-1.8 4.6-4.6c0-3.6-2.4-7-4.6-7Z"
           :fill "#fff" :fillOpacity 0.92}]
   [:circle {:cx 12 :cy 12.4 :r 1.7 :fill "#0148c6"}]])

(def slogan "Don't browse your notes. Peck them. Get answers.")

(def tagline
  "The Logseq editor, plus a layer that turns documents you drop in into a
  cross-linked wiki you can ask questions of.")
