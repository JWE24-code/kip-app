(ns frontend.components.settings
  (:require [clojure.string :as string]
            [electron.ipc :as ipc]
            [logseq.shui.ui :as shui-ui]
            [frontend.colors :as colors]
            [frontend.components.assets :as assets]
            [frontend.components.conversion :as conversion-component]
            [frontend.components.llm-settings :as llm-settings]
            [frontend.components.skills-settings :as skills-settings]
            [frontend.components.plugins :as plugins]
            [frontend.components.svg :as svg]
            [frontend.config :as config]
            [frontend.context.i18n :refer [t]]
            [frontend.date :as date]
            [frontend.db :as db]
            [frontend.dicts :as dicts]
            [frontend.handler.config :as config-handler]
            [frontend.handler.dropbox :as dropbox-handler]
            [frontend.handler.global-config :as global-config-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.plugin :as plugin-handler]
            [frontend.handler.route :as route-handler]
            [frontend.handler.ui :as ui-handler]
            [frontend.handler.update :as update-handler]
            [frontend.handler.user :as user-handler]
            [frontend.mobile.util :as mobile-util]
            [frontend.modules.instrumentation.core :as instrument]
            [frontend.modules.shortcut.data-helper :as shortcut-helper]
            [frontend.components.shortcut :as shortcut]
            [frontend.spec.storage :as storage-spec]
            [frontend.state :as state]
            [frontend.storage :as storage]
            [frontend.ui :as ui]
            [frontend.util :refer [classnames] :as util]
            [frontend.version :refer [version]]
            [promesa.core :as p]
            [reitit.frontend.easy :as rfe]
            [rum.core :as rum]))

(defn toggle
  [label-for name state on-toggle & [detail-text]]
  [:div.it.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-center
   [:label.block.text-sm.font-medium.leading-5.opacity-70
    {:for label-for}
    name]
   [:div.rounded-md.sm:max-w-tss.sm:col-span-2
    [:div.rounded-md {:style {:display "flex" :gap "1rem" :align-items "center"}}
     (ui/toggle state on-toggle true)
     detail-text]]])

(def ^:private releases-url "https://github.com/JWE24-code/kip-app/releases")

