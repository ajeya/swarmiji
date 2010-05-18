(ns org.runa.swarmiji.mpi.supervised-threadpool
  (:use org.runa.swarmiji.sevak.bindings
        org.rathore.amit.utils.clojure
        org.rathore.amit.utils.logger)
  (:import java.util.concurrent.ExecutorService
           java.util.concurrent.Executors))

(def THREAD-TIMEOUT-MILLIS 20000)
(def SUPERVISE-EVERY-MILLIS 10000)
(def THREADPOOL-SIZE (* 3 (.availableProcessors (Runtime/getRuntime))))
(def THREADPOOL (Executors/newFixedThreadPool THREADPOOL-SIZE))

(def running-sevaks (ref {}))

(defn claim-thread [return-queue-name]
  (let [thread-info {:thread (Thread/currentThread) :rqn return-queue-name :started (System/currentTimeMillis)}]
    (dosync (alter running-sevaks assoc return-queue-name thread-info))))

(defn mark-completion [return-queue-name]
  (dosync (alter running-sevaks dissoc return-queue-name)))

(defn on-swarmiji-future [return-queue-name thunk]
  (let [work (fn []
               (claim-thread return-queue-name)
               (thunk))]
    (.submit THREADPOOL work)))

(defn preempt-swarmiji-future [[rqn {:keys [thread]}]]
  (.interrupt thread)
  (mark-completion rqn))

(defn running-over? [[return-queue-name {:keys [started]}]]
  (> (- (System/currentTimeMillis) started) THREAD-TIMEOUT-MILLIS))

(defn preempt-old-futures []
  (let [to-preempt (filter running-over? @running-sevaks)]
    (doseq [frs to-preempt]
      (preempt-swarmiji-future frs))))

(defn supervise [repeat-after-millis]
  (while true
    (preempt-old-futures)
    (Thread/sleep repeat-after-millis)))

(def start-supervisor 
     (create-runonce 
      (fn []
        (future (supervise SUPERVISE-EVERY-MILLIS)))))