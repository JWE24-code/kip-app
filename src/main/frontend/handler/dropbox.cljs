(ns frontend.handler.dropbox
  "Renderer side of Dropbox account connection (kip-app v0.4.0). Thin wrappers
  over the :dropbox* IPC channels (electron.dropbox). The connect flow opens
  the system browser; the promise resolves once the user finishes (or cancels)
  the Dropbox consent screen.

  `:dropbox/status` in app-db mirrors the last status read, so components can
  render reactively without each holding their own copy."
  (:require [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.state :as state]
            [frontend.util :as util]
            [promesa.core :as p]))

(defn- put-status! [s]
  (state/set-state! :dropbox/status s)
  s)

(defn refresh! []
  (when (util/electron?)
    (-> (ipc/ipc "dropboxStatus")
        (p/then (fn [r] (put-status! (js->clj r :keywordize-keys true))))
        (p/catch (fn [_] (put-status! {:connected false}))))))

(defn connect! []
  (state/set-state! :dropbox/connecting? true)
  (-> (ipc/ipc "dropboxConnect")
      (p/then (fn [r] (put-status! (js->clj r :keywordize-keys true))))
      (p/catch (fn [e] (put-status! {:connected false :error (str e)})))
      (p/finally (fn [] (state/set-state! :dropbox/connecting? false)))))

(defn disconnect! []
  (-> (ipc/ipc "dropboxDisconnect")
      (p/then (fn [r] (put-status! (js->clj r :keywordize-keys true))))))

;; --- per-graph sync -------------------------------------------------------

(defn- graph-dir [] (config/get-repo-dir (state/get-current-repo)))

(defn- put-sync! [s] (state/set-state! :dropbox/sync (js->clj s :keywordize-keys true)) s)

(defn refresh-sync! []
  (when (and (util/electron?) (graph-dir))
    (-> (ipc/ipc "dropboxSyncStatus" (graph-dir))
        (p/then put-sync!)
        (p/catch (fn [_] (put-sync! #js {:synced false}))))))

(defn enable-sync! [conflict-mode]
  (state/set-state! :dropbox/sync-busy? true)
  (-> (ipc/ipc "dropboxSyncEnable" (graph-dir) {:conflict-mode (or conflict-mode "auto")})
      (p/then put-sync!)
      (p/finally (fn [] (state/set-state! :dropbox/sync-busy? false) (refresh-sync!)))))

(defn disable-sync! []
  (-> (ipc/ipc "dropboxSyncDisable" (graph-dir))
      (p/then put-sync!)))

(defn sync-now! []
  (state/set-state! :dropbox/sync-busy? true)
  (-> (ipc/ipc "dropboxSyncNow" (graph-dir))
      (p/then put-sync!)
      (p/finally (fn [] (state/set-state! :dropbox/sync-busy? false) (refresh-sync!)))))
