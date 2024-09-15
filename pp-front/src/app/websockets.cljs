(ns app.websockets)

(defonce channel (atom nil))

(defn send-message! [msg]
  (if-let [chan @channel]
    (.send chan msg)
    (throw (ex-info "Couldn't send message, channel isn't open!"
                    {:message msg}))))

(defn connect! [url receive-handler onopen-handler]
  (if-let [chan (js/WebSocket. url)]
    (do
      (.log js/console "Connected!")
      (set! (.-onmessage chan) #(->> %
                                     .-data
                                     receive-handler))
      (set! (.-onopen chan) #(->> %
                                     .-data
                                     onopen-handler))
      (reset! channel chan)
      )
    (throw (ex-info "Websocket Connection Failed!"
                    {:url url}))))

