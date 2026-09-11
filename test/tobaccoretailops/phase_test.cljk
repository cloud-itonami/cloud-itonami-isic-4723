(ns tobaccoretailops.phase-test
  "Unit tests of `tobaccoretailops.phase` rollout logic."
  (:require [clojure.test :refer [deftest is testing]]
            [tobaccoretailops.phase :as phase]))

(deftest phase-0-read-only
  (testing "phase 0 allows no writes"
    (doseq [op [:log-sales-record :schedule-staffing-operation :coordinate-supply-order
                :flag-compliance-concern]]
      (let [{:keys [disposition]} (phase/gate 0 {:op op} :commit)]
        (is (= :hold disposition)
            (str "phase 0 must hold all ops including " op))))))

(deftest phase-1-sales-record-only
  (testing "phase 1 allows only sales-record logging, requires approval"
    (let [{:keys [disposition reason]} (phase/gate 1 {:op :log-sales-record} :commit)]
      (is (= :escalate disposition))
      (is (= :phase-approval reason)))
    (let [{:keys [disposition]} (phase/gate 1 {:op :schedule-staffing-operation} :commit)]
      (is (= :hold disposition)))))

(deftest phase-2-adds-coordination-ops
  (testing "phase 2 allows coordination ops, still requires approval"
    (doseq [op [:log-sales-record :schedule-staffing-operation :coordinate-supply-order]]
      (let [{:keys [disposition]} (phase/gate 2 {:op op} :commit)]
        (is (= :escalate disposition)
            (str "phase 2 op " op " requires approval"))))))

(deftest phase-3-auto-commits-clean-ops
  (testing "phase 3 auto-commits clean, high-conf non-safety ops"
    (let [{:keys [disposition]} (phase/gate 3 {:op :log-sales-record} :commit)]
      (is (= :commit disposition)))
    (let [{:keys [disposition]} (phase/gate 3 {:op :schedule-staffing-operation} :commit)]
      (is (= :commit disposition)))
    (let [{:keys [disposition]} (phase/gate 3 {:op :coordinate-supply-order} :commit)]
      (is (= :commit disposition)))))

(deftest compliance-concern-holds-when-not-enabled
  (testing ":flag-compliance-concern holds in phases 0-2 (not yet enabled)"
    (doseq [ph [0 1 2]]
      (let [{:keys [disposition]} (phase/gate ph {:op :flag-compliance-concern} :escalate)]
        (is (= :hold disposition)
            (str "phase " ph " has not enabled flag-compliance-concern yet"))))))

(deftest compliance-concern-escalates-when-enabled
  (testing ":flag-compliance-concern ALWAYS escalates when enabled, even if governor says commit"
    (let [{:keys [disposition]} (phase/gate 3 {:op :flag-compliance-concern} :commit)]
      (is (= :escalate disposition)
          "phase 3 must escalate compliance concerns regardless of governor disposition"))))

(deftest high-cost-supply-order-escalates-at-phase-3
  (testing "the governor already turned a high-cost supply order into :escalate upstream -- phase 3 must not force it back to :commit"
    (let [{:keys [disposition]} (phase/gate 3 {:op :coordinate-supply-order} :escalate)]
      (is (= :escalate disposition)))))

(deftest hard-hold-always-wins
  (testing "a governor HARD hold stays HOLD regardless of phase"
    (doseq [ph [0 1 2 3]]
      (let [{:keys [disposition]} (phase/gate ph {:op :log-sales-record} :hold)]
        (is (= :hold disposition)
            (str "phase " ph " must respect governor HARD hold"))))))

(deftest verdict->disposition-maps-correctly
  (testing "verdict->disposition correctly translates governor verdict to base disposition"
    (is (= :hold (phase/verdict->disposition {:hard? true :escalate? false})))
    (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true})))
    (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false})))))
