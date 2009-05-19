(ns org.runa.swarmiji.client.client-core)

(use 'org.runa.swarmiji.mpi.sevak-proxy)
(use 'org.runa.swarmiji.utils.general-utils)
(require '(org.danlarkin [json :as json]))
(import '(java.io StringWriter))

(def swarmiji-sevak-init-value :__swarmiji-sevak-init__)

(defn attribute-from-response [sevak-data attrib-name]
  (if (= swarmiji-sevak-init-value sevak-data)
    (throw (Exception. "Sevak not complete!")))
  (if (not (= :success (keyword (sevak-data :status))))
    (throw (Exception. "Sevak has errors!")))
  (sevak-data attrib-name))

(defn response-value-from [sevak-data]
  (attribute-from-response sevak-data :response))

(defn time-on-server [sevak-data]
  (attribute-from-response sevak-data :time-on-server))

(defn on-swarm [sevak-service & args]
  (let [sevak-start (ref (System/currentTimeMillis))
	sevak-time (ref nil)
	sevak-data (ref swarmiji-sevak-init-value)
	on-swarm-response (fn [response-json-object] 
			      (dosync 
			       (ref-set sevak-data response-json-object)
			       (ref-set sevak-time (- (System/currentTimeMillis) @sevak-start))))
	on-swarm-proxy-client (new-proxy (name sevak-service) args on-swarm-response)]
    (fn [accessor]
      (cond
	(= accessor :distributed?) true
	(= accessor :complete?) (not (= swarmiji-sevak-init-value @sevak-data))
	(= accessor :value) (response-value-from @sevak-data)
	(= accessor :status) (@sevak-data :status)
	(= accessor :time-on-server) (time-on-server @sevak-data)
	(= accessor :total-time) @sevak-time
	(= accessor :time-for-messaging) (- @sevak-time (time-on-server @sevak-data))
	(= accessor :exception) (@sevak-data :exception)
	(= accessor :stacktrace) (@sevak-data :stacktrace)
	(= accessor :__inner_ref) @sevak-data
	:default (throw (Exception. (str "On-swarm proxy error - unknown message:" accessor)))))))

(defn all-complete? [swarm-requests]
  (reduce #(and (%2 :complete?) %1) true swarm-requests))

(defn wait-until-completion [swarm-requests allowed-time]
  (loop [all-complete (all-complete? swarm-requests) elapsed-time 0]
    (if (> elapsed-time allowed-time)
      (throw (RuntimeException. (str "Swarmiji reports: This operation has taken more than " allowed-time " milliseconds.")))
       (if (not all-complete)
	 (do
	   (Thread/sleep 100)
	   (recur (all-complete? swarm-requests) (+ elapsed-time 100)))))))

(defmacro from-swarm [max-time-allowed swarm-requests expr]
  (list 'do (list 'wait-until-completion swarm-requests max-time-allowed) expr))

(defn on-local [sevak-service-function & args]
  (let [response-with-time (ref {})]
    (fn [accessor]
      (cond
	(= accessor :distributed?) false
	(= accessor :complete?) true
	(= accessor :status) "success"
	(= accessor :time-on-server) (@response-with-time :time-taken)
	(= accessor :time-for-messaging) 0
	(= accessor :total-time) (@response-with-time :time-taken)
	(= accessor :exception) nil
	(= accessor :stacktrace) nil
	(= accessor :_inner_ref) nil
	(= accessor :value) (do 
			      (dosync (ref-set response-with-time (run-and-measure-timing (apply sevak-service-function args))))
			      (@response-with-time :response))
	:default (throw (Exception. (str "On-local proxy error - unknown message:" accessor)))))))
    