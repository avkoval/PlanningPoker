(ns app.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [uix.core :as uix :refer [defui $]]
            [uix.dom]
            [app.hooks :as hooks]
            [app.subs]
            [app.handlers :as handlers]
            [app.fx]
            [app.db]
            [app.util]
            [app.websockets]
            [re-frame.core :as rf]
            [cljs-http.client :as http]
            [cljs.core.async :refer [<!]]
            [clojure.string :as str]
            ))


(defui navbar []
  (let [current-screen (hooks/use-subscribe [:app/current-screen])]
    ($ :nav.navbar {:role "navigation" :aria-label "main navigation"}
       ($ :a.navbar-burger {:role "button" :aria-label "menu" :aria-expanded "false" :data-target "navbarBasicExample"}
          ($ :span {:aria-hidden "true"})
          ($ :span {:aria-hidden "true"})
          ($ :span {:aria-hidden "true"})
          ($ :span {:aria-hidden "true"}))
       ($ :div.navbar-menu
          ($ :div.navbar-start
             ($ :a.navbar-item {:href "/"} "Home")
             ($ :a.navbar-item {:href "/static/docs/README.html" :target "_docs"} "Docs")
             ($ :a.navbar-item {:class (if (= "estimate" current-screen) "is-active" "")} "Estimate")
             ($ :a.navbar-item {:on-click (fn [^js _] (rf/dispatch [::handlers/browse-tickets]))
                                :class (if (= "browse-tickets" current-screen) "is-active" "")} "Browse tickets"))
          ($ :div.navbar-end
             ($ :div.navbar-item
                ($ :div.buttons
                   ($ :form {:method "post" :action "/logout"}
                      ($ :button.button {:class "is-primary"}
                         ($ :strong "Logout")))))
             )))))

(defui logger []
  (let [log-messages (hooks/use-subscribe [:app/log-messages])]
    (js/console.log log-messages)
    (when-not (empty? log-messages)
      ($ :div.container
           ($ :div.notification {:class "mt-2 mb-2 "} ($ :h5.subtitle "System logger / Events") (for [[n msg] (map-indexed #(vector %1 %2) log-messages)]
                                                           ($ :p.disappear {:key n} msg))
              ($ :button.delete {:on-click (fn [^js _] (rf/dispatch [::handlers/clear-log]))})
              ))
      ))
  )

(defui footer []
  ($ :div
     ($ logger)
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
           )))
)

(def SEARCH_STARTS_AT 5)
(def SEARCH_DELAY 1500)

(defn img-for-type [type] (str "/static/img/jira-ticket/" (str/lower-case type) ".png"))

(defui browse-tickets
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
         (go (let [response (<! (http/get (str app.util/api-url-base "/jira/search")
                                          {:query-params {"q" q}}))
                   body (:body response)
                   ]
               (set-tickets! body)
               (set-last-query! q)
               (set-last-query-time! timestamp))))
       (when (< (- timestamp last-query-time) SEARCH_DELAY)
         (js/setTimeout #(set-last-query-time! 0) SEARCH_DELAY)
         )
       js/undefined))

    ($ :div.ticket-search
       ($ :section.section
          ($ :div.container {:class "has-text-centered"}
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
                                    }))
                ($ :p.help {:class "is-info"} "You can also enter JQL by using prefix "
                         ($ :b "jql:")
                         )
                ))
          ($ :div.column))
       (when (> (count tickets) 0)
         ($ :table.table
            ($ :thead ($ :tr
                         ($ :th "Key")
                         ($ :th "Summary")
                         ($ :th "Type")
                         ($ :th "Original Estimate")
                         ($ :th "Go!")
                         ))
            ($ :tbody
               (for [ticket tickets] ($ :tr {:key (str "tr-" (:key ticket))}
                                        ($ :td {:key (str "key-" (:key ticket))}
                                           ($ :a.button {:href (:url ticket) :target "_blank" :class "is-light"} (:key ticket)))
                                        ($ :td {:key (str "su-" (:key ticket))} (:summary ticket))
                                        ($ :td ($ :img {:width 15 :src (img-for-type (:type ticket))}) " " (:type ticket))
                                        ($ :td (:original_estimate ticket))
                                        ($ :td ($ :button.button {:on-click (fn [^js _] (rf/dispatch [::handlers/start-voting (:key ticket)]))} "⏲"))
                                        ))))))))