(rum/defcs app-updater < rum/reactive
  [_state version]
  (let [{:keys [checking? newer? latest url]} (state/sub :kip/update)]
    [:span.cp__settings-app-updater
     [:div.ctls.flex.items-center
      [:div.mt-1.sm:mt-0.sm:col-span-2.flex.gap-4.items-center.flex-wrap
       [:div
        (if (util/electron?)
          (ui/button
           (if checking? (t :settings-page/checking) (t :settings-page/check-for-updates))
           :class "text-sm mr-1"
           :disabled (boolean checking?)
           :on-click #(update-handler/check! true))
          (ui/button
           (t :settings-page/check-for-updates)
           :class "text-sm mr-1"
           :href releases-url))]

       [:div.text-sm version]

       [:a.text-sm.fade-link.underline.inline
        {:target "_blank"
         :href "https://github.com/JWE24-code/kip-app/blob/version/file/CHANGELOG.md"}
        (t :settings-page/changelog)]]]

     (when (and (util/electron?) (not checking?) (some? newer?))
       [:div.update-state.text-sm
        (if newer?
          [:p (str "Kip " latest " is available — ")
           [:a.link {:on-click (fn [e] (js/window.apis.openExternal url) (util/stop e))}
            svg/external-link "download"]]
          [:p (t :settings-page/app-updated)])])]))

(rum/defc outdenting-hint
  []
  [:div.ui__modal-panel
   {:style {:box-shadow "0 4px 20px 4px rgba(0, 20, 60, .1), 0 4px 80px -8px rgba(0, 20, 60, .2)"}}
   [:div {:style {:margin "12px" :max-width "500px"}}
    [:p.text-sm
     (t :settings-page/preferred-outdenting-tip)
     [:a.text-sm
      {:target "_blank" :href "https://discuss.logseq.com/t/whats-your-preferred-outdent-behavior-the-direct-one-or-the-logical-one/978"}
      (t :settings-page/preferred-outdenting-tip-more)]]
    [:img {:src    "https://discuss.logseq.com/uploads/default/original/1X/e8ea82f63a5e01f6d21b5da827927f538f3277b9.gif"
           :width  500
           :height 500}]]])

(rum/defc auto-expand-hint
  []
  [:div.ui__modal-panel
   {:style {:box-shadow "0 4px 20px 4px rgba(0, 20, 60, .1), 0 4px 80px -8px rgba(0, 20, 60, .2)"}}
   [:div {:style {:margin "12px" :max-width "500px"}}
    [:p.text-sm
     (t :settings-page/auto-expand-block-refs-tip)]
    [:img {:src    "https://user-images.githubusercontent.com/28241963/225818326-118deda9-9d1e-477d-b0ce-771ca0bcd976.gif"
           :width  500
           :height 500}]]])

(defn row-with-button-action
  [{:keys [left-label description action button-label href on-click desc -for stretch center?]
    :or {center? true}}]
  [:div.it.sm:grid.sm:grid-cols-3.sm:gap-4
   {:class (if center? "sm:items-center" "sm:items-start")}
   ;; left column
   [:div.flex.flex-col
    [:label.block.text-sm.font-medium.leading-5.opacity-70
     {:for -for}
     left-label]
    (when description
      [:div.text-xs.text-gray-10 description])]

   ;; right column
   [:div.mt-1.sm:mt-0.sm:col-span-2.flex.items-center
    {:style {:display "flex" :gap "0.5rem" :align-items "center"}}
    [:div {:style (when stretch {:width "100%"})}
     (if action action (shui-ui/button
                         {:as-child (not (string/blank? href))
                          :size     :sm
                          :on-click on-click}
                         (if (string/blank? href) button-label
                           (shui-ui/link {:href href} button-label))))]
    (when-not (or (util/mobile?)
                  (mobile-util/native-platform?))
      [:div.text-sm.flex desc])]])

(defn edit-config-edn []
  (row-with-button-action
   {:left-label   (t :settings-page/custom-configuration)
    :button-label (t :settings-page/edit-config-edn)
    :href         (rfe/href :file {:path (config/get-repo-config-path)})
    :on-click     ui-handler/toggle-settings-modal!
    :-for         "config_edn"}))

(defn edit-global-config-edn []
  (row-with-button-action
    {:left-label   (t :settings-page/custom-global-configuration)
     :button-label (t :settings-page/edit-global-config-edn)
     :href         (rfe/href :file {:path (global-config-handler/global-config-path)})
     :on-click     ui-handler/toggle-settings-modal!
     :-for         "global_config_edn"}))

(defn edit-custom-css []
  (row-with-button-action
   {:left-label   (t :settings-page/custom-theme)
    :button-label (t :settings-page/edit-custom-css)
    :href         (rfe/href :file {:path (config/get-custom-css-path)})
    :on-click     ui-handler/toggle-settings-modal!
    :-for         "customize_css"}))

(defn edit-export-css []
  (row-with-button-action
   {:left-label   (t :settings-page/export-theme)
    :button-label (t :settings-page/edit-export-css)
    :href         (rfe/href :file {:path (config/get-export-css-path)})
    :on-click     ui-handler/toggle-settings-modal!
    :-for         "export_css"}))

(defn show-brackets-row [t show-brackets?]
  [:div.it.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-center
   [:label.block.text-sm.font-medium.leading-5.opacity-70
    {:for "show_brackets"}
    (t :settings-page/show-brackets)]
   [:div
    [:div.rounded-md.sm:max-w-xs
     (ui/toggle show-brackets?
                config-handler/toggle-ui-show-brackets!
                true)]]
   (when (not (or (util/mobile?) (mobile-util/native-platform?)))
     [:div {:style {:text-align "right"}}
      (ui/render-keyboard-shortcut (shortcut-helper/gen-shortcut-seq :ui/toggle-brackets))])])

(rum/defcs switch-spell-check-row < rum/reactive
  [state t]
  (let [enabled? (state/sub [:electron/user-cfgs :spell-check])]
    [:div.it.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-center
     [:label.block.text-sm.font-medium.leading-5.opacity-70
      (t :settings-page/spell-checker)]
     [:div
      [:div.rounded-md.sm:max-w-xs
       (ui/toggle
         enabled?
         (fn []
           (state/set-state! [:electron/user-cfgs :spell-check] (not enabled?))
           (p/then (ipc/ipc :userAppCfgs :spell-check (not enabled?))
                   #(when (js/confirm (t :relaunch-confirm-to-work))
                      (js/logseq.api.relaunch))))
         true)]]]))

(rum/defc app-auto-update-row < rum/reactive [t]
  (let [enabled? (state/sub [:electron/user-cfgs :auto-update])
        enabled? (if (nil? enabled?) true enabled?)]
    (toggle "usage-diagnostics"
            (t :settings-page/auto-updater)
            enabled?
            #((state/set-state! [:electron/user-cfgs :auto-update] (not enabled?))
              (ipc/ipc :userAppCfgs :auto-update (not enabled?))))))

(defn language-row [t preferred-language]
  (let [on-change (fn [e]
                    (let [lang-code (util/evalue e)]
                      (state/set-preferred-language! lang-code)
                      (ui-handler/re-render-root!)))
        action [:select.form-select.is-small {:value     preferred-language
                                              :on-change on-change}
                (for [language dicts/languages]
                  (let [lang-code (name (:value language))
                        lang-label (:label language)]
                    [:option {:key lang-code :value lang-code} lang-label]))]]
    (row-with-button-action {:left-label (t :language)
                             :-for       "preferred_language"
                             :action     action})))

