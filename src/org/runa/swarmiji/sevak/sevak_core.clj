(ns org.runa.swarmiji.sevak.sevak-core)

(use 'org.runa.swarmiji.mpi.transport)
(use 'org.rathore.amit.utils.rabbitmq)
(use 'org.runa.swarmiji.client.client-core)
(use 'org.runa.swarmiji.config.system-config)
(use 'org.runa.swarmiji.utils.general-utils)
(use 'org.runa.swarmiji.sevak.bindings)
(use 'org.rathore.amit.utils.config)
(use 'org.rathore.amit.utils.logger)
(use 'org.rathore.amit.utils.clojure)
(use 'org.rathore.amit.medusa.core)

(def sevaks (ref {}))

(def START-UP-REPORT "START_UP_REPORT")
(def SEVAK-SERVER "SEVAK_SERVER")

(defmacro sevak-runner [realtime? sevak-name needs-response sevak-args]
  `(fn ~sevak-args 
      (if (swarmiji-distributed-mode?)
	(if ~needs-response
	    (apply on-swarm ~realtime? ~sevak-name ~sevak-args)
	    (apply on-swarm-no-response ~realtime? ~sevak-name ~sevak-args))
	(apply on-local (cons (@sevaks ~sevak-name) ~sevak-args)))))

(defmacro create-runner [realtime? service-name needs-response? args expr]
  `(let [sevak-name# (keyword (str '~service-name))]
     (dosync (ref-set sevaks (assoc @sevaks sevak-name# {:return ~needs-response? :fn (fn ~args (do ~@expr))})))
     (def ~service-name (sevak-runner ~realtime? sevak-name# ~needs-response? ~args))))

(defmacro defsevak [service-name args & expr]
  `(create-runner true ~service-name true ~args ~expr))

(defmacro defseva [service-name args & expr]
  `(create-runner true ~service-name false ~args ~expr))

(defmacro defsevak-nr [service-name args & expr]
  `(create-runner false ~service-name true ~args ~expr))

(defmacro defseva-nr [service-name args & expr]
  `(create-runner false ~service-name false ~args ~expr))

(defn handle-sevak-request [service-name service-handler service-args ack-fn]
  (with-swarmiji-bindings
   (try
    (let [response-with-time (run-and-measure-timing 
			      (apply (:fn service-handler) service-args))
	  value (response-with-time :response)
	  time-elapsed (response-with-time :time-taken)]
      {:response value :status :success :sevak-time time-elapsed})
    (catch InterruptedException ie
      (throw ie))
    (catch Exception e 
      (log-exception e (str "SEVAK ERROR! " (class e) " detected while running " service-name " with args: " service-args))
      {:exception (exception-name e) :stacktrace (stacktrace e) :status :error})
    (finally
     (ack-fn)))))

(defn async-sevak-handler [service-handler sevak-name service-args return-q ack-fn]
  (with-swarmiji-bindings
    (let [response (merge 
		    {:return-q-name return-q :sevak-name sevak-name :sevak-server-pid (process-pid)}
		    (handle-sevak-request sevak-name service-handler service-args ack-fn))]
      (if (and return-q (:return service-handler))
	(send-message-no-declare return-q response)))))

(defn sevak-request-handling-listener [req-str ack-fn]
  (with-swarmiji-bindings
    (try
      (let [req (read-string req-str)
            service-name (req :sevak-service-name) service-args (req :sevak-service-args) return-q (req :return-queue-name)
            service-handler (@sevaks (keyword service-name))]
        (log-message "[ in-q pool completed" (number-of-queued-tasks) (current-pool-size) (completed-task-count) "]: Received request for" service-name "with args:" service-args "and return-q:" return-q)
      (if (nil? service-handler)
	(throw (Exception. (str "No handler found for: " service-name))))
      (medusa-future-thunk return-q #(async-sevak-handler service-handler service-name service-args return-q ack-fn)))
      (catch Exception e
        (log-message "Error in sevak-request-handling-listener:" (class e))
        (log-exception e)))))

(defn start-processor [routing-key start-log-message]
  (future 
   (with-swarmiji-bindings 
     (try
      (log-message start-log-message)
      (with-prefetch-count (rabbitmq-prefetch-count)
        (start-queue-message-handler routing-key routing-key sevak-request-handling-listener))
      (log-message "Done with sevak requests!")
      (catch Exception e
        (log-message "Error in sevak-servicing future!")
        (log-exception e))))))

(defn boot-sevak-server []
  (log-message "Starting sevaks in" *swarmiji-env* "mode")
  (log-message "System config:" (operation-config))
  (log-message "Medusa server threads:" (medusa-server-thread-count))
  (log-message "Medusa client threads:" (medusa-client-thread-count))
  (log-message "RabbitMQ prefetch-count:" (rabbitmq-prefetch-count))
  (log-message "Sevaks are offering the following" (count @sevaks) "services:" (keys @sevaks))
  (init-rabbit)
  (init-medusa (medusa-server-thread-count))
  ;(send-message-on-queue (queue-diagnostics-q-name) {:message_type START-UP-REPORT :sevak_server_pid (process-pid) :sevak_name SEVAK-SERVER})

  (future 
   (with-swarmiji-bindings
     (let [broadcasts-q (random-queue-name "BROADCASTS_")]
       (try
        (log-message "Listening for update broadcasts...")
        (.addShutdownHook (Runtime/getRuntime) (Thread. #(with-swarmiji-bindings (delete-queue broadcasts-q))))
        (start-queue-message-handler (sevak-fanout-exchange-name) FANOUT-EXCHANGE-TYPE broadcasts-q (random-queue-name) sevak-request-handling-listener)
        (log-message "Done with broadcasts!")    
        (catch Exception e         
          (log-message "Error in update broadcasts future!")
          (log-exception e))))))

  (start-processor (queue-sevak-q-name true) "Starting to serve realtime sevak requests..." )
  (start-processor (queue-sevak-q-name false) "Starting to serve non-realtime sevak requests..." )
  (log-message "Sevak Server Started!"))
