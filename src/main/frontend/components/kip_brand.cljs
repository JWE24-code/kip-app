(ns frontend.components.kip-brand
  "Shared bits of Kip's identity — the egg mark and the slogan — so the Peck
  empty state and the welcome card show the same thing as the website.")

(def egg-ascii
  "A small ASCII egg with the website favicon's zigzag."
  (str "   .--.\n"
       "  /    \\\n"
       " |\\/\\/\\/|\n"
       " |      |\n"
       "  \\    /\n"
       "   '--'"))

(def slogan "Don't browse your notes. Peck them. Get answers.")

(def tagline
  "The Logseq editor, plus a layer that turns documents you drop in into a
  cross-linked wiki you can ask questions of.")
