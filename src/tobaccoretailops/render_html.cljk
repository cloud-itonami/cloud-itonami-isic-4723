(ns tobaccoretailops.render-html
  "Build-time HTML renderer for the ISIC-4723 specialized tobacco retail
  operations-coordination actor.

  This is NOT a mock console. It boots the REAL stack --
  `tobaccoretailops.store/seed-db` (the seeded store directory),
  `tobaccoretailops.operation/build` (the real langgraph StateGraph:
  intake -> advise -> govern -> decide -> commit | hold | approval), and
  the real `tobaccoretailops.governor` -- drives a deterministic
  scenario through it, and renders whatever the actor actually wrote to
  the SSoT. Every store id it drives (`store-1`, `store-2`, `store-3`)
  comes from `store/demo-data`; nothing is invented.

  The HARD holds shown are produced by the governor's own rules on
  deliberately non-compliant input, never by a hardcoded string:

    :store-unverified   -- `store-3` is seeded `:licensed? false`
                           (registered, tobacco retail license pending).
    :scope-excluded     -- the advisor's own `:out-of-scope?` hook makes
                           it draft age-verification-override text, which
                           `governor/scope-excluded-terms` re-scans.
    :effect-not-propose -- a compromised advisor claims `:effect :commit`.
    :op-not-allowed     -- a drifted advisor proposes an op that was
                           never on `governor/allowed-ops`.

  Ledger discipline: `tobaccoretailops.operation` appends only
  `:committed` (from the `:commit` node) and hold facts -- `:governor-hold`
  / `:approval-rejected` (from the `:hold` node) -- to the store ledger.
  `:approval-requested` and `:approval-granted` live only on the
  in-memory `:audit` channel, so the store-status column below never
  branches on them; they appear only in the request timeline, which is
  read from the graph's returned `:audit` state."
  (:require [kotoba.lang.text :as str]
            [langgraph.graph :as g]
            [tobaccoretailops.advisor :as advisor]
            [tobaccoretailops.governor :as governor]
            [tobaccoretailops.operation :as op]
            [tobaccoretailops.phase :as phase]
            [tobaccoretailops.store :as store]))

;; ----------------------------- driving the real actor -----------------------------

(def ^:private coordinator-phase-1
  {:actor-id "coord-1" :actor-role :tobacco-retail-coordinator :phase 1})

(def ^:private coordinator-phase-3
  {:actor-id "coord-1" :actor-role :tobacco-retail-coordinator :phase 3})

(defn- exec! [actor tid request ctx]
  (g/run* actor {:request request :context ctx} {:thread-id tid}))

(defn- resume! [actor tid status]
  (g/run* actor {:approval {:status status :by "tobacco-retail-coordinator-1"}}
          {:thread-id tid :resume? true}))

(defn- direct-actuation-advisor
  "A compromised TobaccoRetailAdvisor that claims a direct actuation
  instead of a proposal. Exercises `governor/check`'s
  `:effect-not-propose` HARD rule end-to-end (same shape the repo's own
  `tobaccoretailops.sim` uses)."
  []
  (reify advisor/Advisor
    (-advise [_ _ req] (assoc (advisor/infer nil req) :effect :commit))))

(defn- off-allowlist-advisor
  "A drifted TobaccoRetailAdvisor that emits a correctly-shaped,
  `:propose`-effect proposal for an op that was never a member of
  `governor/allowed-ops`. Exercises the `:op-not-allowed` HARD rule --
  an advisor proposing something it was never authorized to propose."
  []
  (reify advisor/Advisor
    (-advise [_ _ {:keys [op store-id patch]}]
      {:op         op
       :store-id   store-id
       :summary    (str store-id " に対する未認可オペレーションの起案")
       :rationale  "アクター憲章の closed allowlist に存在しない操作。"
       :cites      [store-id]
       :effect     :propose
       :value      (merge {:store-id store-id} patch)
       :confidence 0.91})))

