(ns app.handlers
  (:require [re-frame.core :as rf]
            ;; [day8.re-frame.tracing :refer-macros [fn-traced]]
            [app.db :as db]
            [app.fx :as fx]
            [app.util]
            [app.websockets]
            [cljs-http.client :as http]
            [clojure.string :as str]
            ))

(def load-todos (rf/inject-cofx :store/todos "uix-starter/todos"))
(def store-todos (fx/store-todos "uix-starter/todos"))

(rf/reg-event-db
 ::initialize-db
 (fn [_ _]
   db/default-db))

(rf/reg-event-fx
 ::ws-send!
 (fn [{:keys [db]} [_ msg]]
   (app.websockets/send-message! msg)
   (js/console.log "call to ws-send!" msg)
   {:db (dissoc db :ws/server-errors)}))

(rf/reg-event-db
 ::start-voting
  (fn [db [_ jira-ticket]]
    (when-not (= (:estimate-ticket db) jira-ticket)
      (js/console.log "need to dispatch" (:estimate-ticket db) jira-ticket)
      (rf/dispatch [::ws-send! (str "start voting: " jira-ticket)])
      )
    (js/console.log "call to start-voting for:" jira-ticket)
    (-> db
            (assoc :estimate-ticket jira-ticket)
            (assoc :current-screen "estimate")
        )
    ))

(rf/reg-event-db 
 ::vote-for-ticket
  (fn [db [_ vote]]
    (http/post (str app.util/api-url-base "/vote")
               {:body (app.util/tojson {"key" (:estimate-ticket db)
                                        "stamp" nil
                                        "vote" vote})
                :content-type "application/json"
                :accept "application/json"})
    (-> db (assoc :my-vote vote))))


(rf/reg-event-db 
 ::reset-my-vote
  (fn [db [_]]
    (-> db (assoc :my-vote ""))))


(rf/reg-event-db 
 ::browse-tickets
  (fn [db]
    (-> db
            (assoc :current-screen "browse-tickets")
        )
    ))


(defn handle-response! [response]
  (if-let [errors (:errors response)]
    (js/console.log "Errors from ws:" errors)
    (let [[command argument] (str/split response #":")]
      (js/console.log response)
      (case command
        "start voting" (rf/dispatch [::start-voting (str/trim argument)])
        :else (js/console.log "No match: " argument))
      )
    ))

