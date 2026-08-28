(ns electron.update
  "A polite in-app update check for Kip. Not the electron-updater auto-install
  path (electron.updater — disabled, needs signed binaries): this just asks
  GitHub once on launch and every 24h whether a newer release exists, and lets
  the renderer show a dismissible banner. No download, no install."
  (:require ["semver" :as semver]
            [cljs-bean.core :as bean]
            [clojure.string :as string]
            [electron.configs :as cfgs]
            [electron.logger :as logger]
            [electron.utils :as utils]
            [frontend.version :refer [version]]
            [promesa.core :as p]))

(def ^:private releases-url
  "https://api.github.com/repos/JWE24-code/kip-app/releases/latest")

(def ^:private check-interval-ms (* 24 60 60 1000))
(def ^:private timeout-ms 5000)
;; Stored in configs.edn (electron.configs) so it survives restarts.
(def ^:private cache-key :update/last-check)

(defn- strip-v [tag]
  (some-> tag (string/replace #"^[vV]" "")))

(defn- newer?
  "Is `latest-tag` (e.g. \"v0.3.1\") a higher semver than the running build?"
  [latest-tag]
  (let [l (strip-v latest-tag)]
    (boolean (and l (semver/valid l) (semver/valid version) (semver/gt l version)))))

(defn- fetch-latest! []
  (-> (utils/fetch releases-url
                   {:timeout timeout-ms
                    :headers {"Accept"     "application/vnd.github+json"
                              "User-Agent" (str "Kip/" version)}})
      (p/then (fn [^js res]
                (if (.-ok res)
                  (.json res)
                  (p/rejected (js/Error. (str "GitHub returned " (.-status res)))))))
      (p/then (fn [json]
                (let [{:keys [tag_name html_url body]} (bean/->clj json)]
                  {:latest tag_name :url html_url :notes body})))))

(defn check!
  "Resolve to a plain JS object {current, latest, url, notes, newer?} — a JS
  object, not a CLJS map, because the result crosses the ipcMain.handle
  structured-clone boundary. Uses a 24h cache in configs.edn unless `force?`.
  Any failure (offline, timeout, rate limit) resolves to {current, newer?:
  false} — a failed check is never an error the user sees."
  ([] (check! {}))
  ([{:keys [force?]}]
   (let [cached (cfgs/get-item cache-key)
         fresh? (and (map? cached)
                     (number? (:at cached))
                     (< (- (js/Date.now) (:at cached)) check-interval-ms))]
     (if (and fresh? (not force?))
       (p/resolved (clj->js (dissoc cached :at)))
       (-> (fetch-latest!)
           (p/then (fn [{:keys [latest url notes]}]
                     (let [result {:current version
                                   :latest  latest
                                   :url     url
                                   :notes   notes
                                   :newer?  (newer? latest)}]
                       (cfgs/set-item! cache-key (assoc result :at (js/Date.now)))
                       (clj->js result))))
           (p/catch (fn [err]
                      (logger/warn "[update] check failed:" (.-message err))
                      #js {:current version :newer? false})))))))

(defn start!
  "Check on launch, then every 24h; push :app-update-available to `win`
  whenever a newer version is found. Returns a teardown fn."
  [^js win]
  (let [run  #(-> (check! {})
                  (p/then (fn [^js r]
                            (when (aget r "newer?")
                              (utils/send-to-renderer win :app-update-available r)))))
        _    (run)
        id   (js/setInterval run check-interval-ms)]
    #(js/clearInterval id)))
