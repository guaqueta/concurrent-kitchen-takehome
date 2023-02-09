(ns kitchen.customer
  (:require [clojure.core.async :as async]
            [clojure.tools.logging.readable :as log]
            [kitchen.kitchen :as kitchen]
            [clojure.data.json :as json]
            [kitchen.config :as config]
            [clojure.java.io :as io]))

(def orders
  (json/read-str
   (slurp (io/resource (config/env :orders-json)))
   :key-fn keyword)
  #_(let [base-orders (json/read-str
                     (slurp (io/resource (config/env :orders-json)))
                     :key-fn keyword)]
    (mapcat (comp shuffle #(map (fn [order] (assoc order :id (str (java.util.UUID/randomUUID)))) %))
            (repeat 10 base-orders))))

(defn place-orders
  "Emulates customers placing orders on the system. Orders are placed
  asynchronously (i.e. without waiting for a reply), at a fixed, configurable
  rate governed by :customer-wait-between-orders. Executes in a separate
  thread so as not to block the caller while sleeping between orders.

  Note we are deliberately not respecting back-pressure here. We are emulating
  orders coming in at a rate which the rest of the system cannot control. TODO
  is this really what I want"
  [n]
  (let [wait-time 10; (config/env :customer-wait-between-orders)
        ]
    (doseq [order (take n orders)] ;; XXX
      (async/<!! (async/timeout wait-time))
      ;; TODO error handling, rationale for not respecting backpressure
      (log/info "Customer placing order")
      (async/put! kitchen/orders-ch order)
      #_(async/>!! kitchen/orders-ch order) ;;xxx
      )))