(defn theme-modes-row [t switch-theme system-theme? dark?]
  (let [color-accent (state/sub :ui/radix-color)
        pick-theme [:ul.theme-modes-options
                    [:li {:on-click (partial state/use-theme-mode! "light")
                          :class    (classnames [{:active (and (not system-theme?) (not dark?))}])} [:i.mode-light {:class (when color-accent "radix")}] [:strong (t :settings-page/theme-light)]]
                    [:li {:on-click (partial state/use-theme-mode! "dark")
                          :class    (classnames [{:active (and (not system-theme?) dark?)}])} [:i.mode-dark {:class (when color-accent "radix")}] [:strong (t :settings-page/theme-dark)]]
                    [:li {:on-click (partial state/use-theme-mode! "system")
                          :class    (classnames [{:active system-theme?}])} [:i.mode-system {:class (when color-accent "radix")}] [:strong (t :settings-page/theme-system)]]]]
    (row-with-button-action {:left-label (t :right-side-bar/switch-theme (string/capitalize switch-theme))
                             :-for       "toggle_theme"
                             :action     pick-theme
                             :desc       (ui/render-keyboard-shortcut (shortcut-helper/gen-shortcut-seq :ui/toggle-theme))})))

(defn accent-color-row [_in-modal?]
  (let [color-accent (state/sub :ui/radix-color)
        pick-theme [:div.cp__accent-colors-list-wrap
                    {:class (if _in-modal? "as-modal-picker" "")}
                    (for [color (concat [:none :logseq] colors/color-list)
                          :let [active? (= color color-accent)
                                none? (= color :none)]]
                      [:div.flex.items-center {:style {:height 28}}
                       (ui/tippy
                         {:html (case color
                                  :none [:p {:style {:max-width "300px"}}
                                         "Cancel accent color. This is currently in beta stage and mainly used for compatibility with custom themes."]
                                  :logseq "Logseq classical color"
                                  (str (name color) " color") )
                          :delay [1000, 100]}
                         (shui-ui/button
                           {:class      "w-5 h-5 px-1 rounded-full flex justify-center items-center transition ease-in duration-100 hover:cursor-pointer hover:opacity-100"
                            :auto-focus (and _in-modal? active?)
                            :style      {:background-color (colors/variable color :09)
                                         :outline-color    (colors/variable color (if active? :07 :06))
                                         :outline-width    (if active? "4px" "1px")
                                         :outline-style    :solid
                                         :opacity          (if active? 1 0.5)}
                            :variant    :text
                            :on-click   (fn [_e] (state/set-color-accent! color))}
                           [:strong
                            {:class (if none? "h-0.5 w-full bg-red-700"
                                              "w-2 h-2 rounded-full transition ease-in duration-100")
                             :style {:background-color (if-not none? (str "var(--rx-" (name color) "-07)") "")
                                     :opacity          (if (or none? active?) 1 0)}}]))
                       ])]]

    [:<>
     (row-with-button-action {:left-label  "Accent color"
                              :description "Choosing an accent color may override any theme you have selected."
                              :-for        "toggle_radix_theme"
                              :desc        (when-not _in-modal?
                                             [:span.pl-6 (ui/render-keyboard-shortcut
                                                           (shortcut-helper/gen-shortcut-seq :ui/accent-colors-picker))])
                              :stretch     (boolean _in-modal?)
                              :action      pick-theme})]))

(rum/defc modal-accent-colors-inner
  []
  [:div.cp__settings-accent-colors-modal-inner
   (accent-color-row true)])

(defn file-format-row [t preferred-format]
  [:div.it.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-center
   [:label.block.text-sm.font-medium.leading-5.opacity-70
    {:for "preferred_format"}
    (t :settings-page/preferred-file-format)]
   [:div.mt-1.sm:mt-0.sm:col-span-2
    [:div.max-w-lg.rounded-md
     [:select.form-select.is-small
      {:value     (name preferred-format)
       :on-change (fn [e]
                    (let [format (-> (util/evalue e)
                                     (string/lower-case)
                                     keyword)]
                      (user-handler/set-preferred-format! format)))}
      (for [format (map name [:org :markdown])]
        [:option {:key format :value format} (string/capitalize format)])]]]])

(defn date-format-row [t preferred-date-format]
  [:div.it.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-:div.it.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-center
   [:label.block.text-sm.font-medium.leading-5.opacity-70
    {:for "custom_date_format"}
    (t :settings-page/custom-date-format)
    (ui/tippy {:html        (t :settings-page/custom-date-format-warning)
               :class       "tippy-hover ml-2"
               :interactive true
               :disabled    false}
              (svg/info))]
   [:div.mt-1.sm:mt-0.sm:col-span-2
    [:div.max-w-lg.rounded-md
     [:select.form-select.is-small
      {:value     preferred-date-format
       :on-change (fn [e]
                    (let [format (util/evalue e)]
                      (when-not (string/blank? format)
                        (config-handler/set-config! :journal/page-title-format format)
                        (notification/show!
                          [:div (t :settings-page/custom-date-format-notification)]
                          :warning false)
                        (state/close-modal!)
                        (route-handler/redirect! {:to :repos}))))}
      (for [format (sort (date/journal-title-formatters))]
        [:option {:key format} format])]]]])

