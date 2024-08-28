(ns app.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.spec.alpha :as s]
            [uix.core :as uix :refer [defui $]]
            [uix.dom]
            [app.hooks :as hooks]
            [app.subs]
            [app.handlers]
            [app.fx]
            [app.db]
            [re-frame.core :as rf]
            ;;[kitchen-async.promise :as p]
            ;; [lambdaisland.fetch :as fetch]
            [cljs-http.client :as http]
            [cljs.core.async :refer [<!]]
            [lambdaisland.uri :refer [uri join]]
            ))

;; (defui header []
;;   ($ :header.app-header
;;     ($ :img {:src "https://raw.githubusercontent.com/pitch-io/uix/master/logo.png"
;;              :width 32})))

;; (defui footer []
;;   ($ :footer.app-footer
;;     ($ :small "made with "
;;               ($ :a {:href "https://github.com/pitch-io/uix"}
;;                     "UIx"))))

;; (defui text-field [{:keys [on-add-todo]}]
;;   (let [[value set-value!] (uix/use-state "")]
;;     ($ :input.text-input
;;       {:value value
;;        :placeholder "Add a new todo and hit Enter to save"
;;        :on-change (fn [^js e]
;;                     (set-value! (.. e -target -value)))
;;        :on-key-down (fn [^js e]
;;                       (when (= "Enter" (.-key e))
;;                         (set-value! "")
;;                         (on-add-todo {:text value :status :unresolved})))})))

;; (defui editable-text [{:keys [text text-style on-done-editing]}]
;;   (let [[editing? set-editing!] (uix/use-state false)
;;         [editing-value set-editing-value!] (uix/use-state "")]
;;     (if editing?
;;       ($ :input.todo-item-text-field
;;         {:value editing-value

;;          :auto-focus true
;;          :on-change (fn [^js e]
;;                       (set-editing-value! (.. e -target -value)))
;;          :on-key-down (fn [^js e]
;;                         (when (= "Enter" (.-key e))
;;                           (set-editing-value! "")
;;                           (set-editing! false)
;;                           (on-done-editing editing-value)))})
;;       ($ :span.todo-item-text
;;         {:style text-style
;;          :on-click (fn [_]
;;                      (set-editing! true)
;;                      (set-editing-value! text))}
;;         text))))

