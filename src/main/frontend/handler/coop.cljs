(ns frontend.handler.coop
  "Small reads of the open coop for the first-run checklist — how many source
  files are in pages/ and how many pages exist under nest/. Cached in the app
  db under :kip/coop-counts so the Peck empty state can show what's left to do
  before Kip is useful."
  (:require [cljs-bean.core :as bean]
            [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.state :as state]
            [promesa.core :as p]))

(defn- vault-root []
  (config/get-repo-dir (state/get-current-repo)))

(defn refresh-counts!
  "Re-read pages/ and nest/ counts into :kip/coop-counts. Never throws."
  []
  (-> (ipc/ipc "wikiCoopCounts" (vault-root))
      (p/then (fn [r]
                (state/set-state! :kip/coop-counts
                                  (assoc (bean/->clj r) :loaded? true))))
      (p/catch (fn [_] nil))))