(defn workflow-row [t preferred-workflow]
  [:div.it.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-center
   [:label.block.text-sm.font-medium.leading-5.opacity-70
    {:for "preferred_workflow"}
    (t :settings-page/preferred-workflow)]
   [:div.mt-1.sm:mt-0.sm:col-span-2
    [:div.max-w-lg.rounded-md
     [:select.form-select.is-small
      {:value     (name preferred-workflow)
       :on-change (fn [e]
                    (-> (util/evalue e)
                        string/lower-case
                        keyword
                        (#(if (= % :now) :now :todo))
                        user-handler/set-preferred-workflow!))}
      (for [workflow [:now :todo]]
        [:option {:key (name workflow) :value (name workflow)}
         (if (= workflow :now) "NOW/LATER" "TODO/DOING")])]]]])

(defn outdenting-row [t logical-outdenting?]
  (toggle "preferred_outdenting"
          [(t :settings-page/preferred-outdenting)
           (ui/tippy {:html        (outdenting-hint)
                      :class       "tippy-hover ml-2"
                      :interactive true
                      :disabled    false}
                     (svg/info))]
          logical-outdenting?
          config-handler/toggle-logical-outdenting!))

(defn showing-full-blocks [t show-full-blocks?]
  (toggle "show_full_blocks"
          (t :settings-page/show-full-blocks)
          show-full-blocks?
          config-handler/toggle-show-full-blocks!))

(defn preferred-pasting-file [t preferred-pasting-file?]
  (toggle "preferred_pasting_file"
          [(t :settings-page/preferred-pasting-file)
           (ui/tippy {:html        (t :settings-page/preferred-pasting-file-hint)
                      :class       "tippy-hover ml-2"
                      :interactive true
                      :disabled    false}
                     (svg/info))]
          preferred-pasting-file?
          config-handler/toggle-preferred-pasting-file!))

(defn auto-expand-row [t auto-expand-block-refs?]
  (toggle "auto_expand_block_refs"
          [(t :settings-page/auto-expand-block-refs)
           (ui/tippy {:html        (auto-expand-hint)
                      :class       "tippy-hover ml-2"
                      :interactive true
                      :disabled    false}
                     (svg/info))]
          auto-expand-block-refs?
          config-handler/toggle-auto-expand-block-refs!))

(defn tooltip-row [t enable-tooltip?]
  (toggle "enable_tooltip"
          (t :settings-page/enable-tooltip)
          enable-tooltip?
          (fn []
            (config-handler/toggle-ui-enable-tooltip!))))

(defn shortcut-tooltip-row [t enable-shortcut-tooltip?]
  (toggle "enable_tooltip"
          (t :settings-page/enable-shortcut-tooltip)
          enable-shortcut-tooltip?
          (fn []
            (state/toggle-shortcut-tooltip!))))

(defn timetracking-row [t enable-timetracking?]
  (toggle "enable_timetracking"
          (t :settings-page/enable-timetracking)
          enable-timetracking?
          #(let [value (not enable-timetracking?)]
             (config-handler/set-config! :feature/enable-timetracking? value))))

(defn update-home-page
  [event]
  (let [value (util/evalue event)]
    (cond
      (string/blank? value)
      (let [home (get (state/get-config) :default-home {})
            new-home (dissoc home :page)]
        (config-handler/set-config! :default-home new-home)
        (notification/show! "Home default page updated successfully!" :success))

      (db/page-exists? value)
      (let [home (get (state/get-config) :default-home {})
            new-home (assoc home :page value)]
        (config-handler/set-config! :default-home new-home)
        (notification/show! "Home default page updated successfully!" :success))

      :else
      (notification/show! (str "The page \"" value "\" doesn't exist yet. Please create that page first, and then try again.") :warning))))

(defn journal-row [enable-journals?]
  (toggle "enable_journals"
          (t :settings-page/enable-journals)
          enable-journals?
          (fn []
            (let [value (not enable-journals?)]
              (config-handler/set-config! :feature/enable-journals? value)))))

(defn enable-all-pages-public-row [t enable-all-pages-public?]
  (toggle "all pages public"
          (t :settings-page/enable-all-pages-public)
          enable-all-pages-public?
          (fn []
            (let [value (not enable-all-pages-public?)]
              (config-handler/set-config! :publishing/all-pages-public? value)))))

;; (defn enable-block-timestamps-row [t enable-block-timestamps?]
;;   (toggle "block timestamps"
;;           (t :settings-page/enable-block-time)
;;           enable-block-timestamps?
;;           (fn []
;;             (let [value (not enable-block-timestamps?)]
;;               (config-handler/set-config! :feature/enable-block-timestamps? value)))))

