(ns electron.updater
  "electron-updater — downloads and installs a signed release when the user
  asks for it.

  This is the install half. The 'is there a newer version?' check that shows
  the header banner is electron.update (#9, a plain GitHub API poll). Only once
  the user clicks *Update* in that banner does anything here run:

    download-update!  -> electron-updater fetches the NSIS / AppImage from the
                         GitHub Release (progress streamed to the renderer)
    (on 'update-downloaded')  -> the `auto-updater-downloaded` IPC channel fires,
                                 the header shows *Restart to finish updating*
    quit-and-install!  -> autoUpdater.quitAndInstall

  Self-updates a NSIS install (Windows) or an AppImage (Linux) only — a
  portable tar.gz / folder unzip or a dev run has no updater, and
  download-update! rejects with a 'grab it yourself' message.

  On Linux the AppImage install lands under a new versioned name
  (AppImageUpdater.doInstall unlinks the old file first), so wire! also listens
  for 'appimage-filename-updated' and repoints launcher symlinks that
  referenced the old name (#98)."
  (:require ["fs" :as fs]
            ["path" :as node-path]
            ["electron-updater" :refer [autoUpdater]]
            [clojure.string :as string]
            [electron.logger :as logger]
            [electron.utils :as utils]
            [frontend.version :refer [version]]
            [promesa.core :as p]))

(def ^:private debug (partial logger/info "[updater]"))

(defonce ^:private *wired? (atom false))

;; core.cljs shows this in the About dialog.
(def electron-version
  (->> (string/split version #"\.") (take 3) (string/join ".")))

(defn active?
  "electron-updater can self-update this install (NSIS / AppImage). False for a
  portable unpack or a dev run."
  []
  (boolean (try (.isUpdaterActive autoUpdater)
                (catch :default _ false))))

(defn- replace-symlink!
  "Point the symlink `link` at `target`, via a same-directory temp link renamed
  over it — a crash mid-repoint then leaves the old link intact rather than no
  launcher at all. Same style (absolute vs relative) as callers pass."
  [link target]
  (let [tmp (node-path/join (node-path/dirname link)
                            (str "." (node-path/basename link) ".kip-relink"))]
    (try (fs/unlinkSync tmp) (catch :default _))
    (fs/symlinkSync target tmp)
    (fs/renameSync tmp link)))

(defn- repoint-launcher-symlinks!
  "#98: the AppImage install unlinks the old versioned file and moves the new
  build next to it under a fresh versioned name, so launcher symlinks set up
  against the old name (~/Applications/Kip.AppImage -> Kip-<v>-x86_64.AppImage,
  which Kip.desktop execs) are left dangling on every update. Retarget every
  symlink in the same directory that resolved to `old-file`, keeping each
  link's absolute/relative style."
  [old-file new-file]
  (let [dir (node-path/dirname old-file)
        names (try (seq (fs/readdirSync dir)) (catch :default _))]
    (doseq [name names
            :let [link (node-path/join dir name)]
            :when (not= link new-file)
            :let [target (try (fs/readlinkSync link) (catch :default _))]
            :when (and target (= (node-path/resolve dir target) old-file))]
      (let [new-target (if (node-path/isAbsolute target)
                         new-file
                         (node-path/relative dir new-file))]
        (try
          (replace-symlink! link new-target)
          (debug "repointed launcher symlink" link "->" new-target)
          (catch :default e
            (logger/warn "[updater]" "couldn't repoint launcher symlink" link
                         "-" (or (.-message e) e))))))))

(defn- fix-launcher-symlinks!
  "Handler for 'appimage-filename-updated', which doInstall emits right after
  the rename. The old path is still in APPIMAGE at that point — doInstall
  doesn't touch the env var. AppImage-only; no-op elsewhere."
  [new-file]
  (when-let [old-file (.-APPIMAGE js/process.env)]
    (when new-file
      (try
        (repoint-launcher-symlinks! old-file new-file)
        (catch :default e
          (logger/warn "[updater]" "launcher symlink repoint failed:"
                       (or (.-message e) e)))))))

(defn wire!
  "Idempotent. Configure electron-updater and forward its events to `win`:
  download progress on the `updater-download-progress` channel, then the
  existing `auto-updater-downloaded` channel (which the header's
  updater-tips-new-version component already listens on) when it's staged."
  [^js win]
  (when (compare-and-set! *wired? false true)
    (set! (.-autoDownload autoUpdater) false)          ; wait for the user
    (set! (.-autoInstallOnAppQuit autoUpdater) true)
    (doto autoUpdater
      (.on "error"
           (fn [err]
             (logger/warn "[updater]" "error:" (or (.-message err) err))
             (utils/send-to-renderer win "updater-error"
                                     #js {:message (str (or (.-message err) err))})))
      (.on "download-progress"
           (fn [^js p]
             (utils/send-to-renderer win "updater-download-progress"
                                     #js {:percent        (.-percent p)
                                          :transferred    (.-transferred p)
                                          :total          (.-total p)
                                          :bytesPerSecond (.-bytesPerSecond p)})))
      (.on "update-downloaded"
           (fn [^js info]
             (debug "downloaded" (.-version info))
             (utils/send-to-renderer win "auto-updater-downloaded"
                                     #js {:name    (.-version info)
                                          :version (.-version info)
                                          :notes   (.-releaseNotes info)})))
      (.on "appimage-filename-updated" fix-launcher-symlinks!))))

(defn download-update!
  "Check the feed, then download. Progress + completion arrive as the events
  wire! set up. Resolves once the download has started; rejects when this build
  can't self-update or there's nothing to get."
  []
  (if-not (active?)
    (p/rejected (js/Error. "This build updates manually — download the new release from GitHub."))
    (-> (.checkForUpdates autoUpdater)
        (p/then (fn [^js r]
                  (if (and r (.-updateInfo r))
                    (do (debug "downloading" (.. r -updateInfo -version))
                        (.downloadUpdate autoUpdater))
                    (throw (js/Error. "No update available."))))))))

(defn quit-and-install!
  "Relaunch into the installer. `false false` = not silent, do restart after."
  []
  (debug "quit-and-install")
  (.quitAndInstall autoUpdater false true))
