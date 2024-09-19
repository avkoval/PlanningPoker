(ns app.db)

(def default-db
  {:current-screen "browse-tickets"
   :estimate-ticket nil
   :vote-started-by ""
   :log-messages []
   :vote {:back "" :front "" :qa ""}
   :my-vote ""
   :results-loading false
   :results []
   :voting-results []
   :members-online []
   })
