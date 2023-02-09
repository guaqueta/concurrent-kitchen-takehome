(ns kitchen.kitchen-test
  (:require [kitchen.kitchen :as sut]
            [kitchen.customer :as customer]
            [kitchen.config :as config]
            [clojure.test :as t]
            [clojure.core.async :as async]))

(defn- gen-shelf
  "Helper function to generate shelves for testing"
  [temp n]
  (into {}
        (repeatedly n #(let [id (str (java.util.UUID/randomUUID))]
                         [id {:temp temp :id id}]))))

(t/deftest deliveries-returned
  (t/testing "one order succeeds"
    (let [order {:temp "hot" :id 1}]
      (t/is (= [(-> order
                    (assoc :cooked true)
                    (assoc :pickup-successful true))]
               (async/<!!
                (customer/place-orders-collect-deliveries
                 (sut/kitchen (async/chan))
                 [order]
                 0))))))
  (t/testing "orders up to shelf-capacity succeed"
    (let [orders (map #(assoc % :temp "hot")
                      (customer/gen-orders
                       (+ (get-in config/env [:shelf-capacity "hot"])
                          (get-in config/env [:shelf-capacity "overflow"]))))]
      (t/is (= (sort-by :id
                        (map (fn [order]
                               (-> order
                                   (assoc :cooked true)
                                   (assoc :pickup-successful true)))
                             orders))
               (sort-by :id
                        (async/<!!
                         (customer/place-orders-collect-deliveries
                          (sut/kitchen (async/chan))
                          orders
                          0)))))))
  (t/testing "if there are more orders than shelf-capacity, some orders get dropped"
    (let [orders (map #(assoc % :temp "hot")
                      (customer/gen-orders
                       (* 2 (+ (get-in config/env [:shelf-capacity "hot"])
                               (get-in config/env [:shelf-capacity "overflow"])))))
          deliveries (async/<!!
                      (customer/place-orders-collect-deliveries
                       (sut/kitchen (async/chan))
                       orders
                       0))]
      (t/is (< (count deliveries) (count orders)))
      (t/is (clojure.set/subset?
             (set deliveries)
             (set (map (fn [order]
                         (-> order
                             (assoc :cooked true)
                             (assoc :pickup-successful true)))
                       orders)))))))

