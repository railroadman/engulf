(ns engulf.benchmark
  (:require [engulf.runner :as runner]
            [engulf.utils :as utils]
            [clojure.tools.logging :as log])
  (:use clojure.tools.logging
        noir-async.utils
        lamina.core
        [engulf.utils :only [send-bench-msg]]
        [engulf.worker :only [work warmup create-single-url-worker]]
        [engulf.recorder :only [create-recorder
                                record-result
                                record-error
                                record-start
                                processed-stats
                                record-end]])
  (:import java.util.concurrent.atomic.AtomicLong
           java.net.URL))

(def current-benchmark (ref nil))

(defprotocol Benchmarkable
  (start [this] "Start the benchmark")
  (stop [this] "Stop the benchmark")
  (increment-and-check-run-count [this] "Internal use only")
  (check-result-recordability? [this] "Internal use only")
  (handle-result [this res-ch] "Asynchronously handle a result channel returned by a worker's 'work' method")
  (broadcast-state [this] "Enqueues the current state/stats on the output ch")
  (broadcast-at-interval [this millis])
  (stats [this] "Returns processed stats"))
  
(defrecord Benchmark
  [state url max-runs ^AtomicLong run-count workers recorder output-ch broadcast-task]
  Benchmarkable
  
  (start [this]
    (if (not (compare-and-set! state :initialized :started))
      (io! (log/warn (str "Could not start from: @state")))
      (do
        (record-start recorder)
        (doseq [worker @workers]
          (if-let [res-ch (work worker)]
            (handle-result this res-ch)))
        (compare-and-set! broadcast-task nil
                          (broadcast-at-interval this 200)))))

  (stop [this]
    (println "Stopping run at: " max-runs " runs.")
    (when (compare-and-set! state :started :stopped)
      (do
        (record-end recorder)
        (doseq [worker @workers]
          (swap! (:state worker) (fn [s] :stopped)))
        (broadcast-state this))))

  (increment-and-check-run-count [this]
    (let [n (.incrementAndGet run-count)]
      (cond
       (> max-runs n) :under
       (= max-runs n) :thresh
       :else          :over)))
        
  (check-result-recordability? [this]
    (when (= @state :started)
      (let [thresh-status (increment-and-check-run-count this)]
        (condp = thresh-status
            :thresh (do (stop this) true)
            :under  true
            :over   false))))

  (handle-result [this res-ch]
    (on-success res-ch (fn [[result next-result]]
     (let [{:keys [worker-id]} result]
       (when (check-result-recordability? this)
         (record-result recorder worker-id result)))
     (handle-result this next-result)))
    (on-error res-ch (fn [[result next-result]]
      (let [{:keys [worker-id error]} result]
        (log/warn error (str "Error received from worker" worker-id))
        (when (check-result-recordability? this)
          (record-error recorder worker-id error))
        (handle-result this next-result)))))
  
  (broadcast-state [this]
    (send-bench-msg output-ch :state {:state @state
                                      :url url
                                      :workers (count @workers)
                                      :max-runs max-runs})
    (send-bench-msg output-ch :stats (stats this)))

  (broadcast-at-interval [this millis]
   (set-interval millis #(broadcast-state this)))
         
  (stats [this]
    (processed-stats recorder)))

(defn create-workers-for-benchmark
  "Creates a vector of workers instantiated via worker-fn, which
   must be  afunction that takes a worker-id, a success handler and a failure
   handler"
  [worker-fn benchmark worker-count]
  (compare-and-set! (:workers benchmark) nil
   (vec (map (fn [worker-id]
               (let [worker (worker-fn worker-id)]
                 worker))
             (range worker-count)))))

(defn create-benchmark
 "Create a new Benchmark record. This encapsulates the full benchmark state"
 [url worker-count max-runs worker-fn]
 (let [recorder (create-recorder)
       benchmark (Benchmark. (atom :initialized)
                             url
                             max-runs
                             (AtomicLong.) ; run-count
                             (atom nil) ; workers
                             recorder
                             (permanent-channel) ; output ch
                             (atom nil))] ; broadcast-task
   (receive-all (:output-ch benchmark) (fn [_] )) ; Ground
   (create-workers-for-benchmark worker-fn benchmark worker-count)
   benchmark))
     
(defn create-single-url-benchmark
  "Create a new benchmark. You must call start on this to begin"
  [url concurrency requests]
  (let [worker-fn (partial create-single-url-worker url)]
    (create-benchmark url concurrency requests worker-fn)))

(defn replace-current-benchmark
  "Replace the current-benchmark with a newly initialized one"
  [benchmarker]
  (dosync
   (let [cb @current-benchmark]
     ;; Do nothing if another benchmark is awaiting start
     (cond (and cb (= :initialized (:state cb)))
           false
           :else
           (do
             ;; Cancel the current benchmark's broadcast task
             (when cb
               (when-let [bt @(:broadcast-task cb)]
                 (.cancel bt)))
             (ref-set current-benchmark benchmarker))))))
  
(defn run-new-benchmark
  "Attempt to run a new benchmark"
  [url concurrency requests]
  (let [benchmarker (create-single-url-benchmark url concurrency requests)
        set-benchmark (replace-current-benchmark benchmarker)]
    (condp
        (= false set-benchmark)
        :else
        (do
          (start @current-benchmark)
          @current-benchmark))))

(defn stop-current-benchmark
  "Stop current benchmark if it exists"
  []
  (when-let [b @current-benchmark] (stop b)))