(defn run-demo!
  "Boots the real store + real actor graph and drives a deterministic
  scenario. Returns {:db .. :timeline [..]}; the timeline is read back
  out of each graph run's own `:audit` channel, not narrated."
  []
  (let [db       (store/seed-db)
        actor    (op/build db)
        direct   (op/build db {:advisor (direct-actuation-advisor)})
        drifted  (op/build db {:advisor (off-allowlist-advisor)})
        timeline (atom [])
        record!  (fn [tid ctx request result]
                   (swap! timeline conj
                          {:thread      tid
                           :phase       (:phase ctx)
                           :op          (:op request)
                           :store-id    (:store-id request)
                           :disposition (get-in result [:state :disposition])
                           :audit       (mapv :t (get-in result [:state :audit]))})
                   result)
        run!     (fn [a tid ctx request]
                   (let [r (exec! a tid request ctx)]
                     (record! tid ctx request r)))
        run-then! (fn [a tid ctx request status]
                    (exec! a tid request ctx)
                    (record! tid ctx request (resume! a tid status)))]

    ;; --- happy path: sales-record logging at phase 1 (always human sign-off) ---
    (run-then! actor "t1" coordinator-phase-1
               {:op :log-sales-record :store-id "store-1"
                :patch {:units-sold 18 :item "cigars" :returns 0}}
               :approved)

    ;; --- phase 3 supervised-auto: governor-clean, high confidence -> auto-commit ---
    (run! actor "t2" coordinator-phase-3
          {:op :log-sales-record :store-id "store-1"
           :patch {:units-sold 25 :item "pipe tobacco" :returns 1}})

    (run! actor "t3" coordinator-phase-3
          {:op :schedule-staffing-operation :store-id "store-1"
           :patch {:shift "afternoon-counter" :date "2026-08-12" :window "13:00-17:00"}})

    (run! actor "t4" coordinator-phase-3
          {:op :coordinate-supply-order :store-id "store-1"
           :patch {:item "humidor supplies" :quantity 50 :estimated-cost 120.0}})

    ;; --- SOFT escalate: estimated-cost above governor/supply-cost-threshold ---
    (run-then! actor "t5" coordinator-phase-3
               {:op :coordinate-supply-order :store-id "store-1"
                :patch {:item "premium cigar bulk order" :quantity 12 :estimated-cost 3200.0}}
               :approved)

    (run! actor "t6" coordinator-phase-3
          {:op :schedule-staffing-operation :store-id "store-2"
           :patch {:shift "lounge-evening" :date "2026-08-13" :window "17:00-23:00"}})

    ;; --- SOFT escalate: :flag-compliance-concern is never auto-eligible ---
    (run-then! actor "t7" coordinator-phase-3
               {:op :flag-compliance-concern :store-id "store-2"
                :patch {:concern "humidor room ventilation log incomplete for 3 shifts"
                        :confidence 0.92}}
               :approved)

    ;; --- the coordinator declines: :approval-rejected is a real ledger fact ---
    (run-then! actor "t8" coordinator-phase-3
               {:op :flag-compliance-concern :store-id "store-1"
                :patch {:concern "shelf-label audit flagged one SKU, evidence inconclusive"
                        :confidence 0.55}}
               :rejected)

    ;; --- HARD hold: store-3 is seeded registered but NOT licensed ---
    (run! actor "t9" coordinator-phase-3
          {:op :log-sales-record :store-id "store-3"
           :patch {:units-sold 10 :item "vape pods" :returns 0}})

    ;; --- HARD hold: advisor drifts into age-verification-override scope ---
    (run! actor "t10" coordinator-phase-3
          {:op :log-sales-record :store-id "store-1" :out-of-scope? true
           :patch {:units-sold 4 :item "cigarillos" :returns 0}})

    ;; --- HARD hold: advisor claims a direct actuation ---
    (run! direct "t11" coordinator-phase-3
          {:op :schedule-staffing-operation :store-id "store-1"
           :patch {:shift "evening-counter" :date "2026-08-14"}})

    ;; --- HARD hold: advisor proposes an op outside the closed allowlist ---
    (run! drifted "t12" coordinator-phase-3
          {:op :suspend-tobacco-retail-license :store-id "store-2"
           :patch {:reason "repeat shelf-label finding"}})

    {:db db :timeline @timeline}))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-str [v] (if (keyword? v) (name v) (str v)))

