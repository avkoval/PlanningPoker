(ns app.util
  (:require [lambdaisland.uri :refer [uri]]))

(def app-uri (uri (. (. js/document -location) -href)))

(def api-url-base (str (:scheme app-uri) "://" (:host app-uri) (if (nil? (:port app-uri)) "" (str ":" (:port app-uri)))))

(defn tojson
  [ds]
  (.stringify js/JSON (clj->js ds)))
