(ns app.subs
  (:require [re-frame.core :as rf]))

(rf/reg-sub :app/todos
  (fn [db _]
    (:todos db)))

(rf/reg-sub :app/current-screen
  (fn [db _]
    (:current-screen db)))

(rf/reg-sub :app/estimate-ticket
  (fn [db _]
    (:estimate-ticket db)))

(rf/reg-sub :app/voting-results
  (fn [db _]
    (:voting-results db)))

(rf/reg-sub :app/my-vote
  (fn [db _]
    (:my-vote db)))

(rf/reg-sub :app/my-votes
  (fn [db _]
    (:vote db)))

(rf/reg-sub :app/members-online
  (fn [db _]
    (:members-online db)))

(rf/reg-sub :app/log-messages
  (fn [db _]
    (:log-messages db)))

(rf/reg-sub :app/voting-blocked
  (fn [db _]
    (:voting-blocked db)))

(rf/reg-sub :app/results
  (fn [db _]
    (:results db)))

(rf/reg-sub :app/show-add-comment-box
  (fn [db _]
    (:show-add-comment-box db)))