(defui results []
  (let [results-loading (hooks/use-subscribe [:app/results-loading])]
    ($ :div.box {:class "is-center"}
       ($ :h2.title "Results")
       (if results-loading ($ :progress.progress {:class "is-small is-primary" :max "100"})
           ($ :button.button {:on-click (fn [^js _] (rf/dispatch [::handlers/reveal-results]))} "Reveal results"))))
)

(defui your-estimate []
  (let [my-vote (hooks/use-subscribe [:app/my-vote])
        [previous-vote set-previous-vote!] (uix/use-state "")
        results-loading (hooks/use-subscribe [:app/results-loading])
        ]

    ($ :div.box ($ :h2.title {:class (if results-loading "has-text-grey-light" "")} "Your estimate (story points):")
                      ($ :div.buttons
                         (for [est ["0.25" "0.5" "1" "2" "3" "5" "8" ">8"]]
                           ($ :button.button {:class (when (= est my-vote)
                                                       (case est "8" "is-warning" ">8" "is-danger" "is-success"))
                                              :key est
                                              :on-click (fn [^js _]
                                                          (when (and (false? results-loading) (not= est previous-vote))
                                                            (rf/dispatch [::handlers/vote-for-ticket est])
                                                            (set-previous-vote! est)
                                                            ))
                                              } est))))))

(defui vote-screen []
  (let [estimate-ticket (hooks/use-subscribe [:app/estimate-ticket])
        [info set-info!] (uix/use-state {})]

    (uix/use-effect
       (fn []
         (when (not (= estimate-ticket (:key info)))
           (go (let [response (<! (http/get (str app.util/api-url-base "/jira/info")
                                            {:query-params {"issue_key" estimate-ticket}}))
                     body (:body response)
                     ]
                 (set-info! body)
                 )))
         js/undefined))

    (if (not (= estimate-ticket (:key info)))
      ($ :div.block {:class "mt-auto"}
         ($ :div.notification {:class "is-info mt-4"} (str "Loading ticket information for key " estimate-ticket)
            ($ :progress.progress {:class "is-small is-primary" :max "100"})
            ))
      ($ :div.block
         ($ :div.container
            ($ :h1.title {:class "mt-4"} (str "[" estimate-ticket "] " (:summary info)))
            ($ :div.columns
               ($ :div.column {:class "is-three-quarters"}
                  ($ :table.table ($ :tbody
                                     ($ :tr ($ :th "Key") ($ :td ($ :a {:class "is-link" :href (:url info) :target "_blank"} (:url info)))
                                        ($ :th "Type") ($ :td ($ :div.tag ($ :img {:width 15 :class "mx-2" :src (img-for-type (:issuetype info))}) (:issuetype info)) ))
                                     ($ :tr ($ :th "Reporter") ($ :td (:reporter info)) ($ :th "Assignee") ($ :td (:assignee info)))
                                     ($ :tr ($ :th "Created") ($ :td (:created info)) ($ :th "Updated") ($ :td (:updated info)))
                                     ($ :tr ($ :th "Aggregate Time Spent") ($ :td (:aggregatetimespent info)))
                                     ))

                  )
               ($ :div.column 
                  ($ your-estimate)
                  ($ results)
                  )
               
               )
            ($ :div.block
                     ($ :h2.subtitle "Description")
                     ($ :div.box #js {:dangerouslySetInnerHTML #js {:__html (:description info)}} ))
            ))
      )
    ))

(defui header [])

(defui app []
  (let [current-screen (hooks/use-subscribe [:app/current-screen])]
    ($ :div.container
       ($ navbar)
       ($ header)
       (case current-screen
         "browse-tickets" ($ browse-tickets)
         "estimate" ($ vote-screen)
         ($ :div.notification {:class "is-info"} (str "no screen selected /" current-screen)))
       ($ footer))))

(defonce root
  (uix.dom/create-root (js/document.getElementById "root")))

(defn websocket-onopen []
  (js/console.log "sending 'sync' to websocket")
  (app.websockets/send-message! "sync")
  )


(defn render []
  (rf/dispatch-sync [::handlers/initialize-db])
  (app.websockets/connect! (str app.util/api-url-base "/ws") handlers/handle-response! websocket-onopen)
  (uix.dom/render-root ($ app) root))

(defn ^:export init []
  (render))
