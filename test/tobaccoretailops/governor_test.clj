(ns tobaccoretailops.governor-test
  "Pure unit tests of `tobaccoretailops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-test`'s
  full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [tobaccoretailops.advisor :as adv]
            [tobaccoretailops.governor :as gov]
            [tobaccoretailops.store :as store]))

(def store-1 {:store-id "store-1" :name "Union Square Tobacconist" :registered? true :licensed? true})
(def store-3 {:store-id "store-3" :name "Harbor Vape Specialty" :registered? true :licensed? false})

(defn- clean-proposal [op store-id]
  {:op op :store-id store-id :summary "s" :rationale "routine retail coordination"
   :cites [store-id] :effect :propose :value {} :confidence 0.85})

(deftest store-unregistered-is-hard
  (testing "no store record at all -> HARD hold"
    (let [s (store/mem-store {"store-1" store-1})
          verdict (gov/check {} nil (clean-proposal :log-sales-record "unknown-store") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:store-unverified} (map :rule (:violations verdict)))))))

(deftest store-unlicensed-is-hard
  (testing "store registered but not yet licensed -> HARD hold"
    (let [s (store/mem-store {"store-3" store-3})
          verdict (gov/check {} nil (clean-proposal :log-sales-record "store-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:store-unverified} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"store-1" store-1})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-staffing-operation "store-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"store-1" store-1})
          verdict (gov/check {} nil (clean-proposal :finalize-age-verification-override "store-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest age-verification-override-content-is-hard-and-permanent
  (testing "a proposal whose rationale touches finalizing an age-verification override is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"store-1" store-1})
          poisoned (assoc (clean-proposal :log-sales-record "store-1")
                          :rationale "finalized the age verification override for this sale"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest age-verification-terminal-actuation-content-is-hard
  (testing "a proposal touching direct age-verification/ID-scanning terminal actuation is HARD-blocked, same as an override"
    (let [s (store/mem-store {"store-1" store-1})
          poisoned (assoc (clean-proposal :schedule-staffing-operation "store-1")
                          :summary "actuate the id scanner remotely to control the age verification terminal")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest tobacco-license-authority-content-is-hard
  (testing "a proposal touching tobacco-license-suspension/inspection-clearance/regulatory-enforcement is HARD-blocked"
    (let [s (store/mem-store {"store-1" store-1})
          poisoned (assoc (clean-proposal :coordinate-supply-order "store-1")
                          :summary "pursue tobacco license suspension and inspection clearance review")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-compliance-concern-is-not-scope-excluded
  (testing "flagging an observed suspected-underage-sale/ID-check concern as a COMPLIANCE CONCERN (not a finalized override) never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"store-1" store-1})
          concern (assoc (clean-proposal :flag-compliance-concern "store-1")
                         :value {:concern "customer appeared underage, ID check inconclusive, referred to manager"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (suspected age mismatch, inconclusive ID check) is exactly what this op exists to surface"))))

(deftest compliance-concern-always-escalates-clean
  (testing ":flag-compliance-concern is always high-stakes/escalate, even when otherwise clean and high confidence"
    (let [s (store/mem-store {"store-1" store-1})
          verdict (gov/check {} nil (assoc (clean-proposal :flag-compliance-concern "store-1") :confidence 0.99) s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest high-cost-supply-order-always-escalates
  (testing "a :coordinate-supply-order above the cost threshold is high-stakes/escalate, even when otherwise clean and high confidence"
    (let [s (store/mem-store {"store-1" store-1})
          expensive (assoc (clean-proposal :coordinate-supply-order "store-1")
                           :value {:item "premium cigar bulk order" :estimated-cost 5000.0}
                           :confidence 0.97)
          verdict (gov/check {} nil expensive s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest low-cost-supply-order-does-not-force-escalate
  (testing "a :coordinate-supply-order at or below the cost threshold does not trip the high-cost escalate gate"
    (let [s (store/mem-store {"store-1" store-1})
          cheap (assoc (clean-proposal :coordinate-supply-order "store-1")
                       :value {:item "humidor supplies" :estimated-cost 120.0}
                       :confidence 0.9)
          verdict (gov/check {} nil cheap s)]
      (is (false? (:hard? verdict)))
      (is (false? (:high-stakes? verdict)))
      (is (false? (:escalate? verdict))))))

;; ----------------------------------------------------------------------
;; CRITICAL guardrail regression test: multiple sibling actors in this
;; fleet have independently discovered and fixed the SAME bug class --
;; a governor scope-exclusion term phrased as a bare noun (e.g. bare
;; "age" or bare "verification") accidentally matches inside the mock
;; advisor's OWN default rationale/disclaimer text for a legitimate,
;; allowed proposal, causing the actor to self-block on its own happy
;; path. This test asserts every default mock-advisor proposal, for
;; every op in the closed allowlist, at a REGISTERED+LICENSED store,
;; clears the governor with `:scope-excluded` absent from its
;; violations (regardless of `:hard?`/`:escalate?` -- some ops legally
;; escalate, e.g. :flag-compliance-concern, but MUST NOT self-trip the
;; scope-exclusion check to get there).
;; ----------------------------------------------------------------------
(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "the default mock advisor's own proposals, for every allowed op, never trigger :scope-excluded"
    (let [s (store/mem-store {"store-1" store-1})]
      (doseq [op [:log-sales-record :schedule-staffing-operation :coordinate-supply-order
                  :flag-compliance-concern]]
        (let [proposal (adv/infer nil {:op op :store-id "store-1"
                                        :patch {:units-sold 10 :item "test"
                                                :estimated-cost 120.0
                                                :concern "routine compliance check"}})
              verdict (gov/check {:store-id "store-1"} nil proposal s)]
          (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
              (str "default mock advisor's own proposal for " op
                   " must never self-trip :scope-excluded -- rationale/summary: "
                   (pr-str (select-keys proposal [:rationale :summary])))))))))

;; ----------------------------------------------------------------------
;; Domain-specific invariant (see CLAUDE.md / ADR-2607121000): the closed
;; op allowlist must NEVER include any op that directly finalizes an
;; age-verification override -- always a hard permanent block or an
;; always-escalate op, never auto-commit-eligible.
;; ----------------------------------------------------------------------
(deftest no-allowed-op-finalizes-age-verification
  (testing "no member of the closed op allowlist is itself an age-verification-finalization action"
    (doseq [op gov/allowed-ops]
      (is (not (re-find #"(?i)finaliz|override|verify-age|age-verification-decision" (name op)))
          (str "op " op " must not read as an age-verification-finalization action")))))

(deftest flag-compliance-concern-is-always-escalate-never-auto
  (testing ":flag-compliance-concern is a member of always-escalate-ops -- a 'flag a concern' op must always escalate and never be auto-commit-eligible"
    (is (contains? gov/always-escalate-ops :flag-compliance-concern))))