(defn- basis-str [basis]
  (str/join ", " (map kw-str basis)))

(defn- ops-str [ops]
  (str/join ", " (sort (map name ops))))

(defn- yes-no [b]
  (if b "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>"))

(defn- last-fact-for [ledger store-id]
  (last (filter #(= (:store-id %) store-id) ledger)))

(defn- status-cell
  "Only branches on fact types `tobaccoretailops.operation` actually
  appends to the store ledger: `:committed` (`:commit` node) and
  `:governor-hold` / `:approval-rejected` (`:hold` node)."
  [ledger store-id]
  (let [f (last-fact-for ledger store-id)]
    (case (:t f)
      :committed         "<span class=\"ok\">committed</span>"
      :governor-hold     (str "<span class=\"critical\">HARD hold: "
                              (esc (basis-str (:basis f))) "</span>")
      :approval-rejected "<span class=\"warn\">approval rejected</span>"
      "<span class=\"muted\">no ledger activity</span>")))

(defn- store-row [ledger {:keys [store-id name kind registered? licensed?]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc store-id) (esc name) (esc (kw-str kind))
          (yes-no registered?) (yes-no licensed?)
          (status-cell ledger store-id)))

(defn- phase-row [[p {:keys [label writes auto]}]]
  (format "        <tr><td>%s</td><td><code>%s</code>%s</td><td>%s</td><td>%s</td></tr>"
          p (esc label)
          (if (= p phase/default-phase) " <span class=\"muted\">(default)</span>" "")
          (if (seq writes) (str "<code>" (esc (ops-str writes)) "</code>")
              "<span class=\"muted\">none</span>")
          (if (seq auto) (str "<code>" (esc (ops-str auto)) "</code>")
              "<span class=\"muted\">none</span>")))

(defn- governor-rows
  "The gate table, derived from the governor's own vars -- never a
  hand-written restatement of them."
  []
  [(format "        <tr><td><code>:store-unverified</code></td><td><span class=\"critical\">HARD</span></td><td>target store must be present in the store directory AND independently <code>:registered?</code> + <code>:licensed?</code>; re-derived from the store record, never from the proposal's claim</td></tr>")
   (format "        <tr><td><code>:effect-not-propose</code></td><td><span class=\"critical\">HARD</span></td><td>every proposal's <code>:effect</code> must be <code>:propose</code>; any other value is a claim to actuate outside governance</td></tr>")
   (format "        <tr><td><code>:op-not-allowed</code></td><td><span class=\"critical\">HARD</span></td><td>closed op allowlist: <code>%s</code></td></tr>"
           (esc (ops-str governor/allowed-ops)))
   (format "        <tr><td><code>:scope-excluded</code></td><td><span class=\"critical\">HARD</span></td><td>%s scanned terms across op/summary/rationale/cites/value (age-verification override finalization, age-verification / ID-scanning terminal actuation, tobacco-retail-license suspension or revocation, licensing-authority enforcement) — permanent, un-overridable by any human approval</td></tr>"
           (count governor/scope-excluded-terms))
   (format "        <tr><td>low confidence</td><td><span class=\"warn\">ESCALATE</span></td><td>advisor confidence below <code>confidence-floor</code> = %s</td></tr>"
           governor/confidence-floor)
   (format "        <tr><td>always-escalate op</td><td><span class=\"warn\">ESCALATE</span></td><td><code>%s</code> — never auto-commit-eligible in any phase</td></tr>"
           (esc (ops-str governor/always-escalate-ops)))
   (format "        <tr><td>high-cost supply order</td><td><span class=\"warn\">ESCALATE</span></td><td><code>:coordinate-supply-order</code> whose drafted <code>:estimated-cost</code> exceeds <code>supply-cost-threshold</code> = %s</td></tr>"
           governor/supply-cost-threshold)])

(defn- timeline-row [{:keys [thread phase op store-id disposition audit]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td class=\"trace\">%s</td></tr>"
          (esc thread) phase (esc (kw-str op)) (esc store-id)
          (case disposition
            :commit   "<span class=\"ok\">commit</span>"
            :hold     "<span class=\"critical\">hold</span>"
            :escalate "<span class=\"warn\">escalate</span>"
            (str "<span class=\"muted\">" (esc (kw-str disposition)) "</span>"))
          (esc (str/join " → " (map kw-str audit)))))

(defn- hold-row [{:keys [op store-id basis violations confidence]}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td><span class=\"critical\">%s</span></td><td>%s</td><td>%s</td></tr>"
          (esc (kw-str op)) (esc store-id) (esc (basis-str basis))
          (esc (str/join " / " (map :detail violations)))
          (esc confidence)))

(defn- record-row [{:keys [op store-id payload]}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td class=\"trace\">%s</td></tr>"
          (esc (kw-str op)) (esc store-id) (esc (pr-str payload))))

(defn- ledger-row [{:keys [t op store-id disposition basis confidence]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc (kw-str t)) (esc (kw-str (or op :n-a))) (esc store-id)
          (esc (kw-str disposition)) (esc (basis-str basis))
          (esc (or confidence "-"))))

(def ^:private css
  (str "body{font:14px/1.5 -apple-system,BlinkMacSystemFont,'Hiragino Sans',sans-serif;margin:0;color:#1a1a1a;background:#f5f5f5}"
       ".bar{background:#2b1a12;color:#fff;padding:1.2rem 2rem}.bar h1{margin:0;font-size:1.15rem}"
       ".bar p{margin:.35rem 0 0;font-size:.8rem;color:#d9c9bd}"
       "main{max-width:1080px;margin:1.5rem auto;padding:0 1rem}"
       ".card{background:#fff;border-radius:8px;padding:1.2rem 1.4rem;margin-bottom:1.2rem;box-shadow:0 1px 3px rgba(0,0,0,.08)}"
       ".card h2{margin:0 0 .5rem;font-size:1rem}"
       ".muted{color:#777;font-size:.82rem}table{border-collapse:collapse;width:100%}"
       "th,td{text-align:left;padding:.42rem .5rem;border-bottom:1px solid #eee;font-size:.85rem;vertical-align:top}"
       "th{font-weight:600;color:#555}"
       ".ok{color:#0a7d33}.warn{color:#9a6700}.critical{color:#b41010;font-weight:600}"
       ".trace{color:#555;font-size:.78rem;word-break:break-all}"
       "code{background:#f0f0f0;padding:.1rem .3rem;border-radius:3px;font-size:.8rem}"))

(defn render
  "Renders the operator console from what the actor actually wrote."
  [{:keys [db timeline]}]
  (let [ledger  (vec (store/ledger db))
        records (vec (store/coordination-log db))
        stores  (store/all-stores db)
        holds   (filterv #(= :governor-hold (:t %)) ledger)]
    (str
     "<!doctype html><html lang=\"ja\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
     "<title>cloud-itonami-isic-4723 — tobacco retail operations console</title>"
     "<style>" css "</style></head><body>"
     "<header class=\"bar\"><h1>Specialized tobacco retail operations (ISIC 4723) — <code>tobaccoretailops</code></h1>"
     "<p>Generated by <code>tobaccoretailops.render-html</code> from a real "
     "<code>tobaccoretailops.operation</code> graph run over "
     "<code>tobaccoretailops.store/seed-db</code>. Nothing here is hand-written HTML: "
     "every row is read back out of the actor's own SSoT.</p></header><main>"

     "<section class=\"card\"><h2>Store directory</h2>"
     "<p class=\"muted\">Seeded by <code>store/demo-data</code>. <code>:registered?</code> / <code>:licensed?</code> "
     "are the governor's ground truth — a proposal's own claim about its store is never trusted.</p>"
     "<table><thead><tr><th>Store</th><th>Name</th><th>Kind</th><th>Registered</th>"
     "<th>Licensed</th><th>Last ledger fact</th></tr></thead><tbody>\n"
     (str/join "\n" (map #(store-row ledger %) stores))
     "\n      </tbody></table></section>"

     "<section class=\"card\"><h2>Governor gate</h2>"
     "<p class=\"muted\">Derived from <code>tobaccoretailops.governor</code>'s own vars. HARD rules are permanent "
     "and un-overridable by any human approval; ESCALATE rules route to a human coordinator.</p>"
     "<table><thead><tr><th>Rule</th><th>Class</th><th>Condition</th></tr></thead><tbody>\n"
     (str/join "\n" (governor-rows))
     "\n      </tbody></table></section>"

     "<section class=\"card\"><h2>Rollout phase gate</h2>"
     "<p class=\"muted\">From <code>tobaccoretailops.phase/phases</code>. "
     "<code>:flag-compliance-concern</code> is absent from every phase's auto set — structurally, not as a "
     "milestone still to come.</p>"
     "<table><thead><tr><th>Phase</th><th>Label</th><th>Writes</th><th>Auto-commit</th></tr></thead><tbody>\n"
     (str/join "\n" (map phase-row (sort-by key phase/phases)))
     "\n      </tbody></table></section>"

     "<section class=\"card\"><h2>Request timeline</h2>"
     "<p class=\"muted\">One row per graph run. The trace column is the run's own <code>:audit</code> channel "
     "— <code>:approval-requested</code> / <code>:approval-granted</code> are in-memory audit facts only and are "
     "never appended to the store ledger.</p>"
     "<table><thead><tr><th>Thread</th><th>Phase</th><th>Op</th><th>Store</th>"
     "<th>Disposition</th><th>Audit trace</th></tr></thead><tbody>\n"
     (str/join "\n" (map timeline-row timeline))
     "\n      </tbody></table></section>"

     "<section class=\"card\"><h2>HARD holds (" (count holds) ")</h2>"
     "<p class=\"muted\">Produced by <code>governor/check</code> on deliberately non-compliant input. "
     "Rule names and details below are the governor's own violation maps, read out of the ledger.</p>"
     "<table><thead><tr><th>Op</th><th>Store</th><th>Rule</th><th>Detail</th><th>Confidence</th></tr></thead><tbody>\n"
     (str/join "\n" (map hold-row holds))
     "\n      </tbody></table></section>"

     "<section class=\"card\"><h2>Committed coordination log (" (count records) ")</h2>"
     "<p class=\"muted\">The SSoT writes from <code>store/commit-record!</code>. "
     "<code>:approved-by</code> appears only where a human coordinator resumed the paused graph.</p>"
     "<table><thead><tr><th>Op</th><th>Store</th><th>Payload</th></tr></thead><tbody>\n"
     (str/join "\n" (map record-row records))
     "\n      </tbody></table></section>"

     "<section class=\"card\"><h2>Audit ledger (" (count ledger) ")</h2>"
     "<p class=\"muted\">Append-only, from <code>store/append-ledger!</code>. Only the <code>:commit</code> and "
     "<code>:hold</code> nodes ever write here.</p>"
     "<table><thead><tr><th>Fact</th><th>Op</th><th>Store</th><th>Disposition</th>"
     "<th>Basis</th><th>Confidence</th></tr></thead><tbody>\n"
     (str/join "\n" (map ledger-row ledger))
     "\n      </tbody></table></section>"

     "</main></body></html>\n")))

(defn -main [& args]
  (let [out    (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        f      (java.io.File. ^String out)]
    (when-let [p (.getParentFile f)] (.mkdirs p))
    (spit f (render result))
    (println "wrote" out
             "--" (count (store/ledger (:db result))) "ledger facts,"
             (count (filter #(= :governor-hold (:t %)) (store/ledger (:db result)))) "HARD holds,"
             (count (store/coordination-log (:db result))) "committed records")))
