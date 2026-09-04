(ns frontend.components.addressbook
  "The Addressbook panel (right-sidebar entry, see :addressbook in
   frontend.components.right-sidebar). A view over the coop's person pages
   (nest/people/*.md) with a filter, sortable columns, click-through to the
   person page, a \"New person\" form, and a dedupe/merge affordance for persons
   sharing an email (kip-app#126).

   Backed by the :wikiPeopleList / :wikiPeopleMerge / :wikiPersonAdd IPC
   channels (electron.wiki, fs reads/writes of the frontmatter hatch writes)."
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.handler.route :as route]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [promesa.core :as p]
            [rum.core :as rum]))

(defn- vault-root [] (config/get-repo-dir (state/get-current-repo)))

(defn- fetch! [*rows *loading?]
  (reset! *loading? true)
  (-> (ipc/ipc "wikiPeopleList" (vault-root))
      (p/then (fn [r] (reset! *rows (vec (:people (bean/->clj r))))))
      (p/catch (fn [_] (reset! *rows [])))
      (p/finally (fn [] (reset! *loading? false)))))

(defn- haystack [p]
  (string/lower-case
   (string/join " " (concat [(:name p) (:org p) (:role p) (:email p) (:phone p)]
                            (:aliases p)))))

(defn- matches? [q p]
  (or (string/blank? q)
      (string/includes? (haystack p) (string/lower-case (string/trim q)))))

(defn- duplicates [people]
  (->> people
       (filter #(not (string/blank? (:email %))))
       (group-by (comp string/lower-case :email))
       (vals)
       (filter #(> (count %) 1))))

(defn- merge! [*rows keep-slug drop-slug refresh]
  (-> (ipc/ipc "wikiPeopleMerge" (vault-root) keep-slug drop-slug)
      (p/finally refresh)))

(defn- add-person! [*name *email *org *role *phone *aliases *adding refresh]
  (let [name (string/trim @*name)]
    (when-not (string/blank? name)
      (-> (ipc/ipc "wikiPersonAdd" (vault-root)
                   {:name name
                    :email (string/trim @*email)
                    :org (string/trim @*org)
                    :role (string/trim @*role)
                    :phone (string/trim @*phone)
                    :aliases (->> (string/split @*aliases #",")
                                  (map string/trim)
                                  (remove string/blank?)
                                  vec)})
          (p/finally (fn []
                       (reset! *name "")
                       (reset! *email "")
                       (reset! *org "")
                       (reset! *role "")
                       (reset! *phone "")
                       (reset! *aliases "")
                       (reset! *adding false)
                       (refresh)))))))

(defn- sort-key [k]
  (case k
    :org  (comp string/lower-case #(or (:org %) ""))
    :role (comp string/lower-case #(or (:role %) ""))
    (comp string/lower-case #(or (:name %) ""))))

(defn- col-header [label *sort k]
  [:button.text-xs.uppercase.tracking-wide.opacity-40.hover:opacity-70
   {:on-click #(reset! *sort k)}
   label (when (= @*sort k) " ▾")])

(rum/defc person-row < rum/static
  [{:keys [slug name email org role phone aliases]}]
  [:tr.group.cursor-pointer {:on-click #(route/redirect-to-page! slug)}
   [:td.py-1.5.pr-2.align-top
    [:div.text-sm.font-medium.leading-tight name]
    [:div.text-xs.opacity-40 (or slug "")]]
   [:td.py-1.5.pr-2.align-top.text-xs
    [:div (or org "") (when role (str " · " role))]]
   [:td.py-1.5.pr-2.align-top.text-xs
    [:div (or email "") (when phone (str " · " phone))]]
   [:td.py-1.5.align-top.text-xs.opacity-50
    (string/join ", " aliases)]])

(rum/defcs addressbook-panel
  < rum/reactive
  (rum/local nil ::rows)
  (rum/local false ::loading?)
  (rum/local "" ::q)
  (rum/local :name ::sort)
  (rum/local false ::adding)
  (rum/local "" ::name)
  (rum/local "" ::email)
  (rum/local "" ::org)
  (rum/local "" ::role)
  (rum/local "" ::phone)
  (rum/local "" ::aliases)
  {:will-mount (fn [state] (fetch! (::rows state) (::loading? state)) state)}
  [state]
  (let [*rows (::rows state)
        *loading? (::loading? state)
        *q (::q state)
        *sort (::sort state)
        *adding (::adding state)
        *name (::name state)
        *email (::email state)
        *org (::org state)
        *role (::role state)
        *phone (::phone state)
        *aliases (::aliases state)
        rows @*rows
        refresh #(fetch! *rows *loading?)
        q @*q
        people (->> (or rows [])
                    (filter #(matches? q %))
                    (sort-by (sort-key @*sort)))
        dups (duplicates (or rows []))]
    [:div.flex.flex-col {:style {:height "100%"}}
     [:div.flex.gap-2.px-1.pb-2
      [:input.form-input.is-small.flex-1.text-sm
       {:type "text" :placeholder "Filter by name, org, role, email…"
        :value @*q
        :on-change #(reset! *q (.. % -target -value))}]
      (ui/button {:icon "user-plus" :icon-props {:size 14} :variant :ghost :size :xs
                  :title "New person"
                  :class (if @*adding "" "opacity-60")
                  :on-click #(swap! *adding not)})
      (ui/button {:icon "refresh" :icon-props {:size 14} :variant :ghost :size :xs
                  :title "Refresh" :on-click refresh})]
     (when @*adding
       [:div.rounded.border.border-gray-05.p-2.mb-2.space-y-1
        [:div.text-xs.font-medium "New person"]
        [:input.form-input.is-small.w-full.text-sm
         {:type "text" :placeholder "Name (required)"
          :value @*name
          :on-change #(reset! *name (.. % -target -value))}]
        [:div.flex.gap-1
         [:input.form-input.is-small.flex-1.text-sm
          {:type "text" :placeholder "Email" :value @*email
           :on-change #(reset! *email (.. % -target -value))}]
         [:input.form-input.is-small.flex-1.text-sm
          {:type "text" :placeholder "Phone" :value @*phone
           :on-change #(reset! *phone (.. % -target -value))}]]
        [:div.flex.gap-1
         [:input.form-input.is-small.flex-1.text-sm
          {:type "text" :placeholder "Org" :value @*org
           :on-change #(reset! *org (.. % -target -value))}]
         [:input.form-input.is-small.flex-1.text-sm
          {:type "text" :placeholder "Role" :value @*role
           :on-change #(reset! *role (.. % -target -value))}]]
        [:input.form-input.is-small.w-full.text-sm
         {:type "text" :placeholder "Aliases (comma-separated)" :value @*aliases
          :on-change #(reset! *aliases (.. % -target -value))}]
        [:div.flex.gap-1.justify-end
         (ui/button {:size :xs :variant :ghost
                     :on-click #(reset! *adding false)} "Cancel")
         (ui/button {:size :xs
                     :on-click #(add-person! *name *email *org *role *phone *aliases *adding refresh)}
                    "Add person")]])
     [:div.flex-1.overflow-y-auto {:class "overflow-x-hidden"}
      (cond
        (and (nil? rows) @*loading?)
        [:div.text-xs.opacity-50.px-2.py-4 "Loading…"]

        (empty? rows)
        [:div.text-sm.opacity-50.px-2.py-6.leading-relaxed
         "No people yet. When you hatch a document that names someone, Kip files "
         "a " [:code "person"] " page under " [:code "nest/people/"] " and they'll show up here."]

        :else
        [:<>
         (when (seq dups)
           [:div.mb-3.rounded.border.p-2
            {:class "border-yellow-300/50 bg-yellow-500/10"}
            [:div.text-xs.font-medium "Possible duplicates (same email)"]
            (for [group dups
                  :let [keep (first (sort-by (comp count :slug) group))
                        drops (remove #(= (:slug %) (:slug keep)) group)]]
              [:div.flex.items-center.gap-2.text-xs.pt-1
               {:key (mapv :slug group)}
               [:div.flex-1.truncate
                (string/join " + " (map :name group))]
               [:div.opacity-50.truncate (or (:email keep) "")]
               (for [d drops]
                 (ui/button (str "Merge “" (:name d) "” →")
                            {:size :xs :variant :ghost
                             :class "opacity-60 hover:opacity-100"
                             :on-click #(merge! *rows (:slug keep) (:slug d) refresh)}))])])
         [:div.text-xs.opacity-50.px-1.pb-1 (str (count people) " people")]
         [:table.w-full.text-left
          [:thead
           [:tr.border-b.border-gray-05
            [:th.pb-1.pr-2 (col-header "Name" *sort :name)]
            [:th.pb-1.pr-2 (col-header "Org / Role" *sort :org)]
            [:th.pb-1.pr-2 (col-header "Contact" *sort :role)]
            [:th.pb-1 (col-header "Aliases" *sort :name)]]]
          [:tbody
           (for [p people]
             (rum/with-key (person-row p) (:slug p)))]]])]]))
