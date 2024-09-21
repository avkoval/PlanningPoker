(ns app.db)

(def default-db
  {:current-screen "browse-tickets"
   :estimate-ticket nil
   :vote-started-by ""
   :log-messages []
   :vote {:back "" :front "" :qa ""}
   :my-vote ""
   :voting-blocked false
   :results []
   :voting-results []
   :members-online []
   :show-add-comment-box false
   })
