(ns app.handlers
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [re-frame.core :as rf]
            ;; [day8.re-frame.tracing :refer-macros [fn-traced]]
            [app.db :as db]
            [app.fx :as fx]
            [app.util]
            [cljs-http.client :as http]
            [cljs.core.async :refer [<!]]
            ))

(def load-todos (rf/inject-cofx :store/todos "uix-starter/todos"))
(def store-todos (fx/store-todos "uix-starter/todos"))

;; (rf/reg-event-fx :app/init-db
;;   [load-todos]
;;   (fn [{:store/keys [todos]} [_ default-db]]
;;     {:db (update default-db :todos into todos)}))

;; (rf/reg-event-fx :todo/add
;;   [(rf/inject-cofx :time/now) store-todos]
;;   (fn [{:keys [db]
;;         :time/keys [now]}
;;        [_ todo]]
;;     {:db (assoc-in db [:todos now] todo)}))

;; (rf/reg-event-db :todo/remove
;;   [store-todos]
;;   (fn [db [_ created-at]]
;;     (update db :todos dissoc created-at)))

;; (rf/reg-event-db :todo/set-text
;;   [store-todos]
;;   (fn [db [_ created-at text]]
;;     (assoc-in db [:todos created-at :text] text)))

;; (rf/reg-event-db :todo/toggle-status
;;   [store-todos]
;;   (fn [db [_ created-at]]
;;     (update-in db [:todos created-at :status] {:unresolved :resolved
;;                                                :resolved :unresolved})))

(rf/reg-event-db
 ::initialize-db
 (fn [_ _]
   db/default-db))

(rf/reg-event-db 
 ::start-voting
  (fn [db [_ jira-ticket]]
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
