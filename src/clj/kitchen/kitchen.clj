(ns kitchen.kitchen
  "Emulate a kitchen that receives, processes, and delivers orders.

  There are two services: the kitchen-service and the courier-service. The
  kitchen-service receives orders on the `orders-ch` channel, cooks them,
  dispatches couriers, and hands off cooked orders to arriving couriers. It
  has internal state (the pick-up-area) to hold orders while waiting for
  couriers to pickup. The pick-up-area has limited capacity, which is
  configurable via the :shelf-capacity value in config.edn. If there are too
  many incoming orders, eventually some orders will be dropped and the courier
  will fail to pick them up.

  The courier-service receives dispatch requests and schedules couriers to go
  pick up orders. It also delivers orders that have been picked up. The
  couriers wait a random amount of time before pickup, which is configurable
  via the :courier-minimum-wait-time and :courier-maximum-wait-time values in
  config.edn.

  We use `mount` to manage the service lifecycle."
  (:require [clojure.core.async :as async]
            [clojure.tools.logging.readable :as log]
            [kitchen.config :as config]
            [mount.core :as mount]))

(def orders-ch
  "Orders for the kitchen to process. This is the entry point for the customer
  to place orders. Other channels below are for internal use between the
  kitchen-service and courier-service."
  (async/chan))

(def pickup-ch
  "Couriers picking up specific orders from the kitchen. The courier-service
  sends orders to this channel for the kitchen to fulfill."
  (async/chan))

(def dispatch-ch
  "Dispatch requests for couriers. As the kitchen receives new orders, it asks
  the courier-service to schedule a courier by sending orders here."
  (async/chan))

(def delivery-ch 
  "Couriers that have picked up their orders. The kitchen hands-off cooked
  orders here for delivery."
  (async/chan))

(defn- cook 
  "Cook the order. Essentially a placeholder for presumably more complex logic
  to come. Returns an order."
  [order]
  (assoc order :cooked true))

(defn make-empty-pick-up-area
  "Returns a pick-up-area with no dishes on it. A pick-up-area is a map of
  temperatures to shelves, where each shelf in turn is a map of order ids to
  orders. We represent shelves as maps so that we can efficiently hand-off
  specific dishes to arriving couriers. This does imply that we assume order
  ids to be unique."
  []
  {"hot" {}
   "cold" {}
   "frozen" {}
   "overflow" {}})

(defn abbrev-order [order]
  (str (:name order) ":" (apply str (take 6 (:id order)))))

(defn abbrev-pick-up-area [pick-up-area]
  (sort-by first
           (map (fn [[k v]] 
                  [k (count v) (sort (map abbrev-order (vals v)))])
                pick-up-area)))

(defn- pick-up-area-availability
  "Return a map with the number of available slots for each shelf in the
  pick-up-area."
  [pick-up-area]
  (into {}
        (map (fn [[k v]]
               [k
                (- (get-in config/env [:shelf-capacity k])
                   (count v))])
             pick-up-area)))

(defn put-on-pick-up-area
  "Put the given order on the best available shelf of the pick-up-area.

  First the shelf of the appropriate temperature. If that fails, try to put
  the order on the overflow shelf. If that fails, try to make room by finding
  an item on the overflow shelf that can be moved to its appropriate shelf. If
  that fails, randomly pick an item from the overflow shelf to drop. Always
  succeeds in putting the order down.

  Returns a map describing the results of the action:
  - :pick-up-area updated pick-up-area
  - :shelf the key to the shelf where the order was placed
  - :action one of :moved or :discarded, if those actions had to be taken to
            make room for the order.
  - :affected-order the affected order if an action was taken"
  [pick-up-area {:keys [temp id] :as order}]
  (let [availability (pick-up-area-availability pick-up-area)]
    (cond
      (pos? (availability temp)) {:pick-up-area (assoc-in pick-up-area [temp id] order)
                                  :shelf temp}
      (pos? (availability "overflow")) {:pick-up-area (assoc-in pick-up-area ["overflow" id] order)
                                        :shelf "overflow"}
      :else (if-let [move-order (->> (vals (pick-up-area "overflow"))
                                     (filter (fn [{:keys [temp]}] (pos? (availability temp))))
                                     first)]
              {:pick-up-area (-> pick-up-area
                                 (update-in ["overflow"] dissoc (:id move-order))
                                 (assoc-in [(move-order :temp) (:id move-order)] move-order)
                                 (assoc-in ["overflow" id] order))
               :shelf "overflow"
               :action :moved
               :affected-order move-order}
              (let [discard-order (rand-nth (vals (pick-up-area "overflow")))]
                {:pick-up-area (-> pick-up-area
                                   (update-in ["overflow"] dissoc (:id discard-order))
                                   (assoc-in ["overflow" id] order))
                 :action :discarded
                 :affected-order discard-order})))))

