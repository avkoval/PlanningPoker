(ns app.websockets)

(defonce channel (atom nil))
(defonce connection-config (atom {:url nil
                                  :receive-handler nil
                                  :onopen-handler nil
                                  :reconnect-attempts 0
                                  :max-reconnect-attempts 50
                                  :reconnect-timeout nil
                                  :reconnect-delay-ms 2000}))

(defn calculate-backoff-delay
  "Calculate exponential backoff delay with jitter"
  [attempt]
  (let [base-delay (:reconnect-delay-ms @connection-config)
        max-delay 30000 ; 30 seconds max
        exponential-delay (min max-delay 
                               (* base-delay (Math/pow 2 attempt)))
        jitter (* exponential-delay (+ 0.5 (rand 0.5)))] ; 50-100% of delay
    (int jitter)))

(declare connect!)

(defn setup-reconnect!
  "Setup reconnection logic"
  [ws-instance]
  (set! (.-onclose ws-instance)
        (fn [event]
          (let [config @connection-config
                attempts (:reconnect-attempts config)
                max-attempts (:max-reconnect-attempts config)]
            (.log js/console (str "WebSocket connection closed. Code: " 
                                  (.-code event) 
                                  ", Reason: " 
                                  (.-reason event)))
            (reset! channel nil)
            
            (when (< attempts max-attempts)
              (let [delay (calculate-backoff-delay attempts)]
                (.log js/console (str "Attempting to reconnect in " 
                                      (/ delay 1000) 
                                      " seconds... (Attempt " 
                                      (inc attempts) 
                                      " of " 
                                      max-attempts 
                                      ")"))
                (swap! connection-config assoc :reconnect-attempts (inc attempts))
                (when-let [timeout (:reconnect-timeout @connection-config)]
                  (js/clearTimeout timeout))
                (swap! connection-config assoc 
                       :reconnect-timeout
                       (js/setTimeout 
                        #(connect! (:url config) 
                                  (:receive-handler config) 
                                  (:onopen-handler config))
                        delay))))))))

(defn send-message! [msg]
  (if-let [chan @channel]
    (try
      (.send chan msg)
      (catch js/Error e
        (.error js/console "Error sending message:" e)
        (throw (ex-info "Error sending message"
                        {:message msg
                         :error e}))))
    (throw (ex-info "Couldn't send message, channel isn't open!"
                    {:message msg}))))

(defn connect! [url receive-handler onopen-handler]
  (try
    (swap! connection-config assoc 
           :url url
           :receive-handler receive-handler
           :onopen-handler onopen-handler)
    
    (if-let [chan (js/WebSocket. url)]
      (do
        (.log js/console "WebSocket connecting...")
        
        (set! (.-onmessage chan) #(->> %
                                       .-data
                                       receive-handler))
        
        (set! (.-onopen chan) 
              (fn [event]
                (.log js/console "WebSocket connected!")
                (swap! connection-config assoc :reconnect-attempts 0)
                (when onopen-handler
                  (onopen-handler event))))
        
        (setup-reconnect! chan)
        
        (reset! channel chan))
      (throw (ex-info "WebSocket Connection Failed!"
                      {:url url})))
    (catch js/Error e
      (.error js/console "Error connecting to WebSocket:" e)
      (throw (ex-info "WebSocket Connection Failed!"
                      {:url url
                       :error e})))))

(defn disconnect! []
  "Manually disconnect the websocket and prevent automatic reconnection"
  (when-let [chan @channel]
    (swap! connection-config assoc :max-reconnect-attempts 0)
    (.close chan)
    (reset! channel nil)
    (.log js/console "WebSocket manually disconnected")))

