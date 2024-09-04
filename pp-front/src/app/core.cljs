(ns app.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.spec.alpha :as s]
            [uix.core :as uix :refer [defui $]]
            [uix.dom]
            [app.hooks :as hooks]
            [app.subs]
            [app.handlers :as handlers]
            [app.fx]
            [app.db]
            [re-frame.core :as rf]
            ;;[kitchen-async.promise :as p]
            ;; [lambdaisland.fetch :as fetch]
            [cljs-http.client :as http]
            [cljs.core.async :refer [<!]]
            [lambdaisland.uri :refer [uri join]]
            [clojure.string :as str]
            ))

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
             ($ :a.navbar-item "Browse tickets"))
          ($ :div.navbar-end
             ($ :div.navbar-item
                ($ :div.buttons
                   ($ :form {:method "post" :action "/logout"}
                      ($ :button.button {:class "is-primary"}
                         ($ :strong "Logout")))))
             ))))
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
                                    }))))
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
                                        ($ :td ($ :img {:width 15 :src (str "/static/img/jira-ticket/" (str/lower-case (:type ticket)) ".png")}) " " (:type ticket))
                                        ($ :td (:original_estimate ticket))
                                        ($ :td ($ :button.button {:on-click (fn [^js _] (rf/dispatch [::handlers/start-voting (:key ticket)]))} "⏲"))
                                        ))))))))


(defui vote []
  ($ :h3.title "Lets vote!")
  )


(defui app []
  (let [current-screen (hooks/use-subscribe [:app/current-screen])]
    ($ :div.container {:class "hero is-fullheight"}
       ($ navbar)
       (case current-screen
         "browse-tickets" ($ browse-tickets)
         "estimate" ($ vote)
         ($ :div.notification {:class "is-info"} (str "no screen selected /" current-screen)))
       ($ footer))))

(defonce root
  (uix.dom/create-root (js/document.getElementById "root")))

(defn render []
  (rf/dispatch-sync [::handlers/initialize-db])
  (uix.dom/render-root ($ app) root))

(defn ^:export init []
  (render))
