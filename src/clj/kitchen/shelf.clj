(ns kitchen.shelf
  (:require [clojure.core.async :as async]
            [clojure.tools.logging.readable :as log]
            [kitchen.config :as config]))

;; TODO provide rationale for why I'm using channels here instead of letting
;; other namespaces interact directly with, say, an agent API

(def orders-ch
  "Incoming queue of cooked orders to manage on the shelf. Although not strictly
  necessary to have a channel for this I felt that the extra level of
  indirection is in the spirit of emulating a concurrent kitchen" ;; TODO clean up doc language
  (async/chan))

(def pickup-ch
  "Incoming queue of couriers picking up specific dishes." ;; TODO clean up doc language
  (async/chan))

(def shelf-db ;; TODO revisit turning into an atom/agent/ref
  "TODO: docs"
  (atom
   {"hot" {}
    "cold" {}
    "frozen" {}
    "overflow" {}}))

;; TODO pickup-order and place-order return a shelf, but we are also
;; interested in whether they succeeded, and in getting the order back

(defn abridged-shelf [shelf]
  (sort-by first
           (map (fn [[k v]] 
                  [k 
                   (count v)
                   (sort
                    (map (fn [[k v]]
                           (str (:name v) ":" (subs k 0 4)))
                         v))])
                shelf)))

(defn pickup-order [shelf {:keys [temp id] :as order}]
  #_(log/info "shelf picking up order" (:name order) (abridged-shelf @shelf-db))
  (let [shelf (if (get-in shelf [temp id])
                (do
                  #_(log/info "found order on" temp "shelf")
                  (update-in shelf [temp] dissoc id))
               (if (get-in shelf ["overflow" id])
                 (do
                   #_(log/info "found order on overflow shelf")
                   (update-in shelf ["overflow"] dissoc id))
                 shelf))]
    #_(log/info "order picked-up" shelf)
    shelf))

(defn shelf-availability [shelf]
  (into {}
        (map (fn [[k v]]
               [k
                (- (get-in config/env [:shelf-capacity k])
                   (count v))])
             shelf)))

(defn place-order [shelf {:keys [temp id] :as order}]
  #_(log/info "shelf placing order" (:name order) (abridged-shelf @shelf-db))
  (let [availability (shelf-availability shelf)]
    ;; TODO replace attempts at placing items on shelf with an `or` (instead of ugly nested `if`)
    (let [shelf (if (pos? (availability temp))
                  (do
                    #_(log/info "shelf found space for order" (order :name) "on shelf" temp)
                    (assoc-in shelf [temp id] order))
                  (if (pos? (availability "overflow"))
                    (do 
                      #_(log/info "shelf found space for order" (order :name) "on overflow shelf")
                      (assoc-in shelf ["overflow" id] order))
                    ;; Try to find an order that can be moved off the overflow
                    (if-let [movable-order (->> (vals (shelf "overflow"))
                                                (filter (fn [{:keys [temp]}] (pos? (availability temp))))
                                                first)]
                      (do
                        #_(log/info "shelf moving order out of overflow and to" (movable-order :temp) "to make room for" (order :name))
                        (-> shelf
                           (update-in ["overflow"] dissoc (:id movable-order))
                           (assoc-in [(movable-order :temp) (:id movable-order)] movable-order)
                           (assoc-in ["overflow" id] order)))
                      (do
                        #_(log/info "shelf discarding random order was waste to make room for" (order :name))
                        (-> shelf
                           (update-in ["overflow"] dissoc (first (rand-nth (into [] (shelf "overflow")))))
                           (assoc-in ["overflow" id] order))))
                    ))]
      #_(log/info "order placed" shelf)
      shelf)))

(defn shelf-runner [] ;; TODO should I rename other 'process-X' fns in other nss as 'x-runner'?
  ;; TODO rationale for using alts! to run a complicated machine instead of spinning up two separate machines
  (async/go-loop []
    (when-let [shelf (async/alt!
                       orders-ch ([order _] (swap! shelf-db place-order order))
                       pickup-ch ([order _] (swap! shelf-db pickup-order order)))]
      (recur))
    #_(let [[order ch] (async/alts! [orders-ch pickup-ch])]
      (println "shelf processing order" (order :id) (order :name))
      (send shelf-db
            (case ch
              ;; TODO add default case here
              pickup-ch pickup-order
              orders-ch place-order)
            order))))
