(ns engulf.formulas.http-benchmark
  (:require [lamina.core :as lc]
            [clojure.tools.logging :as log]
            [clojure.set :as cset])
  (:use [engulf.formula :only [Formula register]]
        [engulf.utils :only [set-timeout]]
        [aleph.http :only [http-client http-request]])
  (:import fastPercentiles.PercentileRecorder))

(defn increment-keys
  "Given a map and a list of keys, this will return an identical map with those keys
   with those keys incremented.
    
   Keys with a null value will be set to 1."
  [src-map & xs]
  (into src-map (map #(vector %1 (inc (get src-map %1 0))) xs)))

(defn http-result
  [status & opts]
  (assoc {:status status} opts))

(defn error-result
  [throwable]
  (http-result :thrown :throwable throwable))

(defn success-result
  [])

(defn empty-aggregation
  [params]
  {:type :aggregate
   :runtime nil
   :runs-total 0
   :runs-succeeded 0
   :runs-failed 0
   :response-code-counts {}
   :by-start-time {}
   :runtime-percentiles-recorder (PercentileRecorder. (or (:timeout params) 10000))})

(defn run-request
  [params callback]
  (let [res (lc/result-channel)
        started-at (System/currentTimeMillis)] ; (http-request {:url (:url params)})
    (set-timeout 1 #(lc/success res {:started-at started-at
                                     :status 200
                                     :ended-at (System/currentTimeMillis)}))
    (lc/on-realized res #(callback %1) #(callback %1))))

(defn successes
  [results]
  (filter #(not (isa? (class %1) Throwable)) results))

(defn count-successes
  [{:keys [runs-total runs-failed runs-succeeded] :as stats} results]
  (let [total (+ runs-total (count results))
        succeeded (+ runs-succeeded (count (successes results)))
        failed (+ runs-failed (- runs-total runs-succeeded))]
    (assoc stats :runs-total total :runs-failed failed :runs-succeeded succeeded)))

(defn count-times
  [{runtime :runtime :as stats} results]
  (reduce #(- (:ended-at %1) (:started-at %1)) (successes results)))

(defn aggregate
  [params results]
  (let [stats (empty-aggregation params)]
    (-> stats
        (count-successes results)
        (count-times results))))

(defn validate-params [params]
  (let [diff (cset/difference #{:url :method :concurrency} params)]
    (when (not (empty? diff))
      (throw (Exception. (str "Invalid parameters! Missing keys: " diff))))))

(defprotocol IHttpBenchmark
  (run-repeatedly [this]))

(defrecord HttpBenchmark [state params res-ch]
  IHttpBenchmark
  (run-repeatedly [this]
    (run-request
     params
     (fn req-resp [res]
       (when (= @state :started) ; Discard results and don't recur when stopped
         (lc/enqueue res-ch res)
         (run-repeatedly this)))))  
  Formula
  (start-relay [this]
    
    )
  (start-edge [this]
    (validate-params params)
    (if (not (compare-and-set! state :initialized :started))
      (throw (Exception. (str "Expected state :initialized, not: ") @state))
      (do
        (dotimes [t (Integer/valueOf (:concurrency params))] (run-repeatedly this))
        (lc/map* (partial aggregate params) (lc/partition-every 250 res-ch)))))
  (stop [this]
    (reset! state :stopped)
    (lc/close res-ch)))

(defn init-benchmark
  [params]
  (HttpBenchmark. (atom :initialized)
                  params
                  (lc/channel)))

(register :http-benchmark init-benchmark)
