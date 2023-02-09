(ns kitchen.core
  (:require [kitchen.kitchen :as kitchen]
            [kitchen.customer :as customer]
            [clojure.core.async :as async]
            [clojure.tools.logging.readable :as log]))

(defn run-emulator []
  (async/<!!
   (customer/place-orders-collect-deliveries
    (kitchen/kitchen (async/chan))
    customer/orders)))

(defn -main [& args]
  (log/info (run-emulator)))
