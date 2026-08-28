(ns frontend.handler.update
  "Renderer side of the polite update check (electron.update). Triggers the
  check over IPC, caches the result in :kip/update, and tracks which version
  the user has dismissed the banner for."
  (:require [cljs-bean.core :as bean]
            [electron.ipc :as ipc]
            [frontend.state :as state]
            [frontend.storage :as storage]
            [frontend.util :as util]
            [promesa.core :as p]))

(def ^:private dismissed-key "kip-update-dismissed")

(defn dismissed-version []
  (storage/get dismissed-key))

(defn dismiss!
  "Hide the banner until a release newer than the current latest."
  []
  (when-let [latest (:latest (:kip/update @state/state))]
    (storage/set dismissed-key latest)
    (state/set-state! [:kip/update :dismissed?] true)))

(defn show-banner?
  "A newer version exists and the user hasn't dismissed the banner for it."
  []
  (let [{:keys [newer? latest dismissed?]} (:kip/update @state/state)]
    (and newer? (not dismissed?) (not= latest (dismissed-version)))))

(defn check!
  "Run the check (force? bypasses the 24h cache in the main process) and store
  the result. Never rejects."
  ([] (check! false))
  ([force?]
   (when (util/electron?)
     (state/set-state! [:kip/update :checking?] true)
     (-> (ipc/ipc "checkForAppUpdate" force?)
         (p/then (fn [r]
                   (let [{:keys [latest] :as result} (bean/->clj r)]
                     (state/set-state! :kip/update
                                       (assoc result
                                              :checking? false
                                              :dismissed? (= latest (dismissed-version)))))))
         (p/catch (fn [_]
                    (state/set-state! [:kip/update :checking?] false)))))))
