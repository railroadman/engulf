(ns engulf.api
  (:require
   [engulf.job-manager :as jmgr]
   [engulf.control :as ctrl])
  (:use
   lamina.core))

(declare stop-current-job)

;; We probably don't need the lock here but it's easier than designing weird UI
;; Failure states
(def start-lock (Object.))

(defn start-job
  [params]
  (locking start-lock
    (let [job (jmgr/register-job :http-benchmark params)
          serializable-job (dissoc job :results)]
      (stop-current-job)
      (ctrl/broadcast :job-start serializable-job)
      job)))

(defn stop-current-job
  []
  (jmgr/stop-job)
  (ctrl/broadcast :job-stop))

(defn get-job
  [uuid])

(defn list-jobs
  [])

(defn del-job
  [uuid])