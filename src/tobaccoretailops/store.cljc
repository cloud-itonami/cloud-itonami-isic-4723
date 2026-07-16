(ns tobaccoretailops.store
  "SSoT for the ISIC-4723 specialized tobacco retail COORDINATION actor,
  behind a `Store` protocol so the backend is a swap, not a rewrite -- the
  same seam every `cloud-itonami-isic-*` actor in this fleet uses.

  This actor coordinates the back-office operations of specialized tobacco
  retail stores -- tobacconists, cigar lounges, pipe & tobacco shops, and
  vape/e-cigarette specialty retailers (ISIC Rev.4 4723, 'Retail sale of
  tobacco products in specialized stores'; distinct from specialized food
  retail (4721) and stand-alone beverage retail (4722)): inventory/sale/
  return logging, floor-staff scheduling, inventory procurement (supply-
  order) coordination, and compliance-concern flagging (age-verification
  failures, regulatory concerns). It never finalizes an age-verification
  override/decision, never directly operates or controls an age-
  verification/ID-scanning terminal, and never suspends or revokes a
  tobacco retail license -- see `tobaccoretailops.governor`'s
  `scope-exclusion-violations`, a HARD, permanent, un-overridable block.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/demo
  (no deps). A `stores` directory keyed by `:store-id` STRING (never a
  keyword -- consistent keying from the start, avoiding the silent-miss
  bug that plagued an earlier shepherd attempt).

  A registered/licensed store record (business registration AND an
  independently verified tobacco retail license) must exist before ANY
  proposal for that store may ever commit or escalate --
  `tobaccoretailops.governor`'s `store-unverified-violations` re-derives
  this from the store's own `:registered?`/`:licensed?` fields, never from
  proposal self-report, the SAME 'ground truth, not self-report'
  discipline every sibling actor's own governor uses.

  The ledger stays append-only: which store a proposal targeted, which
  operation, on what basis, committed/held/escalated and approved by whom
  is always a query over an immutable log.")

(defprotocol Store
  (store-record [s store-id] "Registered store record, or nil.
    Store map: {:store-id .. :name .. :kind .. :registered? bool :licensed? bool}.
    :registered? -- business registration is on file.
    :licensed?   -- tobacco retail license independently verified (never
                    trust the proposal's own claim).")
  (all-stores [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-stores [s stores] "replace/seed the store directory (map store-id->store)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained store directory covering both the happy path
  and the governor's own hard checks, so the actor + tests run offline."
  []
  {:stores
   {"store-1" {:store-id "store-1" :name "Union Square Tobacconist" :kind :tobacconist
               :registered? true :licensed? true}
    "store-2" {:store-id "store-2" :name "Riverside Cigar Lounge" :kind :cigar-lounge
               :registered? true :licensed? true}
    "store-3" {:store-id "store-3" :name "Harbor Vape Specialty (tobacco retail license pending)" :kind :vape-specialty
               :registered? true :licensed? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (store-record [_ store-id] (get-in @a [:stores store-id]))
  (all-stores [_] (sort-by :store-id (vals (:stores @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-stores [s stores] (when (seq stores) (swap! a assoc :stores stores)) s))

(defn seed-db
  "A MemStore seeded with the demo store directory. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with an explicit `stores` map (store-id string ->
  store map) -- the primary test/dev entry point. `stores` may be empty
  (an unregistered-everywhere store)."
  [stores]
  (->MemStore (atom {:stores (or stores {}) :ledger [] :coordination-log []})))
