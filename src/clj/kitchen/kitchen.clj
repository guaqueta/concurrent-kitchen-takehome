(ns kitchen.kitchen
  (:require [clojure.core.async :as async]
            [clojure.tools.logging.readable :as log]
            [kitchen.config :as config]
            [mount.core :as mount]))

(def orders-ch
  "Queue of orders for the kitchen to process" ;; TODO Add rationale for buffering options
  (async/chan))

(def pickup-ch
  "Queue of couriers picking up specific dishes." ;; TODO clean up doc language
  (async/chan))

(def dispatch-ch
  "Queue of dispatch requests for couriers." ;; TODO clean up doc language
  (async/chan))

(def delivery-ch 
  "Queue of Couriers that have picked up their orders" ;; TODO clean up doc language
  (async/chan))

(def kitchen-report-ch (async/chan)) ;;; TODO - remove
(def courier-report-ch (async/chan)) ;;; TODO - remove
(def report-in (async/chan)) ;;; TODO - remove
(def report-out (async/mult report-in))
(async/tap report-out kitchen-report-ch)
(async/tap report-out courier-report-ch)

(defn cook [order]
  (assoc order :cooked true))

(defn make-empty-shelf []
  {"hot" {}
   "cold" {}
   "frozen" {}
   "overflow" {}})

(defn shelf-availability [shelf]
  (into {}
        (map (fn [[k v]]
               [k
                (- (get-in config/env [:shelf-capacity k])
                   (count v))])
             shelf)))

(defn abridge-order [order]
  (str (:name order) ":" (apply str (take 6 (:id order)))))

(defn abridge-shelf [shelf]
  (sort-by first
           (map (fn [[k v]] 
                  [k (count v) (sort (map abridge-order (vals v)))])
                shelf)))

(defn put-on-shelf [shelf {:keys [temp id] :as order}]
  (let [availability (shelf-availability shelf)]
    ;; TODO replace attempts at placing items on shelf with an `or` (instead of ugly nested `if`)
    (if (pos? (availability temp))
      (do
        (log/info "put-on-shelf found room on " temp)
        (assoc-in shelf [temp id] order))
      (if (pos? (availability "overflow"))
        (do
          (log/info "put-on-shelf found room on overflow")
          (assoc-in shelf ["overflow" id] order))
        ;; Try to find an order that can be moved off the overflow
        (if-let [movable-order (->> (vals (shelf "overflow"))
                                    (filter (fn [{:keys [temp]}] (pos? (availability temp))))
                                    first)]
          (do
            (log/info "put-on-shelf found an order to move" (abridge-order movable-order))
            (-> shelf
               (update-in ["overflow"] dissoc (:id movable-order))
               (assoc-in [(movable-order :temp) (:id movable-order)] movable-order)
               (assoc-in ["overflow" id] order)))
          (let [discard (rand-nth (into [] (shelf "overflow")))]
            (log/info "put-on-shelf forced to discard an order" (abridge-order (second discard)))
            (-> shelf
               (update-in ["overflow"] dissoc (first discard))
               (assoc-in ["overflow" id] order))))))))

(defn pickup-order [shelf {:keys [temp id]}]
  (let [mark-picked-up #(assoc % :picked-up true)]
    (cond
      (get-in shelf [temp id]) 
      [(update-in shelf [temp] dissoc id) (mark-picked-up (get-in shelf [temp id]))]

      (get-in shelf ["overflow" id])
      [(update-in shelf ["overflow"] dissoc id) (mark-picked-up (get-in shelf ["overflow" id]))]

      :else
      [shelf nil])))

(defn log [event order shelf]
  (if (config/env :abridged-logs)
    (log/info event {:order (abridge-order order) :shelf (abridge-shelf shelf)})
    (log/info event {:order order :shelf shelf})))

(defn sample-courier-wait-time []
  (let [min-time (config/env :courier-minimum-wait-time)
        max-time (config/env :courier-maximum-wait-time)]
    (+ min-time (Math/round (* (rand) (- max-time min-time))))))

(defn kitchen-machine []
  (let [my-id (java.util.UUID/randomUUID)
        stop-ch (async/chan)]
    (async/go-loop [shelf (make-empty-shelf)]
      (when-let [shelf (async/alt!
                         
                         stop-ch nil
                         
                         kitchen-report-ch ([_ _] (log/info "KITCHEN REPORT" my-id (abridge-shelf shelf)) shelf)

                         orders-ch
                         ([order _]
                          (log "Kitchen received order" order shelf)
                          ;; TODO respect backpressure?
                          (async/>! dispatch-ch order)
                          ;; TODO log
                          (let [shelf (put-on-shelf shelf (cook order))]
                            (log "Kitchen cooked and placed order" order shelf)
                            shelf))

                         pickup-ch
                         ([order _]
                          (log "Kitchen received courier" order shelf)
                          (let [[shelf order] (pickup-order shelf order)]
                            ;;(log "Kitchen attempted to give courier order" order shelf)
                            (if order
                              (async/>! delivery-ch order)
                              (log "Courier failed to find order" order shelf))
                            ;; TODO log
                            shelf)))]
        (recur shelf)))
    stop-ch))

(mount/defstate kitchen 
  :start (kitchen-machine)
  :stop (async/>!! kitchen true))

(defn courier-machine []
  ;; TODO documentation, also pick better name
  (let [stop-ch (async/chan)]
    (async/go-loop []
      (when (async/alt!

              stop-ch nil

              courier-report-ch ([_ _] (log/info "COURIER REPORT") true)
              
              dispatch-ch
              ([order _]
               ;; TODO docs, log
               (let [wait-time (sample-courier-wait-time)]
                 (async/go
                   (log "Courier dispatched" order nil) ;; how to log shelf
                   (async/<! (async/timeout wait-time))
                   ;; TODO log order pickup ?
                   (async/>! pickup-ch order)))
               true)

              delivery-ch
              ([order _]
               ;; The Challenge Prompt didn't specify what 'delivery'
               ;; means. All we do here is log the fact that the delivery
               ;; happened.
               (log "Courier delivered" order nil)
               true))
        (recur)))
    stop-ch))

(mount/defstate courier
  :start (courier-machine)
  :stop (async/>!! courier true))
