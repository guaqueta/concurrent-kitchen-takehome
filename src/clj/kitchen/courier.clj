(ns kitchen.courier
  (:require [clojure.core.async :as async]
            [clojure.tools.logging.readable :as log]
            [kitchen.config :as config]
            [kitchen.shelf :as shelf]))

(def courier-ch
  "Incoming queue of requests to dispatch couriers" ;; TODO Add rationale for buffering options
  (async/chan))

(defn sample-courier-wait-time []
  (+ (config/env :courier-minimum-wait-time)
     (Math/round 
      (* (rand) (- (config/env :courier-maximum-wait-time)
                   (config/env :courier-minimum-wait-time)))))
  ;;0 ;;xxx
  )

(defn process-couriers
  "Process incoming dispatch requests for couriers. Each time we get a dispatch
  request we want to accept it instantly, and then turn that into a little
  machine that waits a prescribed amount of time before issuing a pickup
  request to the shelf."
  []
  (async/go-loop []
    (when-let [order (async/<! courier-ch)]
      ;; TODO log receipt of order
      #_(log/info "courier service processing dispatch request" (order :name) (shelf/abridged-shelf @shelf/shelf-db))
      (async/go
        (let [wait-time (sample-courier-wait-time)]
          #_(log/info "courier for will wait"  wait-time " before picking up" (order :name) (shelf/abridged-shelf @shelf/shelf-db))
          (async/<! (async/timeout wait-time))
          ;; TODO log order pickup ?
          #_(log/info "courier for" (order :name) ":" (subs (order :id) 0 4) "is about to pickup" (shelf/abridged-shelf @shelf/shelf-db))
          (async/>! shelf/pickup-ch order)
          #_(if (async/>! shelf/pickup-ch order)
            (log/info "courier order" (order :name) ":" (subs (order :id) 0 4) "delivered" (shelf/abridged-shelf @shelf/shelf-db))
            (log/info "courier failed to get order" (order :name) (shelf/abridged-shelf @shelf/shelf-db)))))
      (recur))))

#_(process-couriers)
