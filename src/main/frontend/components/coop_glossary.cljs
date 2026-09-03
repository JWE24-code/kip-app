(ns frontend.components.coop-glossary
  "One place for the farm-metaphor folder names and their plain-language
  glosses. `term` renders a `<code>` with a hover tooltip; `legend` is the
  one-liner shown in the help panel and the coop-map."
  (:require [clojure.string :as string]))

(def glosses
  "Folder name (no slash, no dot) → what it actually is."
  {"pages"    "your notes and source documents — everything Kip reads and hatches"
   "nest"     "the cross-linked wiki Kip builds from them"
   "clucks"   "Kip's activity log"
   "roost"    "the search index — disposable, rebuilt any time"
   "henhouse" "LLM provider and skills config"})

(defn- key-for [label]
  (-> (str label) string/lower-case (string/replace #"[^a-z]" "")))

(defn term
  "A `<code>` tag for a metaphor folder name with its gloss as a tooltip.
  `(term \"pages/\")` or `(term \"pages\" \"the pages folder\")`."
  ([label] (term label label))
  ([k label]
   (let [g (get glosses (key-for k))]
     [:code (cond-> {} g (assoc :title g)) label])))

(defn legend
  "pages = … · nest = … · clucks = … — for the help panel and coop-map."
  []
  [:div.text-xs.opacity-60.leading-relaxed
   (->> ["pages" "nest" "clucks" "roost" "henhouse"]
        (map (fn [k] (str k " = " (get glosses k))))
        (string/join "  ·  "))])
