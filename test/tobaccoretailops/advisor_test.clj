(ns tobaccoretailops.advisor-test
  "Unit tests of `tobaccoretailops.advisor` proposal generation."
  (:require [clojure.test :refer [deftest is testing]]
            [tobaccoretailops.advisor :as adv]
            [tobaccoretailops.store :as store]))

(def db (store/seed-db))

(deftest propose-sales-record-shape
  (testing "sales-record proposal has correct shape and fields"
    (let [p (adv/infer db {:op :log-sales-record
                           :store-id "store-1"
                           :patch {:units-sold 18 :item "cigars"}})]
      (is (= :log-sales-record (:op p)))
      (is (= "store-1" (:store-id p)))
      (is (= :propose (:effect p)))
      (is (<= 0 (:confidence p) 1))
      (is (map? (:value p)))
      (is (contains? (:value p) :store-id)))))

(deftest propose-staffing-operation-shape
  (testing "staffing-operation proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-staffing-operation
                           :store-id "store-2"
                           :patch {:shift "afternoon-counter" :date "2026-07-20"}})]
      (is (= :schedule-staffing-operation (:op p)))
      (is (= "store-2" (:store-id p)))
      (is (= :propose (:effect p))))))

(deftest propose-supply-order-shape
  (testing "supply-order proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-supply-order
                           :store-id "store-1"
                           :patch {:item "humidor supplies" :quantity 50 :estimated-cost 120.0}})]
      (is (= :coordinate-supply-order (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest propose-compliance-concern-shape
  (testing "compliance-concern proposal always escalates"
    (let [p (adv/infer db {:op :flag-compliance-concern
                           :store-id "store-1"
                           :patch {:concern "suspected underage sale"}})]
      (is (= :flag-compliance-concern (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest all-proposals-effect-is-always-propose
  (testing "every proposal type has :effect :propose, never direct actuation"
    (doseq [op [:log-sales-record :schedule-staffing-operation :coordinate-supply-order
                :flag-compliance-concern]]
      (let [p (adv/infer db {:op op :store-id "store-1" :patch {}})]
        (is (= :propose (:effect p))
            (str "op " op " must have :effect :propose"))))))

(deftest rationale-string-is-present
  (testing "every proposal has a rationale explaining the advisor's thinking"
    (doseq [op [:log-sales-record :schedule-staffing-operation :coordinate-supply-order
                :flag-compliance-concern]]
      (let [p (adv/infer db {:op op :store-id "store-1" :patch {}})]
        (is (string? (:rationale p))
            (str "op " op " must have a :rationale string"))))))