(defn zotero-settings-row []
  [:div.it.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-center
   [:label.block.text-sm.font-medium.leading-5.opacity-70
    {:for "zotero_settings"}
    "Zotero"]
   [:div.mt-1.sm:mt-0.sm:col-span-2
    [:div
     (ui/button
       (t :settings)
       :class "text-sm"
       :style {:margin-top "0px"}
       :on-click
       (fn []
         (state/close-settings!)
         (route-handler/redirect! {:to :zotero-setting})))]]])

(rum/defcs dropbox-app-key-advanced < rum/reactive (rum/local false ::open?) (rum/local "" ::val)
  [state custom?]
  (let [*open? (::open? state) *val (::val state)]
    [:div.mt-2
     [:button.text-xs.opacity-40.hover:opacity-70 {:on-click #(swap! *open? not)}
      (str (if @*open? "▾" "▸") " advanced")]
     (when @*open?
       [:div.mt-1.text-xs.opacity-70.leading-snug
        (if custom?
          [:div
           "Using your own Dropbox app. "
           [:a.text-blue-500 {:on-click #(dropbox-handler/set-app-key! nil)} "Switch back to Kip's"]]
          [:div.flex.flex-col.gap-1 {:style {:max-width "24rem"}}
           [:span "Use your own Dropbox app key (for your own rate-limit quota). "
            "Register an app at dropbox.com/developers, scoped access, with "
            [:code "http://localhost"] " as a redirect URI."]
           [:div.flex.gap-1
            [:input.form-input.is-small.flex-1 {:type "text" :placeholder "app key"
                                                :value @*val
                                                :on-change #(reset! *val (.. % -target -value))}]
            (ui/button "Use" :class "text-xs"
                       :on-click #(when (seq (string/trim @*val))
                                    (dropbox-handler/set-app-key! (string/trim @*val))
                                    (reset! *val "")))]])])]))

(rum/defc dropbox-sync-row < rum/reactive
  {:did-mount (fn [state]
                (dropbox-handler/refresh!)
                (dropbox-handler/refresh-sync!)
                state)}
  [current-repo]
  (let [{:keys [connected account error customAppKey]} (state/sub :dropbox/status)
        connecting? (state/sub :dropbox/connecting?)
        {:keys [synced conflictMode files lastSync] :as sync} (state/sub :dropbox/sync)
        sync-busy? (state/sub :dropbox/sync-busy?)]
    [:div.it.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-start
     [:label.block.text-sm.font-medium.leading-5.opacity-70 {:for "dropbox_sync"} "Dropbox"]
     [:div.mt-1.sm:mt-0.sm:col-span-2
      (if-not connected
        [:div
         (ui/button (if connecting? "Waiting for Dropbox…" "Connect Dropbox")
                    :class "text-sm" :disabled (boolean connecting?)
                    :on-click #(dropbox-handler/connect!))
         [:div.text-sm.opacity-50.mt-1
          "Opens Dropbox in your browser. Kip only ever sees a folder it
           creates for itself (Apps/Kip-ai/) — nothing else in your Dropbox."]
         (dropbox-app-key-advanced (boolean customAppKey))]
        [:div
         [:div.text-sm
          (if account
            [:span "Connected as " [:b (or (:name account) (:email account))]]
            [:span.text-amber-600 (or error "Connected — needs re-authorising")])
          "  ·  "
          [:a.opacity-60.hover:opacity-100 {:on-click #(dropbox-handler/disconnect!)} "disconnect"]]

         (when current-repo
           [:div.mt-2.pt-2.border-t.border-gray-05
            (if synced
              [:div.text-sm
               [:div "This graph syncs with Dropbox"
                (when files [:span.opacity-50 (str " · " files " files")])
                (when (:error sync) [:span.text-red-500 (str " · " (:error sync))])]
               [:div.opacity-50.text-xs.mt-0.5
                (str "Conflicts: " (if (= conflictMode "manual") "keep both copies" "newest wins")
                     (when lastSync (str " · last sync " (.toLocaleTimeString (js/Date. lastSync)))))]
               [:div.mt-1.flex.gap-2
                (ui/button "Sync now" :class "text-xs" :disabled (boolean sync-busy?)
                           :on-click #(dropbox-handler/sync-now!))
                (ui/button "Stop syncing this graph" :class "text-xs" :background "gray"
                           :on-click #(dropbox-handler/disable-sync!))]]
              [:div
               (ui/button (if sync-busy? "Setting up…" "Sync this graph with Dropbox")
                          :class "text-sm" :disabled (boolean sync-busy?)
                          :on-click #(dropbox-handler/enable-sync! "auto"))
               (when (:error sync)
                 [:div.text-xs.text-red-500.mt-1.leading-snug (str "Couldn't start sync: " (:error sync))])
               [:div.text-xs.opacity-50.mt-1
                "Two-way. Newest change wins on a conflict (Dropbox keeps history).
                 Notes only — the search cache and your API keys stay on this machine."]])])])]]))

(defn usage-diagnostics-row [t instrument-disabled?]
  (toggle "usage-diagnostics"
          (t :settings-page/disable-sentry)
          (not instrument-disabled?)
          (fn [] (instrument/disable-instrument
                   (not instrument-disabled?)))
          [:span.text-sm.opacity-50 (t :settings-page/disable-sentry-desc)]))

(defn clear-cache-row [t]
  (row-with-button-action {:left-label   (t :settings-page/clear-cache)
                           :button-label (t :settings-page/clear)
                           :on-click     #(state/pub-event! [:graph/clear-cache!])
                           :-for         "clear_cache"}))

(defn version-row [t version]
  (row-with-button-action {:left-label (t :settings-page/current-version)
                           :action     (app-updater version)
                           :-for       "current-version"}))

(defn developer-mode-row [t developer-mode?]
  (toggle "developer_mode"
          (t :settings-page/developer-mode)
          developer-mode?
          (fn []
            (let [mode (not developer-mode?)]
              (state/set-developer-mode! mode)))
          [:div.text-sm.opacity-50 (t :settings-page/developer-mode-desc)]))

(rum/defc plugin-enabled-switcher
  [t]
  (let [value (state/lsp-enabled?-or-theme)
        [on? set-on?] (rum/use-state value)
        on-toggle #(let [v (not on?)]
                     (set-on? v)
                     (storage/set ::storage-spec/lsp-core-enabled v))]
    [:div.flex.items-center.gap-2
     (ui/toggle on? on-toggle true)
     (when (not= (boolean value) on?)
       (ui/button (t :plugin/restart)
                  :on-click #(js/logseq.api.relaunch)
                  :small? true :intent "logseq"))]))

(rum/defc http-server-enabled-switcher
  [t]
  (let [[value _] (rum/use-state (boolean (storage/get ::storage-spec/http-server-enabled)))
        [on? set-on?] (rum/use-state value)
        on-toggle #(let [v (not on?)]
                     (set-on? v)
                     (storage/set ::storage-spec/http-server-enabled v))]
    [:div.flex.items-center.gap-2
     (ui/toggle on? on-toggle true)
     (when (not= (boolean value) on?)
       (ui/button (t :plugin/restart)
                  :on-click #(js/logseq.api.relaunch)
                  :small? true :intent "logseq"))]))

(rum/defc flashcards-enabled-switcher
  [enable-flashcards?]
  (ui/toggle enable-flashcards?
             (fn []
               (let [value (not enable-flashcards?)]
                 (config-handler/set-config! :feature/enable-flashcards? value)))
             true))

(rum/defc user-proxy-settings
  [{:keys [type protocol host port] :as agent-opts}]
  (ui/button [:span.flex.items-center
              [:span.pr-1
               (case type
                 "system" "System Default"
                 "direct" "Direct"
                 (and protocol host port (str protocol "://" host ":" port)))]
              (ui/icon "edit")]
             :class "text-sm"
             :on-click #(state/set-sub-modal!
                         (fn [_] (plugins/user-proxy-settings-panel agent-opts))
                         {:id :https-proxy-panel :center? true})))

(defn plugin-system-switcher-row []
  (row-with-button-action
   {:left-label (t :settings-page/plugin-system)
    :action (plugin-enabled-switcher t)}))

(defn http-server-switcher-row []
  (row-with-button-action
   {:left-label "HTTP APIs server"
    :action (http-server-enabled-switcher t)}))

(defn flashcards-switcher-row [enable-flashcards?]
  (row-with-button-action
   {:left-label (t :settings-page/enable-flashcards)
    :action (flashcards-enabled-switcher enable-flashcards?)}))

(defn https-user-agent-row [agent-opts]
  (row-with-button-action
   {:left-label (t :settings-page/network-proxy)
    :action (user-proxy-settings agent-opts)}))

(rum/defcs auto-chmod-row < rum/reactive
  [state t]
  (let [enabled? (if (= nil (state/sub [:electron/user-cfgs :feature/enable-automatic-chmod?]))
                   true
                   (state/sub [:electron/user-cfgs :feature/enable-automatic-chmod?]))]
    (toggle
     "automatic-chmod"
     (t :settings-page/auto-chmod)
     enabled?
     #(do
       (state/set-state! [:electron/user-cfgs :feature/enable-automatic-chmod?] (not enabled?))
       (ipc/ipc :userAppCfgs :feature/enable-automatic-chmod? (not enabled?)))
     [:span.text-sm.opacity-50 (t :settings-page/auto-chmod-desc)])))

(defn filename-format-row []
  (row-with-button-action
   {:left-label (t :settings-page/filename-format)
    :button-label (t :settings-page/edit-setting)
    :on-click #(state/set-sub-modal!
                (fn [_] (conversion-component/files-breaking-changed))
                {:id :filename-format-panel :center? true})}))

(rum/defcs native-titlebar-row < rum/reactive
  [state t]
  (let [enabled? (state/sub [:electron/user-cfgs :window/native-titlebar?])]
    (toggle
     "native-titlebar"
     (t :settings-page/native-titlebar)
     enabled?
     #(when (js/confirm (t :relaunch-confirm-to-work))
        (state/set-state! [:electron/user-cfgs :window/native-titlebar?] (not enabled?))
        (ipc/ipc :userAppCfgs :window/native-titlebar? (not enabled?))
        (js/logseq.api.relaunch))
     [:span.text-sm.opacity-50 (t :settings-page/native-titlebar-desc)])))

(rum/defcs settings-general < rum/reactive
  [_state current-repo]
  (let [preferred-language (state/sub [:preferred-language])
        theme (state/sub :ui/theme)
        dark? (= "dark" theme)
        show-radix-themes? true
        system-theme? (state/sub :ui/system-theme?)
        switch-theme (if dark? "light" "dark")]
    [:div.panel-wrap.is-general
     (version-row t version)
     (language-row t preferred-language)
     (theme-modes-row t switch-theme system-theme? dark?)
     (when (and (util/electron?) (not util/mac?)) (native-titlebar-row t))
     (when show-radix-themes? (accent-color-row false))
     (when (util/electron?) (dropbox-sync-row current-repo))
     (when (config/global-config-enabled?) (edit-global-config-edn))
     (when current-repo (edit-config-edn))
     (when current-repo (edit-custom-css))
     (when current-repo (edit-export-css))]))

(rum/defcs settings-editor < rum/reactive
  [_state _current-repo]
  (let [preferred-format (state/get-preferred-format)
        preferred-date-format (state/get-date-formatter)
        preferred-workflow (state/get-preferred-workflow)
        enable-timetracking? (state/enable-timetracking?)
        enable-all-pages-public? (state/all-pages-public?)
        logical-outdenting? (state/logical-outdenting?)
        show-full-blocks? (state/show-full-blocks?)
        preferred-pasting-file? (state/preferred-pasting-file?)
        auto-expand-block-refs? (state/auto-expand-block-refs?)
        enable-tooltip? (state/enable-tooltip?)
        enable-shortcut-tooltip? (state/sub :ui/shortcut-tooltip?)
        show-brackets? (state/show-brackets?)]

    [:div.panel-wrap.is-editor
     (file-format-row t preferred-format)
     (date-format-row t preferred-date-format)
     (workflow-row t preferred-workflow)
     ;; (enable-block-timestamps-row t enable-block-timestamps?)
     (show-brackets-row t show-brackets?)

     (when (util/electron?) (switch-spell-check-row t))
     (outdenting-row t logical-outdenting?)
     (showing-full-blocks t show-full-blocks?)
     (preferred-pasting-file t preferred-pasting-file?)
     (auto-expand-row t auto-expand-block-refs?)
     (when-not (or (util/mobile?) (mobile-util/native-platform?))
       (shortcut-tooltip-row t enable-shortcut-tooltip?))
     (when-not (or (util/mobile?) (mobile-util/native-platform?))
       (tooltip-row t enable-tooltip?))
     (timetracking-row t enable-timetracking?)
     (enable-all-pages-public-row t enable-all-pages-public?)]))

(rum/defc settings-advanced < rum/reactive
  [current-repo]
  (let [instrument-disabled? (state/sub :instrument/disabled?)
        developer-mode? (state/sub [:ui/developer-mode?])
        https-agent-opts (state/sub [:electron/user-cfgs :settings/agent])]
    [:div.panel-wrap.is-advanced
     (when (and (or util/mac? util/win32?) (util/electron?)) (app-auto-update-row t))
     (usage-diagnostics-row t instrument-disabled?)
     (when-not (mobile-util/native-platform?) (developer-mode-row t developer-mode?))
     (when (util/electron?) (https-user-agent-row https-agent-opts))
     (when (util/electron?) (auto-chmod-row t))
     (when (and (util/electron?) (not (config/demo-graph? current-repo))) (filename-format-row))
     (clear-cache-row t)

     (ui/admonition
       :warning
       [:p (t :settings-page/clear-cache-warning)])]))

(rum/defc whiteboards-enabled-switcher
  [enabled?]
  (ui/toggle enabled?
             (fn []
               (let [value (not enabled?)]
                 (config-handler/set-config! :feature/enable-whiteboards? value)))
             true))

(defn whiteboards-switcher-row [enabled?]
  (row-with-button-action
   {:left-label (t :settings-page/enable-whiteboards)
    :action (whiteboards-enabled-switcher enabled?)}))

(rum/defc settings-features < rum/reactive
  []
  (let [current-repo (state/get-current-repo)
        enable-journals? (state/enable-journals? current-repo)
        enable-flashcards? (state/enable-flashcards? current-repo)
        enable-whiteboards? (state/enable-whiteboards? current-repo)]
    [:div.panel-wrap.is-features.mb-8
     (journal-row enable-journals?)
     (when (not enable-journals?)
       [:div.it.sm:grid.sm:grid-cols-3.sm:gap-4.sm:items-center
        [:label.block.text-sm.font-medium.leading-5.opacity-70
         {:for "default page"}
         (t :settings-page/home-default-page)]
        [:div.mt-1.sm:mt-0.sm:col-span-2
         [:div.max-w-lg.rounded-md.sm:max-w-xs
          [:input#home-default-page.form-input.is-small.transition.duration-150.ease-in-out
           {:default-value (state/sub-default-home-page)
            :on-blur       update-home-page
            :on-key-press  (fn [e]
                             (when (= "Enter" (util/ekey e))
                               (update-home-page e)))}]]]])
     (whiteboards-switcher-row enable-whiteboards?)
     (when (and (util/electron?) config/feature-plugin-system-on?)
       (plugin-system-switcher-row))
     (when (util/electron?)
       (http-server-switcher-row))
     (flashcards-switcher-row enable-flashcards?)
     (zotero-settings-row)]))


(def DEFAULT-ACTIVE-TAB-STATE [:general :general])

(rum/defc settings-effect
  < rum/static
  [active]

  (rum/use-effect!
    (fn []
      (let [active (and (sequential? active) (name (first active)))
            ^js ds (.-dataset js/document.body)]
        (if active
          (set! (.-settingsTab ds) active)
          (js-delete ds "settingsTab"))
        #(js-delete ds "settingsTab")))
    [active])

  [:<>])

(rum/defcs settings
  < (rum/local DEFAULT-ACTIVE-TAB-STATE ::active)
    {:will-mount
     (fn [state]
       (state/load-app-user-cfgs)
       state)
     :did-mount
     (fn [state]
       (let [active-tab (first (:rum/args state))
             *active (::active state)]
         (when (keyword? active-tab)
           (reset! *active [active-tab nil])))
       state)
     :will-unmount
     (fn [state]
       (state/close-settings!)
       state)}
    rum/reactive
  [state _active-tab]
  (let [current-repo (state/sub :git/current-repo)
        ;; enable-block-timestamps? (state/enable-block-timestamps?)
        _installed-plugins (state/sub :plugin/installed-plugins)
        plugins-of-settings (and config/lsp-enabled? (seq (plugin-handler/get-enabled-plugins-if-setting-schema)))
        *active (::active state)]

    [:div#settings.cp__settings-main
     (settings-effect @*active)
     [:div.cp__settings-inner {:class "min-h-[70dvh] max-h-[70dvh]"}
      [:aside.md:w-64 {:style {:min-width "10rem"}}
       [:header.cp__settings-header
        [:h1.cp__settings-modal-title (t :settings)]]
       [:ul.settings-menu
        (for [[label id text icon]
              [[:general "general" (t :settings-page/tab-general) (ui/icon "adjustments")]
               [:editor "editor" (t :settings-page/tab-editor) (ui/icon "writing")]
               [:keymap "keymap" (t :settings-page/tab-keymap) (ui/icon "keyboard")]

               (when (util/electron?)
                 [:llm "llm" (t :settings-page/tab-llm) (ui/icon "message-2")])

               (when (util/electron?)
                 [:skills "skills" (t :settings-page/tab-skills) (ui/icon "tool")])

               ;; (when (util/electron?)
               ;;   [:assets "assets" (t :settings-page/tab-assets) (ui/icon "box")])

               [:advanced "advanced" (t :settings-page/tab-advanced) (ui/icon "bulb")]
               [:features "features" (t :settings-page/tab-features) (ui/icon "app-feature")]

               (when plugins-of-settings
                 [:plugins-setting "plugins" (t :settings-of-plugins) (ui/icon "puzzle")])]]

          (when label
            [:li.settings-menu-item
             {:key      text
              :data-id  id
              :class    (util/classnames [{:active (= label (first @*active))}])
              :on-click #(reset! *active [label (first @*active)])}

             [:a.flex.items-center.settings-menu-link icon [:strong text]]]))]]

      [:article
       [:header.cp__settings-header
        [:h1.cp__settings-category-title (t (keyword (str "settings-page/tab-" (name (first @*active)))))]]

       (case (first @*active)

         :plugins-setting
         (let [label (second @*active)]
           (state/pub-event! [:go/plugins-settings (:id (first plugins-of-settings))])
           (reset! *active [label label])
           nil)

         :general
         (settings-general current-repo)

         :editor
         (settings-editor current-repo)

         :keymap
         (shortcut/shortcut-keymap-x)

         :llm
         (llm-settings/settings-content)

         :skills
         (skills-settings/settings-content)

         :assets
         (assets/settings-content)

         :advanced
         (settings-advanced current-repo)

         :features
         (settings-features)

         nil)]]]))
