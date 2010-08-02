(ns org.runa.swarmiji.mpi.sevak-proxy
  (:import (com.rabbitmq.client Channel Connection ConnectionFactory QueueingConsumer)))

(use 'org.runa.swarmiji.mpi.transport)
(use 'org.runa.swarmiji.config.system-config)
(use 'org.runa.swarmiji.utils.general-utils)
(use 'org.runa.swarmiji.sevak.bindings)
(use 'org.rathore.amit.utils.logger)
(use 'org.rathore.amit.utils.clojure)
(use 'org.rathore.amit.utils.config)
(use 'org.rathore.amit.utils.rabbitmq)
(use 'org.rathore.amit.medusa.core)

(defn sevak-queue-message-no-return [sevak-service args]
  {:sevak-service-name sevak-service
   :sevak-service-args args})

(defn sevak-queue-message-for-return [sevak-service args]
  (assoc (sevak-queue-message-no-return sevak-service args) :return-queue-name (return-queue-name sevak-service)))

(defn register-callback [return-q-name custom-handler request-object]
  (init-medusa 140)
  (let [chan (new-channel)
        consumer (consumer-for chan DEFAULT-EXCHANGE-NAME DEFAULT-EXCHANGE-TYPE return-q-name return-q-name)
        on-response (fn [msg]
                      (custom-handler (read-string msg))
                      (.queueDelete chan return-q-name)
                      (.close chan))
        f (fn []
            (send-message-on-queue (queue-sevak-q-name) request-object)
            (on-response (delivery-from chan consumer)))]
    (log-message "[" (number-of-queued-tasks) "]: Dispatching request")
    (medusa-future-thunk return-q-name f)
    {:channel chan :queue return-q-name :consumer consumer}))

(defn new-proxy 
  ([sevak-service args callback-function]
     (let [request-object (sevak-queue-message-for-return sevak-service args)
	   return-q-name (request-object :return-queue-name)
	   proxy-object (register-callback return-q-name callback-function request-object)]
       proxy-object))
  ([sevak-service args]
     (let [request-object (sevak-queue-message-no-return sevak-service args)]
       (send-message-on-queue (queue-sevak-q-name) request-object)
       nil)))

(defmacro multicast-to-sevak-servers [sevak-name & args]
  `(fanout-message-to-all (sevak-queue-message-no-return (str '~sevak-name) (list ~@args))))