;; (s/def :todo/text string?)
;; (s/def :todo/status #{:unresolved :resolved})

;; (s/def :todo/item
;;   (s/keys :req-un [:todo/text :todo/status]))

;; (defui todo-item
;;   [{:keys [created-at text status on-remove-todo on-set-todo-text] :as props}]
;;   {:pre [(s/valid? :todo/item props)]}
;;   ($ :.todo-item
;;     {:key created-at}
;;     ($ :input.todo-item-control
;;       {:type :checkbox
;;        :checked (= status :resolved)
;;        :on-change #(rf/dispatch [:todo/toggle-status created-at])})
;;     ($ editable-text
;;       {:text text
;;        :text-style {:text-decoration (when (= :resolved status) :line-through)}
;;        :on-done-editing #(on-set-todo-text created-at %)})
;;     ($ :button.todo-item-delete-button
;;       {:on-click #(on-remove-todo created-at)}
;;       "×")))

;; (defui app []
;;   (let [todos (hooks/use-subscribe [:app/todos])]
;;     ($ :.app
;;       ($ header)
;;       ($ text-field {:on-add-todo #(rf/dispatch [:todo/add %])})
;;       (for [[created-at todo] todos]
;;         ($ todo-item
;;           (assoc todo :created-at created-at
;;                       :key created-at
;;                       :on-remove-todo #(rf/dispatch [:todo/remove %])
;;                       :on-set-todo-text #(rf/dispatch [:todo/set-text %1 %2]))))
;;       ($ footer))))

;; (defn ticket-search [s]
;;   (p/try
;;     (p/let [resp (fetch/get
;;                   "jira/search/q=FH-8884"
;;                   {:accept :json
;;                    :content-type :json})]
;;       (prn (:body resp)))
;;     (p/catch :default e
;;       ;; log your exception here
;;       (prn :error e))))

;;(ticket-search "aaa")

;; (def result-ref (atom nil))
;; (defn into-ref [chan]
;;   (go (reset! result-ref (<! chan))))
;; (into-ref
;;   (http/get "http://localhost:8000/jira/search?q=123"
;;     {:query-params
;;      {"q" "123"}}))
;; @result-ref

(comment 
  (go (let [response (<! (http/get "https://api.github.com/users"
                                   {:with-credentials? false
                                    :query-params {"since" 135}}))]
        (prn (:status response))
        (prn (map :login (:body response))))))

(def app-uri
  (uri
   (. (. js/document -location) -href)))

(def api-url-base
  (str (:scheme app-uri) "://" (:host app-uri) (if (nil? (:port app-uri)) "" (str ":" (:port app-uri)))))

(defn search-tickets [query]
  (go (let [response (<! (http/get (str api-url-base "/jira/search")
                                   {:query-params {"q" query}}))
            body (:body response)
            ]
        (js/console.log (clj->js body))
        ))
  )

(search-tickets "aaa")

(defui navbar []
  ($ :nav.navbar {:role "navigation" :area-label="main navigation"}
     ($ :a.navbar-burger {:role "button" :aria-label "menu" :aria-expanded "false" :data-target "navbarBasicExample"}
        ($ :span {:aria-hidden "true"})
        ($ :span {:aria-hidden "true"})
        ($ :span {:aria-hidden "true"})
        ($ :span {:aria-hidden "true"}))
     ($ :div.navbar-menu
        ($ :div.navbar-start
           ($ :a.navbar-item {:href "/"} "Home")
           ($ :a.navbar-item {:href "/static/docs/README.html" :target "_docs"} "Docs")
           ($ :a.navbar-item "Estimate")
           ($ :a.navbar-item "Browse tickets"))
        ($ :div.navbar-end
           ($ :div.navbar-item
              ($ :div.buttons
                 ($ :a.button {:class "is-primary"}
                    ($ :strong "Logout"))))
           )))
)

(defui footer []
  ($ :footer.footer {:class "is-flex-align-items-flex-end mt-auto"}
     ($ :div.content {:class "has-text-centered"}
        ($ :p "(C) 2024 "
           ($ :a {:href "http://alex.koval.kharkov.ua" :target "_blank"} "Oleksii Koval")
           " as part of Daily Learning Excercise "
           )

        ($ :p "Technologies used to implement this page: "
           ($ :a {:href "https://fastapi.tiangolo.com/" :target "_blank"} "FastAPI")
           " and "
           ($ :a {:href "https://github.com/pitch-io/uix" :target "_blank"} "UIX^2")
           )
        ))
)

(def SEARCH_STARTS_AT 5)
(def SEARCH_DELAY 1500)

(defui enter-ticket-no
  "Search for ticket"
  []
  (let [[q set-q!] (uix/use-state "")
        [tickets set-tickets!]  (uix/use-state [])
        [last-query set-last-query!]  (uix/use-state "")
        [last-query-time set-last-query-time!] (uix/use-state 0)
        timestamp (.now js/Date)]
    (uix/use-effect 
     (fn []
       (when (and (> (count q) SEARCH_STARTS_AT) (not (= q last-query)) (or (= 0 last-query-time) (> (- timestamp last-query-time) SEARCH_DELAY)))
         (go (let [response (<! (http/get (str api-url-base "/jira/search")
                                          {:query-params {"q" q}}))
                   body (:body response)
                   ]
               (js/console.log "Query finished" tickets " diff " (- timestamp last-query-time) (> (- timestamp last-query-time) SEARCH_DELAY))
               (set-tickets! body)
               (set-last-query! q)
               (set-last-query-time! timestamp))))
       (when (< (- timestamp last-query-time) SEARCH_DELAY)
         (js/setTimeout #(set-last-query-time! 0) SEARCH_DELAY)
         )
       js/undefined))
    (js/console.log "ok-2024-08-28-1724872598 " (count tickets) (clj->js tickets))
    ($ :div.ticket-search
       ($ :section.section
          ($ :container.has-text-centered
             ($ :h2.title "Estimate ticket")
             ))
       ($ :div.columns {:class "pl-3 pr-3"}
          ($ :div.column {:class "is-one-fifths"})
          ($ :div.column
             ($ :div.field
                ($ :label.label "Jira Ticket Search:")
                ($ :div.control
                   ($ :input.input {:type "text"
                                    :value q
                                    :placeholder "Enter ticket number or subject"
                                    :on-change (fn [^js e]
                                                 (set-q! (.. e -target -value)))
                                    }))))
          ($ :div.column))
       ($ :table.table
          ($ :tbody
             (for [ticket tickets] ($ :tr {:key (str "tr-" (:key ticket))} 
                                      ($ :td {:key (str "key-" (:key ticket))} ($ :a {:href (:url ticket) :target "_blank"} (:key ticket)))
                                      ($ :td {:key (str "su-" (:key ticket))} (:summary ticket))
                                      )))

          )
))
  )



(defui app []
  ($ :div.container {:class "hero is-fullheight"}
     ($ navbar)
     ($ enter-ticket-no)
     ($ footer)))

(defonce root
  (uix.dom/create-root (js/document.getElementById "root")))

(defn render []
  (rf/dispatch-sync [:app/init-db app.db/default-db])
  (uix.dom/render-root ($ app) root))

(defn ^:export init []
  (render))
