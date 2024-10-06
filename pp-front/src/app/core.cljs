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
  (let [current-screen (hooks/use-subscribe [:app/current-screen])
        [navbar-is-active set-navbar-is-active!] (uix/use-state false)]
    ($ :nav.navbar {:role "navigation" :aria-label "main navigation"}
       ($ :a.navbar-burger {:role "button" :aria-label "menu" :aria-expanded "false" :data-target "navbarBasicExample"
                            :class (if navbar-is-active "is-active" "")
                            :on-click (fn [^js _] (set-navbar-is-active! (not navbar-is-active)))}
          ($ :span {:aria-hidden "true"})
          ($ :span {:aria-hidden "true"})
          ($ :span {:aria-hidden "true"})
          ($ :span {:aria-hidden "true"}))
       ($ :div.navbar-menu
          {:class (if navbar-is-active "is-active" "")}
          ($ :div.navbar-start
             ($ :a.navbar-item {:href "/"} "Home")
             ($ :a.navbar-item {:on-click (fn [^js _] (rf/dispatch [::handlers/set-screen "docs"]))
                                :class (if (= "docs" current-screen) "is-active" "")} "Docs")
             ($ :a.navbar-item {:class (if (= "estimate" current-screen) "is-active" "")} "Estimate")
             ($ :a.navbar-item {:on-click (fn [^js _] (rf/dispatch [::handlers/set-screen "browse-tickets"]))
                                :class (if (= "browse-tickets" current-screen) "is-active" "")} "Browse tickets"))
          ($ :div.navbar-end
             ($ :div.navbar-item
                ($ :div.buttons
                   ;; ($ :button.button {:class "is-danger1"} "💡")
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
(def SEARCH_DELAY 1000)

(defn img-for-type [type] (str "/static/img/jira-ticket/" (str/lower-case type) ".png"))

(defui browse-tickets
  "Search for ticket"
  []
  (let [[q set-q!] (uix/use-state "")
        [tickets set-tickets!]  (uix/use-state [])
        [last-query set-last-query!]  (uix/use-state "")
        [last-executed-at set-last-executed-at!] (uix/use-state 0)
        [last-modified set-last-modified!] (uix/use-state 0)
        needs-exec? (and (> last-modified 0) (> last-modified last-executed-at))
        now (.now js/Date)
        [query-is-running? set-query-is-running!] (uix/use-state false)
        [last-refreshed set-last-refreshed!] (uix/use-state now)]
    (uix/use-effect
     (fn []
       (when (and needs-exec? (> (- now last-modified) SEARCH_DELAY))
         (set-query-is-running! true)
         (go (let [response (<! (http/get (str app.util/api-url-base "/jira/search")
                                          {:query-params {"q" q}}))
                   body (:body response)]
               (set-tickets! body)
               (set-query-is-running! false)))
         (rf/dispatch [::handlers/set-log-message (str (subs (str (js/Date)) 0 33) " Executing this query: \"" q "\"")])
         (set-last-query! q)
         (set-last-executed-at! now))
         (when (and needs-exec? (<= last-refreshed last-modified))
           (js/setTimeout #(set-last-refreshed! (.now js/Date)) SEARCH_DELAY))
       js/undefined) [q last-query last-executed-at last-modified last-refreshed needs-exec? now])

    ($ :div.ticket-search
       ($ :section.section
          ($ :div.container {:class "has-text-centered"}
             ($ :h2.title "Estimate ticket")
             ))
       ($ :div.columns {:class "pl-3 pr-3"}
          ($ :div.column {:class "is-one-fifths"})
          ($ :div.column
             ($ :div.field
                ($ :label.label {:class (if query-is-running? "running-query" "")}
                   (str "Jira Ticket Search" (when query-is-running? (str ": " last-query))))

                ($ :div.control
                   ($ :input.input {:type "text"
                                    :value q
                                    :placeholder "Enter ticket number or subject"
                                    :on-change (fn [^js e]
                                                 (set-q! (.. e -target -value))
                                                 (set-last-modified! (.now js/Date))
                                                 )
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
  (let [voting-blocked? (hooks/use-subscribe [:app/voting-blocked])
        results (hooks/use-subscribe [:app/results])
        voting-finished? (not (empty? results))]
    ($ :div.box {:class "is-center"}
       ($ :h4.title {:class "is-4"} "Voting Results")
       (if (and voting-blocked? (not voting-finished?))
         ($ :progress.progress {:class "is-small is-primary" :max "100"})
         (if (not voting-finished?)
           ($ :button.button {:on-click (fn [^js _] (rf/dispatch [::handlers/reveal-results]))} "Reveal results")
           ($ :table.table ($ :tbody
                              ($ :tr $ ($ :td "Developer") ($ :td "Back") ($ :td "Front") ($ :td "QA"))
                              (for [[k v] results] ($ :tr {:key k} ($ :td k) ($ :td (get v "back")) ($ :td (get v "front")) ($ :td (get v "qa"))))))))
       (when voting-finished?
         ($ :a {:on-click (fn [^js _] (rf/dispatch [::handlers/toggle-add-comment-box]))} "Add/Replace Jira comment"))
       )))

(defui tab [{:keys [title class on-click]}]
  ($ :li {:class class} ($ :a {:on-click on-click} ($ :span title)))
)

(defui your-estimate []
  (let [my-votes (hooks/use-subscribe [:app/my-votes])
        [category set-category!] (uix/use-state :back)
        [measure set-measure!] (uix/use-state "hours")
        estimate-values (if (= measure "sp") ["0.25sp" "0.5sp" "1sp" "2sp" "3sp" "5sp" "8sp" ">8sp"] ["0.5h" "1h" "2h" "4h" "8h" "16h" "40h"])
        vote (get my-votes category)
        [previous-votes set-previous-votes!] (uix/use-state {:back "" :front "" :qa ""})
        previous-vote (get previous-votes category)
        voting-blocked (hooks/use-subscribe [:app/voting-blocked])
        ]
    ($ :div.box ($ :h4.title {:class (if voting-blocked "has-text-grey-light is-4" "is-4")} "Your estimate: "
                   (when voting-blocked ($ :span.tag {:class "is-success is-light"} "(process completed)")))
       ($ :p.has-text-right
          "Measure in: "
          ($ :a {:on-click (fn [^js _] (set-measure! "sp")) :class (str "is-link" (when (= "sp" measure) " has-text-weight-bold")) } "sp") " / "
          ($ :a {:class (str "is-link" (when (= "hours" measure) " has-text-weight-bold")) :on-click (fn [^js _] (set-measure! "hours"))} "hours"))
       ($ :div.tabs
          ($ :ul
             ($ tab {:class (if (= category :back) "is-active" "") :title "Back" :on-click (fn [^js _] (set-category! :back))})
             ($ tab {:class (if (= category :front) "is-active" "") :title "Front"  :on-click (fn [^js _] (set-category! :front))})
             ($ tab {:class (if (= category :qa) "is-active" "") :title "QA"  :on-click (fn [^js _] (set-category! :qa))})
             ))
       ($ :div.buttons
          (for [est estimate-values]
            ($ :button.button {:class (when (= est vote)
                                        (case est "8sp" "is-warning" ">8sp" "is-danger" "16h" "is-warning" "40h" "is-danger" "is-success"))
                               :key est
                               :on-click (fn [^js _]
                                           (when (and (false? voting-blocked) (not= est previous-vote))
                                             (rf/dispatch [::handlers/vote-for-ticket est category])
                                             (set-previous-votes! (assoc previous-votes category est))
                                             ))
                               } est))))))

(def INACTIVITY_SAVE_TIMEOUT 3000)

(defui add-comment [{:keys [ticket]}]
  (let [now (.now js/Date)
        [last-saved set-last-saved!] (uix/use-state 0)
        [last-modified set-last-modified!] (uix/use-state 0)
        [last-refreshed set-last-refreshed!] (uix/use-state now)
        [text set-text!] (uix/use-state "")
        needs-save? (and (> last-modified 0) (> last-modified last-saved))
        ]
    (uix/use-effect
       (fn []
         (when (and needs-save? (> (- now last-modified) INACTIVITY_SAVE_TIMEOUT))
           (http/post (str app.util/api-url-base "/add-estimate-comment")
                      {:body (app.util/tojson {"key" ticket
                                               "text" text})
                       :content-type "application/json"
                       :accept "application/json"})
           (js/console.log "saved" (- now last-modified))
           (set-last-saved! now)
           )
         (when (and needs-save? (<= last-refreshed last-modified))
           (js/setTimeout #(set-last-refreshed! (.now js/Date)) INACTIVITY_SAVE_TIMEOUT)
           )
         js/undefined) [last-refreshed needs-save? last-modified now ticket text])


    ($ :div.box ($ :h4.title {:class "is-4"} "Add estimate as task comment"
                   (when (and (> last-modified 0) (not needs-save?)) ($ :span.tag {:class "is-success is-light"} "(saved)")))
       ($ :form {:method "post"}
          ($ :div.field ($ :label.label "Comment text")
             ($ :div.control
                ($ :textarea.textarea {:name "comment" :defaultValue "Back: \nFront: \nQA: "
                                       :on-change (fn [^js e]
                                                    (set-text! (.. e -target -value))
                                                    (set-last-modified! (.now js/Date)))}))))))
)

(defui vote-screen []
  (let [estimate-ticket (hooks/use-subscribe [:app/estimate-ticket])
        show-add-comment-box (hooks/use-subscribe [:app/show-add-comment-box])
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
               )
            ($ :div.block
                     ($ :h2.subtitle "Description")
                     ($ :div.content
                        ($ :div.box #js {:dangerouslySetInnerHTML #js {:__html (:description info)}} )))
            )
         ($ :div.container {:class "mt-4"}
            ($ :div.columns
               ($ :div.column {:class "is-half"} ($ your-estimate))
               ($ :div.column {:class "is-half"} ($ results))
               )
            (when show-add-comment-box
              ($ :div.columns
                 ($ :div.column {:class "is-half"})
                 ($ :div.column {:class "is-half"} ($ add-comment {:ticket estimate-ticket}))
                 ))

            )
         )
      )
    ))

(defui header [])

(defui docs-screen []
  ($ :iframe {:src "/static/docs/README.html" :width "100%" :height "1000px"})
)

(defui app []
  (let [current-screen (hooks/use-subscribe [:app/current-screen])]
    ($ :div.container
       ($ navbar)
       ($ header)
       (case current-screen
         "browse-tickets" ($ browse-tickets)
         "estimate" ($ vote-screen)
         "docs" ($ docs-screen)
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
