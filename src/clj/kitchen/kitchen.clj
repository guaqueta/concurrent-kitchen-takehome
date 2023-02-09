(ns kitchen.kitchen
  "Emulate a kitchen that receives, processes, and delivers orders.
 
  This namespace provides `kitchen`, a fn that returns an asynchronous machine
  that emulates a kitchen. The kitchen accepts orders on the order-ch, and
  returns finished orders on the delivery-ch. The kitchen has internal state
  to emulate a shelf where cooked orders are stored, and a courier dispatch
  service that schedules couriers to pick-up food from the shelf and deliver
  it to the customer."
  (:require
   [clojure.core.async :as async]
   [clojure.tools.logging.readable :as log]
   [kitchen.config :as config]))

(defn- cook
  "Cook the order. Essentially a placeholder for presumably more complex logic
  to come. Returns an order."
  [order]
  (assoc order :cooked true))

(defn- make-empty-pick-up-area
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

(defn- put-on-pick-up-area
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

(defn- pickup-order
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

(defn- sample-courier-wait-time
  "Generate a random time for a courier to wait before going to pick up its
  order. The distribution is governed by the values in config/env"
  []
  (let [min-time (config/env :courier-minimum-wait-time)
        max-time (config/env :courier-maximum-wait-time)]
    (+ min-time (Math/round (* (rand) (- max-time min-time))))))

(defn kitchen
  "Returns a map of async channels that allow the caller to interact with a
  machine that emulates a kitchen.

  Takes a `delivery-ch` channel as argument. After orders are cooked and
  picked up by couriers they will be put on this channel. By providing it as
  an argument the caller is free to choose how the channel is buffered. The
  `kitchen` doesn't respect backpressure on this channel and will `put!`
  completed orders on it.

  Orders are maps with, at minimum, :id and :temp keys. We assume :id is
  unique, and that :temp is one of hot, cold, or frozen. Other keys are
  allowed. As the order progresses through the kitchen it will gain :cooked
  and :pickup-successful keys as well, with boolean values.

  Orders are cooked and put in the pick-up-area. This area contains shelves at
  different temperatures to hold different orders. See `put-on-pick-up-area`
  for details. A courier is scheduled to come pickup completed orders. Once
  the courier arrives, it delivers the order to `delivery-ch`.

  The return map includes:

  - stop: Any value put on this channel will cause the go-loop to halt on its
  next iteration. Note this won't halt a loop that is parked waiting.

  - report: For testing and debugging. A value on this channel will cause the
  machine to log its internal state.

  - orders: Orders placed here will be processed and delivered.

  - end-orders: A value on this channel indicates that no more orders are
  incoming. Once the kitchen completes all its deliveries it will close the
  delivery-ch to indicate that it is finished.

  - delivery: For convenience, we include the delivery-ch argument in the
  return value."
  [delivery-ch]
  (let [stop-ch (async/chan)
        report-ch (async/chan)
        orders-ch (async/chan)
        end-orders-ch (async/chan)
        pickup-ch (async/chan)]
    (async/go-loop [pick-up-area (make-empty-pick-up-area)
                    couriers {}
                    orders-ended false]
      (when-let [[pick-up-area couriers orders-ended]
                 (async/alt!
                   ;; Provide a way to halt the machine
                   stop-ch (log/info "STOPPING" pick-up-area couriers)

                   ;; Provide a way to peek at the internal state. For testing and debugging.
                   report-ch (do
                               (log/info "REPORT" pick-up-area couriers orders-ended)
                               [pick-up-area couriers orders-ended])

                   ;; Handle incoming orders
                   orders-ch
                   ([order]
                    (log/info "order received" order pick-up-area)
                    (let [wait-time (sample-courier-wait-time)
                          courier (async/go
                                    (async/<! (async/timeout wait-time))
                                    (async/>! pickup-ch order))
                          {:keys [pick-up-area shelf action affected-order]} (put-on-pick-up-area pick-up-area (cook order))]
                      (log/info "courier scheduled" wait-time order pick-up-area)
                      (log/info "order cooked and placed on shelf" order shelf (if action (str "and" action affected-order) "") pick-up-area)
                      [pick-up-area (assoc couriers (order :id) courier) orders-ended]))

                   ;; Handle incoming couriers. If this courier is the last
                   ;; one left in flight and no more orders are expected, we
                   ;; can close the delivery-ch
                   pickup-ch
                   ([order]
                    (log/info "courier arrived" order pick-up-area)
                    (let [[pick-up-area order] (pickup-order pick-up-area order)
                          couriers (dissoc couriers (order :id))]
                      (if (:pickup-successful order)
                        (do
                          (async/put! delivery-ch order)
                          (log/info "order out for delivery" order pick-up-area))
                        (log/info "order not found" order pick-up-area))
                      (if (and orders-ended (empty? couriers))
                        (async/close! delivery-ch)
                        [pick-up-area couriers orders-ended])))

                   ;; A signal on this channel indicates that no more orders
                   ;; are coming. This gives us a chance to rendezvous with
                   ;; the caller. Once we are done waiting for the last
                   ;; couriers we can close delivery-ch.
                   end-orders-ch (do
                                   (log/info "no more orders coming," (count couriers) "couriers in flight")
                                   (if (empty? couriers)
                                     (async/close! delivery-ch)
                                     [pick-up-area couriers true])))]
        (recur pick-up-area couriers orders-ended)))
    {:stop stop-ch
     :report report-ch
     :orders orders-ch
     :end-orders end-orders-ch
     :delivery delivery-ch}))
