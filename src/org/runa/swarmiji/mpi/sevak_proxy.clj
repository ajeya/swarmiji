(ns org.runa.swarmiji.mpi.sevak-proxy)

(import '(java.util Random))
(require '(org.danlarkin [json :as json]))
(import '(net.ser1.stomp Client Listener))
(use '[clojure.contrib.duck-streams :only (spit)]) 


(defn return-queue-name []
  (str (.nextInt (Random. ) 10000000000)))

(defn sevak-queue-message [sevak-service args]
  (let [return-q-name (return-queue-name)]
    {:return-queue-name return-q-name
     :sevak-service-name sevak-service
     :sevak-service-args args}))

(defn listener-proxy [q-client return-q-name custom-handler]
  (proxy [Listener] []
    (message [headerMap messageBody]
	     (custom-handler (json/decode-from-str messageBody))
	     (.unsubscribe q-client return-q-name)
	     (.disconnect q-client))))

(defn register-callback [q-client return-q-name custom-handler]
  (let [client (Client. "tank.cinchcorp.com" 61613, "guest" "guest")
	callback (listener-proxy q-client return-q-name custom-handler)]
    (.subscribe client return-q-name callback)))

(defn send-on-transport [q-message]
  (let [client (Client. "tank.cinchcorp.com" 61613, "guest" "guest")
	q-message-string (json/encode-to-str q-message)]
    (.send client "RUNA_SWARMIJI_TRANSPORT" q-message-string)
    client))

(defn new-proxy [sevak-service args]
  (let [request-json-object (sevak-queue-message sevak-service args)
	return-q-name (request-json-object :return-queue-name)
	_ (println "request:" request-json-object)
	q-client (send-on-transport request-json-object)]
    (register-callback q-client return-q-name 
		       (fn [response-json]
			 (spit "/Users/amit/workspace/swarmiji/test.out" (json/encode-to-str response-json))))))