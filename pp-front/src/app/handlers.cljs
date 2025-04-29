(ns app.handlers
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [re-frame.core :as rf]
            ;; [day8.re-frame.tracing :refer-macros [fn-traced]]
            [app.db :as db]
            [app.fx :as fx]
            [app.util]
            [app.websockets]
            [cljs-http.client :as http]
            [cljs.core.async :refer [<!]]
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
            (assoc :voting-blocked false)
            (assoc :results [])
            (assoc :show-add-comment-box false)
            (assoc :vote {:back "" :front "" :qa ""})
            (assoc :current-screen "estimate")
        )
    ))

(rf/reg-event-db
 ::vote-for-ticket
  (fn [db [_ vote category]]
    (http/post (str app.util/api-url-base "/vote")
               {:body (app.util/tojson {"key" (:estimate-ticket db)
                                        "stamp" nil
                                        "vote" vote
                                        "category" category})
                :content-type "application/json"
                :accept "application/json"})
    (-> db (assoc-in [:vote category] vote))))

;; (rf/reg-event-fx
;;  ::fetch-user-info
;;  (fn [{:keys [db]} _]
;;    {:http-xhrio {:method          :get
;;                  :uri             (str app.util/api-url-base "/userInfo")
;;                  :response-format (http/json-response-format {:keywords? true})
;;                  :on-success      [::user-info-success]
;;                  :on-failure      [::user-info-failure]}}))

;; (rf/reg-event-db
;;  ::user-info-success
;;  (fn [db [_ response]]
;;    (js/console.log "User info retrieved successfully:" response)
;;    (assoc db :user-info response)))

;; (rf/reg-event-db
;;  ::user-info-failure
;;  (fn [db [_ error]]
;;    (js/console.log "Failed to retrieve user info:" error)
;;    db))

(rf/reg-event-fx
 ::fetch-user-info
 (fn [_]
   (go (let [response (<! (http/get (str app.util/api-url-base "/userInfo")
                                 {:with-credentials? false}))]
      (js/console.log )
      (when (= (:status response) 200)
        (rf/dispatch [::save-userinfo (:body response)]))
      (js/console.log (clj->js (:body response)))))
   {}
   ))

(rf/reg-event-db
 ::save-userinfo
  (fn [db [_ user-info]]
    (-> db 
        (assoc :user-info-loaded true)
        (assoc :user-info user-info)
        )))

(rf/reg-event-db
 ::reset-my-vote
  (fn [db [_]]
    (-> db (assoc :my-vote ""))))

(rf/reg-event-db
 ::set-screen
 (fn [db [_ screen]]
   (-> db
       (assoc :current-screen screen))))


(rf/reg-event-db
 ::reveal-results
 (fn [db]
   (http/post (str app.util/api-url-base "/vote/finish")
               {:body (app.util/tojson {})
                :content-type "application/json"
                :accept "application/json"})
   (-> db
       (assoc :voting-blocked true))))

(def LOG_DISAPPEARS_AFTER 300)

(rf/reg-event-db
 ::set-log-message
 (fn [db [_ msg]]
   (js/setTimeout
      (fn []
        (rf/dispatch [::clear-log-message 0]))
      (* LOG_DISAPPEARS_AFTER 1000))
   (-> db
       (assoc :log-messages (conj (:log-messages db) msg)))))

(defn vec-remove
  "remove elem in coll"
  [pos coll]
  (into (subvec coll 0 pos) (subvec coll (inc pos))))

(rf/reg-event-db
 ::clear-log-message
 (fn [db [_ n]]
   (if (> (count (:log-messages db)) n)
     (assoc db :log-messages (vec-remove n (:log-messages db)))
     db)))

(rf/reg-event-db
 ::clear-log
 (fn [db [_]]
   (-> db
       (assoc :log-messages []))))

(rf/reg-event-db
 ::set-results
 (fn [db [_ results]]
   (-> db
       ;; (assoc :voting-blocked false)
       (assoc :results results))))

(rf/reg-event-db
 ::toggle-add-comment-box
 (fn [db [_]]
   (-> db
       (assoc :show-add-comment-box (not (:show-add-comment-box db))))))



(defn handle-response! [response]
  (if-let [errors (:errors response)]
    (js/console.log "Errors from ws:" errors)
    (let [[cmd argument] (str/split response #"::")
          arg (str/trim argument)]
      (case cmd
        "start voting" (rf/dispatch [::start-voting arg])
        "log" (rf/dispatch [::set-log-message arg])
        "results" (rf/dispatch [::set-results (js->clj (js/JSON.parse argument))])
        (js/console.log "No match: " argument))
      )
    ))
