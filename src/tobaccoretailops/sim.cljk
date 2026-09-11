(ns tobaccoretailops.sim
  "Demo driver -- `clojure -M:run`. Walks a clean sales-record logging
  request through intake -> advise -> govern -> decide -> approval ->
  commit at phase 1 (assisted-logging, always approval), then re-runs the
  same op at phase 3 (supervised-auto, clean + high confidence ->
  auto-commit), then a staffing-operation-scheduling request and a
  low-cost supply-order coordination (both auto-commit clean at phase
  3), then a high-cost supply-order (ALWAYS escalates regardless of
  phase), then a compliance-concern flag (ALWAYS escalates, at any phase
  -- approve, then commit), then HARD-hold scenarios: an unregistered
  store, a store registered but not yet licensed, a proposal whose own
  `:effect` is not `:propose`, and a proposal that has drifted into the
  permanently-excluded age-verification-override scope."
  (:require [langgraph.graph :as g]
            [tobaccoretailops.advisor :as advisor]
            [tobaccoretailops.store :as store]
            [tobaccoretailops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "tobacco-retail-coordinator-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        coordinator-phase-1 {:actor-id "coord-1" :actor-role :tobacco-retail-coordinator :phase 1}
        coordinator-phase-3 {:actor-id "coord-1" :actor-role :tobacco-retail-coordinator :phase 3}
        actor (op/build db)]

    (println "== log-sales-record store-1 (phase 1, escalates -- human approves) ==")
    (let [r (exec-op actor "t1" {:op :log-sales-record :store-id "store-1"
                                  :patch {:units-sold 18 :item "cigars" :returns 0}} coordinator-phase-1)]
      (println r)
      (println "-- human tobacco retail coordinator approves --")
      (println (approve! actor "t1")))

    (println "\n== log-sales-record store-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t2" {:op :log-sales-record :store-id "store-1"
                                  :patch {:units-sold 25 :item "pipe tobacco" :returns 1}} coordinator-phase-3))

    (println "\n== schedule-staffing-operation store-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :schedule-staffing-operation :store-id "store-1"
                                  :patch {:shift "afternoon-counter" :date "2026-07-20" :window "13:00-17:00"}} coordinator-phase-3))

    (println "\n== coordinate-supply-order store-1, low cost (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t4" {:op :coordinate-supply-order :store-id "store-1"
                                  :patch {:item "humidor supplies" :quantity 50 :estimated-cost 120.0}} coordinator-phase-3))

    (println "\n== coordinate-supply-order store-1, HIGH cost (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t5" {:op :coordinate-supply-order :store-id "store-1"
                                 :patch {:item "premium cigar bulk order" :quantity 1 :estimated-cost 3200.0}} coordinator-phase-3)]
      (println r)
      (println "-- human tobacco retail coordinator reviews & approves --")
      (println (approve! actor "t5")))

    (println "\n== flag-compliance-concern store-1 (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t6" {:op :flag-compliance-concern :store-id "store-1"
                                 :patch {:concern "customer appeared underage, ID check inconclusive, referred to manager" :confidence 0.92}} coordinator-phase-3)]
      (println r)
      (println "-- human tobacco retail coordinator reviews & approves --")
      (println (approve! actor "t6")))

    (println "\n== log-sales-record store-99 (unregistered store -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :log-sales-record :store-id "store-99"
                                  :patch {:units-sold 0 :item "unknown"}} coordinator-phase-3))

    (println "\n== log-sales-record store-3 (registered but unlicensed -> HARD hold) ==")
    (println (exec-op actor "t8" {:op :log-sales-record :store-id "store-3"
                                  :patch {:units-sold 10 :item "vape pods"}} coordinator-phase-3))

    (println "\n== schedule-staffing-operation store-1, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer nil req) :effect :commit)))})]
      (println (exec-op actor-direct "t9" {:op :schedule-staffing-operation :store-id "store-1"
                                           :patch {:shift "evening-counter" :date "2026-07-22"}} coordinator-phase-3)))

    (println "\n== log-sales-record store-1, advisor drifts into age-verification-override scope -> HARD hold, permanent ==")
    (println (exec-op actor "t10" {:op :log-sales-record :store-id "store-1"
                                   :out-of-scope? true
                                   :patch {}} coordinator-phase-3))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== committed coordination log ==")
    (doseq [r (store/coordination-log db)] (println r))))
