(ns kitchen.kitchen
  (:require [clojure.core.async :as async]
            [clojure.tools.logging.readable :as log]
            [kitchen.courier :as courier]
            [kitchen.shelf :as shelf]))

(def orders-ch
  "Incoming queue of orders for the kitchen to process" ;; TODO Add rationale for buffering options
  (async/chan))

(defn cook [order]
  #_(log/info "kitchen cooking order" (order :name) (shelf/abridged-shelf @shelf/shelf-db)) ;; TODO log
  (assoc order :cooked true))

(defn process-orders [] ;; TODO closes when the order channel closes. But I
                        ;; guess it should close if courier-ch or
                        ;; shelf/orders-ch close as well
  (async/go-loop []
    (when-let [order (async/<! orders-ch)]
      #_(log/info "kitchen processing order" (order :name) (shelf/abridged-shelf @shelf/shelf-db))
      ;; TODO Rationale for respecting back-pressure here
      (async/>! courier/courier-ch order)
      ;; TODO Rationale for respecting back-pressure here
      (async/>! shelf/orders-ch (cook order))
      (recur))))

#_(process-orders)
