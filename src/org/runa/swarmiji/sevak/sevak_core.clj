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

(defmacro sevak-runner [sevak-name needs-response sevak-args]
  `(fn ~sevak-args 
      (if (swarmiji-distributed-mode?)
	(if ~needs-response
	    (apply on-swarm (cons ~sevak-name ~sevak-args))
	    (apply on-swarm-no-response (cons ~sevak-name ~sevak-args)))
	(apply on-local (cons (@sevaks ~sevak-name) ~sevak-args)))))

(defmacro defsevak [service-name args & expr]
  `(let [sevak-name# (keyword (str '~service-name))]
     (dosync (ref-set sevaks (assoc @sevaks sevak-name# {:return Boolean/TRUE :fn (fn ~args (do ~@expr))})))
     (def ~service-name (sevak-runner sevak-name# Boolean/TRUE ~args))))

(defmacro defseva [service-name args & expr]
  `(let [seva-name# (keyword (str '~service-name))]
     (dosync (ref-set sevaks (assoc @sevaks seva-name# {:return Boolean/FALSE :fn (fn ~args (do ~@expr))})))
     (def ~service-name (sevak-runner seva-name# Boolean/FALSE ~args))))

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
      (let [f (medusa-future-thunk return-q #(async-sevak-handler service-handler service-name service-args return-q ack-fn))]
;        (when (> (number-of-queued-tasks) 10)
;          (.get f))
        f))
      (catch Exception e
        (log-message "Error in sevak-request-handling-listener:" (class e))
        (log-exception e)))))

(defn boot-sevak-server []
  (log-message "Starting sevaks in" *swarmiji-env* "mode")
  (log-message "System config:" (operation-config))
  (log-message "MPI transport Q:" (queue-sevak-q-name))
  (log-message "MPI diagnostics Q:" (queue-diagnostics-q-name))
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
  
  (future 
   (with-swarmiji-bindings 
     (try
      (log-message "Starting to serve sevak requests...")
      (with-prefetch-count (rabbitmq-prefetch-count)
        (start-queue-message-handler (queue-sevak-q-name) (queue-sevak-q-name) sevak-request-handling-listener))
      (log-message "Done with sevak requests!")
      (catch Exception e
        (log-message "Error in sevak-servicing future!")
        (log-exception e)))))
  
(log-message "Sevak Server Started!"))