(defn pickup-order
  "Remove the desired order from the pick-up-area. Returns a vector
  of [pick-up-area order].

  If the order is found we assoc :pickup-successful true. Otherwise we assoc
  false."
  [pick-up-area {:keys [temp id] :as order}]
  (let [mark-picked-up #(assoc % :picked-up true)]
    (cond
      (get-in pick-up-area [temp id]) 
      [(update-in pick-up-area [temp] dissoc id)
       (assoc (get-in pick-up-area [temp id]) :pickup-successful true)]

      (get-in pick-up-area ["overflow" id])
      [(update-in pick-up-area ["overflow"] dissoc id)
       (assoc (get-in pick-up-area ["overflow" id]) :pickup-successful true)]

      :else
      [pick-up-area (assoc order :pickup-successful false)])))

(def report-ch
  "A channel for debugging/testing purposes. We use this to signal the services
  that we want a readout of their internal states. Not used during normal
  operation."
  (async/chan))

(def report-mult
  "A mult on the report-ch so that we can get both services to report."
  (async/mult report-ch))

(defn kitchen-service
  "Start the kitchen service, which runs asynchronously in a go-loop. The
  service listens on four channels:
  
  - stop-ch: For stopping the service.
  - report-ch: For debugging/testing. Trigger a log of the current pick-up-area
  - orders-ch: Incoming orders from customers
  - pickup-ch: Incoming couriers for pickup

  Returns the stop-ch so that `mount` can stop the service when needed."
  []
  (let [stop-ch (async/chan)
        report-ch (async/chan)]
    (async/tap report-mult report-ch)
    (async/go-loop [pick-up-area (make-empty-pick-up-area)]
      (when-let [pick-up-area
                 (async/alt!
                   stop-ch nil
                   report-ch ([_ _] (log/info "KITCHEN report" (abbrev-pick-up-area pick-up-area)) pick-up-area);;TODO

                   orders-ch
                   ([order _]
                    (log/info "KITCHEN received order" order pick-up-area)
                    (async/>! dispatch-ch order)
                    (let [{:keys [pick-up-area shelf action affected-order]}
                          (put-on-pick-up-area pick-up-area (cook order))]
                      (log/info "KITCHEN cooked and placed order" order "on shelf" shelf
                                (if action
                                  (str "and" action affected-order)
                                  "")
                                pick-up-area)
                      pick-up-area))

                   pickup-ch
                   ([order _]
                    (log/info "KITCHEN received courier" order pick-up-area)
                    (let [[pick-up-area order] (pickup-order pick-up-area order)]
                      (log/info "KITCHEN courier took order" order pick-up-area)
                      (async/>! delivery-ch order)
                      pick-up-area)))]
        (recur pick-up-area)))
    stop-ch))

(mount/defstate kitchen 
  :start (kitchen-service)
  :stop (async/>!! kitchen true))

(defn sample-courier-wait-time
  "Generate a random time for a courier to wait before going to pick up its
  order. The distribution is governed by the values in config/env"
  []
  (let [min-time (config/env :courier-minimum-wait-time)
        max-time (config/env :courier-maximum-wait-time)]
    (+ min-time (Math/round (* (rand) (- max-time min-time))))))

(defn courier-service
  "Start the courier service, which runs asynchronously in a go-loop. The
  service listens on four channels:
  
  - stop-ch: For stopping the service.
  - dispatch-ch: Incoming dispatch requests
  - deliver-ch: Incoming couriers for delivery

  Returns the stop-ch so that `mount` can stop the service when needed."
  []
  (let [stop-ch (async/chan)]
    (async/go-loop []
      (when (async/alt!
              stop-ch nil
              
              dispatch-ch
              ([order _]
               ;; When we receive a dispatch request, we create a courier that
               ;; will wait a random amount of time before going to pick up
               ;; the order.
               (let [wait-time (sample-courier-wait-time)]
                 (log/info "COURIER dispatched" order)
                 (async/go
                   (async/<! (async/timeout wait-time))
                   (async/>! pickup-ch order)))
               true)

              delivery-ch
              ([order _]
               ;; The Challenge Prompt didn't specify what 'delivery'
               ;; means. All we do here is log the fact that the delivery
               ;; happened.
               (log/info "COURIER returned from pick-up-area" order)
               true))
        (recur)))
    stop-ch))

(mount/defstate courier
  :start (courier-service)
  :stop (async/>!! courier true))
