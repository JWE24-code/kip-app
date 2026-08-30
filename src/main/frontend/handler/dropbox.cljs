(ns frontend.handler.dropbox
  "Renderer side of Dropbox account connection (kip-app v0.4.0). Thin wrappers
  over the :dropbox* IPC channels (electron.dropbox). The connect flow opens
  the system browser; the promise resolves once the user finishes (or cancels)
  the Dropbox consent screen.

  `:dropbox/status` in app-db mirrors the last status read, so components can
  render reactively without each holding their own copy."
  (:require [electron.ipc :as ipc]
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
