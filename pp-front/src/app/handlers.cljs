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
      ;; (rf/dispatch [::ws-send! (str "start voting: " jira-ticket)])
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
       (assoc :current-screen "browse-tickets"))))

(rf/reg-event-db 
 ::reveal-results
 (fn [db]
   (-> db
       (assoc :results-loading true))))

(rf/reg-event-db 
 ::set-log-message
 (fn [db [_ msg]]
   (js/console.log "got this message ok-2024-09-16-1726507078:" msg)
   (js/setTimeout
      (fn []
        (rf/dispatch [::clear-log-message 0]))
      30000)
   (-> db
       (assoc :log-messages (conj (:log-messages db) msg)))))

(defn vec-remove
  "remove elem in coll"
  [pos coll]
  (into (subvec coll 0 pos) (subvec coll (inc pos))))

(rf/reg-event-db 
 ::clear-log-message
 (fn [db [_ n]]
   (-> db
       (assoc :log-messages (vec-remove n (:log-messages db))))))

(rf/reg-event-db 
 ::clear-log
 (fn [db [_]]
   (-> db
       (assoc :log-messages []))))

(defn handle-response! [response]
  (if-let [errors (:errors response)]
    (js/console.log "Errors from ws:" errors)
    (let [[cmd argument] (str/split response #"::")
          arg (str/trim argument)]
      (case cmd
        "start voting" (rf/dispatch [::start-voting arg])
        "log" (rf/dispatch [::set-log-message arg])
        (js/console.log "No match: " argument))
      )
    ))

