(ns kitchen.customer
  "Emulate customers placing orders on the kitchen.

  Order data is expected to be in a resource file. The filename is
  configurable as (config/env :orders-json).

  Orders are placed at a configurable rate. The relevant config variable
  is :customer-wait-between-orders, which is in milliseconds."
  (:require
   [clojure.core.async :as async]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [kitchen.config :as config]))

(def orders
  "Reads orders from the resource specified in the config."
  (json/read-str
   (slurp (io/resource (config/env :orders-json)))
   :key-fn keyword))

(defn gen-orders
  "This is just a helper function for interactive testing. Using `orders` as a
  reference, it generates `n` orders with random ids"
  [n]
  (take n (repeatedly (fn [] (assoc (rand-nth orders) :id (str (java.util.UUID/randomUUID)))))))

(defn place-orders
  "Emulates customers placing orders on the kitchen. Orders are placed
  asynchronously (i.e. without waiting for a reply), at a fixed rate governed
  by config/env :customer-wait-between-orders. Returns immediately. Doesn't
  wait for deliveries to return.

  With two arguments, the wait-time between orders defaults
  to (config/env :customer-wait-between-orders).

  Note we deliberately don't respect back-pressure (i.e. we use async/put! to
  place orders). This is because we are emulating orders coming in at a rate
  which the rest of the system cannot control. In other words, the kitchen can
  drop orders, but it can't ask customers to slow down."
  ([kitchen orders] (place-orders kitchen orders (config/env :customer-wait-between-orders)))
  ([kitchen orders wait-time] 
   (async/go
     (doseq [order orders]
       (async/<!! (async/timeout wait-time))
       (async/put! (kitchen :orders) order)))))

(defn place-orders-collect-deliveries
  "Emulates customers placing orders on the system. Orders are placed
  asynchronously (i.e. without waiting for a reply), at a fixed rate governed
  by config/env :customer-wait-between-orders. Returns immediately.

  With two arguments, the wait-time between orders defaults
  to (config/env :customer-wait-between-orders).

  After placing all orders, indicates to the kitchen that no more orders are
  coming, and then collects all deliveries into a coll. Returns a chan where
  the coll of deliveries can be returned. Note that this means the kitchen
  passed in will be unable to accept new orders."
  ([kitchen orders] (place-orders-collect-deliveries kitchen orders (config/env :customer-wait-between-orders)))
  ([kitchen orders wait-time]
   (async/go
     (doseq [order orders]
       (async/<!! (async/timeout wait-time))
       (async/put! (kitchen :orders) order))
     (async/>! (kitchen :end-orders) true))
   (async/go-loop [accepted []]
     (if-let [delivery (async/<! (kitchen :delivery))]
       (recur (conj accepted delivery))
       accepted))))