(t/deftest pickup-order-test
  (t/testing "order on expected pick-up-area"
    (let [id 1
          order {:temp "hot" :id id}
          pick-up-area (update-in (#'sut/make-empty-pick-up-area) ["hot"] assoc id order)]
      (t/is (= [(#'sut/make-empty-pick-up-area) (assoc order :pickup-successful true)]
               (#'sut/pickup-order pick-up-area order))))
    (let [id 1
          order {:temp "cold" :id id}
          pick-up-area (update-in (#'sut/make-empty-pick-up-area) ["cold"] assoc id order)]
      (t/is (= [(#'sut/make-empty-pick-up-area) (assoc order :pickup-successful true)]
               (#'sut/pickup-order pick-up-area order))))
    (let [id 1
          order {:temp "cold" :id id}
          pick-up-area-without-order (-> (#'sut/make-empty-pick-up-area)
                                         (update-in ["cold"] assoc 2 {:temp "cold" :id 2})
                                         (update-in ["cold"] assoc 3 {:temp "cold" :id 3})
                                         (update-in ["cold"] assoc 4 {:temp "cold" :id 4})
                                         (update-in ["hot"] assoc 5 {:temp "hot" :id 5}))
          pick-up-area-with-order (update-in pick-up-area-without-order ["cold"] assoc id order)]
      (t/is (= [pick-up-area-without-order (assoc order :pickup-successful true)]
               (#'sut/pickup-order pick-up-area-with-order order)))))
  (t/testing "order on overflow pick-up-area"
    (let [id 1
          order {:temp "hot" :id id}
          pick-up-area (update-in (#'sut/make-empty-pick-up-area) ["overflow"] assoc id order)]
      (t/is (= [(#'sut/make-empty-pick-up-area) (assoc order :pickup-successful true)]
               (#'sut/pickup-order pick-up-area order))))
    (let [id 1
          order {:temp "cold" :id id}
          pick-up-area (update-in (#'sut/make-empty-pick-up-area) ["overflow"] assoc id order)]
      (t/is (= [(#'sut/make-empty-pick-up-area) (assoc order :pickup-successful true)]
               (#'sut/pickup-order pick-up-area order)))))
  (t/testing "order not in pick-up-area"
    (let [id 1
          order {:temp "hot" :id id}
          pick-up-area (#'sut/make-empty-pick-up-area)]
      (t/is (= [(#'sut/make-empty-pick-up-area) (assoc order :pickup-successful false)]
               (#'sut/pickup-order pick-up-area order))))))

(t/deftest put-on-pick-up-area-test
  (t/testing "place on empty pick-up-area"
    (let [order {:temp "hot" :id 1}
          pick-up-area {"hot" {1 order} "cold" {} "frozen" {} "overflow" {}}]
      (t/is (= {:pick-up-area pick-up-area
                :shelf "hot"}
               (#'sut/put-on-pick-up-area (#'sut/make-empty-pick-up-area) order)))))
  (t/testing "place on shelf with some contents"
    (let [order {:temp "hot" :id 1}
          pick-up-area-before-order {"hot" {2 {:temp "hot" :id 2}, 3 {:temp "hot" :id 3}}
                              "cold" {} "frozen" {} "overflow" {}}
          pick-up-area-after-order  {"hot" {1 order, 2 {:temp "hot" :id 2}, 3 {:temp "hot" :id 3}}
                              "cold" {} "frozen" {} "overflow" {}}]
      (t/is (= {:pick-up-area pick-up-area-after-order
                :shelf "hot"}
               (#'sut/put-on-pick-up-area pick-up-area-before-order order)))))
  (t/testing "place on overflow shelf"
    (let [order {:temp "hot" :id :placed}
          hot-shelf (gen-shelf "hot" (get-in config/env [:shelf-capacity "hot"]))
          pick-up-area-before-order {"hot" hot-shelf "cold" {} "frozen" {} "overflow" {}}
          pick-up-area-after-order  {"hot" hot-shelf "cold" {} "frozen" {} "overflow" {:placed order}}]
      (t/is (= {:pick-up-area pick-up-area-after-order
                :shelf "overflow"}
               (#'sut/put-on-pick-up-area pick-up-area-before-order order)))))
  (t/testing "move item off overflow shelf"
    (let [order {:temp "hot" :id :placed}
          hot-shelf (gen-shelf "hot" (get-in config/env [:shelf-capacity "hot"]))
          overflow-shelf (gen-shelf "cold" (get-in config/env [:shelf-capacity "overflow"]))
          pick-up-area {"hot" hot-shelf "cold" {} "frozen" {} "overflow" overflow-shelf}
          pick-up-area-after-order (#'sut/put-on-pick-up-area pick-up-area order)]
      (t/is (= 1 (count (get-in pick-up-area-after-order [:pick-up-area "cold"]))))
      (t/is (= order (get-in pick-up-area-after-order [:pick-up-area "overflow" :placed])))
      (t/is (= hot-shelf ((:pick-up-area pick-up-area-after-order) "hot")))))
  (t/testing "move item off overflow shelf"
    (let [order {:temp "hot" :id :placed}
          hot-shelf (gen-shelf "hot" (get-in config/env [:shelf-capacity "hot"]))
          cold-shelf (gen-shelf "cold" (get-in config/env [:shelf-capacity "cold"]))
          frozen-shelf (gen-shelf "frozen" (dec (get-in config/env [:shelf-capacity "frozen"])))
          overflow-shelf (merge
                          (gen-shelf "cold" (dec (get-in config/env [:shelf-capacity "overflow"])))
                          (gen-shelf "frozen" 1))
          pick-up-area {"hot" hot-shelf "cold" cold-shelf "frozen" frozen-shelf "overflow" overflow-shelf}
          pick-up-area-after-order (#'sut/put-on-pick-up-area pick-up-area order)]
      (t/is (= hot-shelf (get-in pick-up-area-after-order [:pick-up-area "hot"])))
      (t/is (= cold-shelf (get-in pick-up-area-after-order [:pick-up-area "cold"])))
      (t/is (= (inc (count frozen-shelf)) (count (get-in pick-up-area-after-order [:pick-up-area "frozen"]))))
      (t/is (= order (get-in pick-up-area-after-order [:pick-up-area "overflow" :placed])))
      (t/is (= :moved (:action pick-up-area-after-order)))))
  (t/testing "discard item to make room"
    (let [order {:temp "hot" :id :placed}
          hot-shelf (gen-shelf "hot" (get-in config/env [:shelf-capacity "hot"]))
          cold-shelf (gen-shelf "cold" (get-in config/env [:shelf-capacity "cold"]))
          overflow-shelf (gen-shelf "cold" (get-in config/env [:shelf-capacity "overflow"]))
          pick-up-area {"hot" hot-shelf "cold" cold-shelf "frozen" {} "overflow" overflow-shelf}
          pick-up-area-after-order (#'sut/put-on-pick-up-area pick-up-area order)]
      (t/is (= cold-shelf (get-in pick-up-area-after-order [:pick-up-area "cold"])))
      (t/is (= hot-shelf (get-in pick-up-area-after-order [:pick-up-area "hot"])))
      (t/is (= (pick-up-area "frozen") (get-in pick-up-area-after-order [:pick-up-area "frozen"])))
      (t/is (= order (get-in pick-up-area-after-order [:pick-up-area "overflow" :placed])))
      (t/is (= :discarded (:action pick-up-area-after-order))))))
