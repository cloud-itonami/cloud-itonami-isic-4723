(ns tobaccoretailops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300):
  this repo previously had NO demo page and no generator at all.

  This namespace drives the REAL actor stack -- `tobaccoretailops.store`
  (a freshly seeded MemStore) -> `tobaccoretailops.operation/build` (the
  langgraph StateGraph) -> `tobaccoretailops.advisor` ->
  `tobaccoretailops.governor` -> `tobaccoretailops.phase` -- through
  `langgraph.graph/run*`, exactly the way `tobaccoretailops.sim` does.
  NOTHING on the page is hand-typed runtime data: every store row, every
  ledger fact, every committed coordination record and every disposition
  is read back out of the store this scenario actually produced. The
  governor is never reimplemented here; this file only *drives* and
  *renders* it. The only hand-written content is the two contract tables
  (`op-notes` / `hard-check-rows`), each commented as documentation of
  fixed behaviour rather than telemetry.

  INPUT PROVENANCE. Every `:store-id` fed to the actor literally exists
  in `tobaccoretailops.store/demo-data`:
    `store-1`  Union Square Tobacconist          :registered? true  :licensed? true
    `store-2`  Riverside Cigar Lounge            :registered? true  :licensed? true
    `store-3`  Harbor Vape Specialty             :registered? true  :licensed? FALSE

  The scenario is authored here rather than reused from
  `tobaccoretailops.sim`, because that demo driver's `t7` step targets
  `store-99`, an id absent from the seed. Holding an id the store has
  never heard of exercises the governor's nil-lookup branch but proves
  nothing about this domain -- and a page built on a fabricated input is
  a fabricated page even when the code that produced it is real. The
  `:store-unverified` HARD hold below is therefore reached through
  `store-3`, a REAL seeded store whose tobacco retail license is
  genuinely unverified in the seed.

  WHICH SCENARIO EXERCISES WHAT
    store-1 -- the clean store. A full lifecycle across all four allowed
      ops and BOTH escalation gates: a phase-2 sales record (writes
      enabled, auto not yet -> human approves), a phase-3 sales record
      and staffing roster and low-cost supply order (all governor-clean
      -> auto-commit), a supply order above
      `governor/supply-cost-threshold` (always escalates -> human
      approves) and a compliance-concern flag (structurally never
      auto-eligible -> human approves). Six commits. Then a drifted
      advisor pushes it into age-verification-override territory and is
      HARD-held (`:scope-excluded`).
    store-2 -- the store where the actor gets stopped. A phase-1 supply
      order (that phase has not enabled the op -> phase hold, NOT a
      governor violation), a high-cost supply order the human REVIEWS
      AND DECLINES (`:approval-rejected`), a compromised advisor
      claiming direct actuation (`:effect-not-propose`) and an advisor
      proposing an op outside the closed allowlist (`:op-not-allowed`).
    store-3 -- registered but its tobacco retail license is unverified
      in the seed, so every op is HARD-held (`:store-unverified`).

  LEDGER FACT TYPES. Only `tobaccoretailops.operation`'s `:commit` and
  `:hold` nodes write to the store, so exactly three `:t` values ever
  reach `store/ledger`: `:committed`, `:governor-hold` and
  `:approval-rejected`. `:advisor-proposal`, `:approval-requested` and
  `:approval-granted` exist only on the in-memory `:audit` channel and
  are read from a run's final state, never looked for in the ledger.
  All three ledger types are reached by the scenario above AND handled
  in `outcome-cell` -- no branch here is decorative.

  A `:governor-hold` is NOT automatically a HARD hold. When the rollout
  phase simply has not enabled an op yet, `phase/gate` produces the same
  `:t` with an EMPTY `:basis` plus a `:phase-reason`. Only a non-empty
  `:basis` means the governor found a violation, and only that is
  permanent -- the rendering keeps the two apart.

  DETERMINISM. The advisor is the deterministic offline mock, the store
  is freshly seeded per run, and no timestamp, random value or
  wall-clock reading appears anywhere in the page. Two consecutive runs
  are byte-identical (render to two paths and diff).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [langgraph.graph :as g]
            [tobaccoretailops.advisor :as advisor]
            [tobaccoretailops.governor :as governor]
            [tobaccoretailops.operation :as op]
            [tobaccoretailops.phase :as phase]
            [tobaccoretailops.store :as store]))

(def ^:private coordinator
  "The human tobacco retail operations coordinator who adjudicates every
  escalated proposal in this scenario."
  "tobacco-retail-coordinator-1")

(defn- ctx [phase]
  {:actor-id "coord-1" :actor-role :tobacco-retail-coordinator :phase phase})

(defn- exec! [actor tid request phase]
  (g/run* actor {:request request :context (ctx phase)} {:thread-id tid}))

(defn- resume!
  "Hand an interrupted thread back to the human coordinator. `decision`
  is `:approved` or `:rejected` -- the same channel shape
  `tobaccoretailops.operation`'s `:request-approval` node reads."
  [actor tid decision]
  (g/run* actor {:approval {:status decision :by coordinator}}
          {:thread-id tid :resume? true}))

;; ----------------------------- advisor fixtures -----------------------------

(def ^:private direct-actuation-advisor
  "Fixture modelling a COMPROMISED advisor that claims a direct actuation
  (`:effect :commit`) instead of a proposal. Identical construction to
  `tobaccoretailops.sim`'s own `t9` step. It still targets a real seeded
  store (`store-2`) -- the fault being exercised is the advisor's, not a
  fabricated store id."
  (reify advisor/Advisor
    (-advise [_ _ req] (assoc (advisor/infer nil req) :effect :commit))))

(def ^:private out-of-allowlist-advisor
  "Fixture modelling an advisor that has DRIFTED into proposing an op
  outside `tobaccoretailops.governor/allowed-ops`. The proposal is
  otherwise impeccable -- `:effect :propose`, high confidence, a real
  seeded `:store-id` -- so the ONLY thing the governor can object to is
  the op itself. That is the point: the allowlist is closed by
  construction, not by the advisor's good behaviour. Every key it sets
  exists on this repo's own proposal shape (see
  `tobaccoretailops.advisor`'s namespace docstring)."
  (reify advisor/Advisor
    (-advise [_ _ {:keys [store-id op]}]
      {:op         op
       :store-id   store-id
       :summary    (str store-id " について許可当局側の判断領域にあたる操作を提案しようとした")
       :rationale  "この actor の closed allowlist の外にある操作。提案されること自体が逸脱。"
       :cites      [store-id]
       :effect     :propose
       :value      {:store-id store-id}
       :confidence 0.91})))

;; ----------------------------- scenario -----------------------------

(def ^:private scenario
  "The steps this console renders, in execution order. Every `:store-id`
  is present in `tobaccoretailops.store/demo-data`; every `:op` is a
  member of `tobaccoretailops.governor/allowed-ops` except the final
  step, which is deliberately outside it to exercise the closed-allowlist
  block. `:decision` resumes an interrupted thread with a human verdict."
  [;; ---- store-1: the full clean lifecycle ----
   {:tid "s1-sales-p2" :phase 2 :decision :approved
    :label "売上記録 (phase 2 — 書き込みは解禁済み・自動化は未解禁)"
    :request {:op :log-sales-record :store-id "store-1"
              :patch {:units-sold 18 :item "cigars" :returns 0}}}

   {:tid "s1-sales-p3" :phase 3
    :label "売上記録 (phase 3 — governor clean、自動コミット)"
    :request {:op :log-sales-record :store-id "store-1"
              :patch {:units-sold 25 :item "pipe tobacco" :returns 1}}}

   {:tid "s1-staffing-p3" :phase 3
    :label "売場スタッフ配置 (phase 3 — 自動コミット)"
    :request {:op :schedule-staffing-operation :store-id "store-1"
              :patch {:shift "afternoon-counter" :date "2026-07-20" :window "13:00-17:00"}}}

   {:tid "s1-supply-low-p3" :phase 3
    :label "仕入れ調達 (閾値以下 — 自動コミット)"
    :request {:op :coordinate-supply-order :store-id "store-1"
              :patch {:item "humidor supplies" :quantity 50 :estimated-cost 240.0}}}

   {:tid "s1-supply-high-p3" :phase 3 :decision :approved
    :label "仕入れ調達 (閾値超 — phase 3 でも必ず人間承認)"
    :request {:op :coordinate-supply-order :store-id "store-1"
              :patch {:item "premium cigar bulk order" :quantity 1 :estimated-cost 3200.0}}}

   {:tid "s1-concern-p3" :phase 3 :decision :approved
    :label "コンプライアンス懸念フラグ (どの phase でも構造的に自動化不可)"
    :request {:op :flag-compliance-concern :store-id "store-1"
              :patch {:concern "customer appeared underage, ID check inconclusive, referred to manager"
                      :confidence 0.92}}}

   {:tid "s1-out-of-scope" :phase 3
    :label "advisor が年齢確認の最終判断へ逸脱 (永久禁止領域 — HARD hold)"
    :request {:op :log-sales-record :store-id "store-1"
              :out-of-scope? true
              :patch {:units-sold 4 :item "cigarettes"}}}

   ;; ---- store-2: where the actor gets stopped ----
   {:tid "s2-supply-p1" :phase 1
    :label "仕入れ調達 (phase 1 — この phase では書き込み自体が未解禁)"
    :request {:op :coordinate-supply-order :store-id "store-2"
              :patch {:item "cigar humidor restock" :quantity 30 :estimated-cost 410.0}}}

   {:tid "s2-supply-declined" :phase 3 :decision :rejected
    :label "仕入れ調達 (閾値超 — 人間が確認のうえ却下)"
    :request {:op :coordinate-supply-order :store-id "store-2"
              :patch {:item "vintage cigar allocation" :quantity 2 :estimated-cost 5400.0}}}

   {:tid "s2-direct-actuation" :phase 3 :advisor direct-actuation-advisor
    :label "advisor が直接実行を主張 (:effect が :propose でない — HARD hold)"
    :request {:op :schedule-staffing-operation :store-id "store-2"
              :patch {:shift "morning-counter" :date "2026-07-23"}}}

   {:tid "s2-op-not-allowed" :phase 3 :advisor out-of-allowlist-advisor
    :label "advisor が allowlist 外の op を提案 (closed allowlist — HARD hold)"
    :request {:op :suspend-tobacco-license :store-id "store-2"}}

   ;; ---- store-3: license unverified in the seed ----
   {:tid "s3-sales-p3" :phase 3
    :label "売上記録 / store-3 (たばこ小売許可が未検証 — HARD hold)"
    :request {:op :log-sales-record :store-id "store-3"
              :patch {:units-sold 10 :item "vape pods" :returns 0}}}])

(defn run-demo!
  "Runs `scenario` through the REAL actor against a freshly seeded store.

  Returns `{:db <store> :runs [<step + :state + :status> ..]}`. `:state`
  is langgraph's own final state for that thread, so `:disposition`,
  `:verdict` and `:audit` are the actor's output and never this file's
  opinion. A step carrying a `:decision` is resumed ONLY when the graph
  actually interrupted -- if the actor had already committed or held, no
  human decision is invented for it."
  []
  (let [db (store/seed-db)
        default-actor (op/build db)
        runs (reduce
              (fn [acc {:keys [tid phase request decision advisor] :as step}]
                (let [actor (if advisor (op/build db {:advisor advisor}) default-actor)
                      r (exec! actor tid request phase)
                      r (if (and decision (= :interrupted (:status r)))
                          (resume! actor tid decision)
                          r)]
                  (conj acc (assoc (dissoc step :advisor)
                                   :state (:state r) :status (:status r)))))
              [] scenario)]
    {:db db :runs runs}))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-str [v] (if (keyword? v) (name v) (str v)))

(defn- basis-str [basis] (str/join ", " (map kw-str basis)))

(defn- audit-fact
  "The last audit fact of type `t` on a run's final state. The `:audit`
  channel (`{:reducer into}`) accumulates across an interrupt/resume, so
  a resumed run still carries its own `:approval-requested`."
  [state t]
  (last (filter #(= t (:t %)) (:audit state))))

(defn- hard-hold?
  "A governor HARD hold, as opposed to a rollout-phase hold. Both are
  written as `:t :governor-hold`; only the former carries violated rules
  in `:basis`, and only the former is permanent."
  [f]
  (and (= :governor-hold (:t f)) (seq (:basis f))))

(defn- verified-cell [flag]
  (if flag
    "<span class=\"ok\">確認済み</span>"
    "<span class=\"critical\">未確認</span>"))

(defn- outcome-cell
  "Renders one ledger fact as a disposition badge. Branches only on the
  three `:t` values that `tobaccoretailops.operation` actually appends to
  the store: `:committed`, `:governor-hold` and `:approval-rejected`."
  [f]
  (cond
    (nil? f) "<span class=\"muted\">no activity</span>"
    (= :committed (:t f)) "<span class=\"ok\">committed</span>"
    (hard-hold? f)
    (str "<span class=\"critical\">HARD hold &middot; " (esc (basis-str (:basis f))) "</span>")
    (= :governor-hold (:t f))
    (str "<span class=\"warn\">phase hold &middot; "
         (esc (kw-str (:phase-reason f :phase-gate))) "</span>")
    (= :approval-rejected (:t f)) "<span class=\"warn\">人間が承認を却下</span>"
    :else "<span class=\"muted\">in progress</span>"))

(defn- store-row
  "One seeded store. The commit/hold tally and the last adjudication are
  both counted out of the ledger this run produced -- the tally matters
  because the last fact alone would hide a store that committed cleanly
  several times before a drifted advisor got it held."
  [ledger {:keys [store-id kind registered? licensed?] :as s}]
  (let [facts (filter #(= (:store-id %) store-id) ledger)
        commits (count (filter #(= :committed (:t %)) facts))
        holds (- (count facts) commits)]
    (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td><td class=\"num\">%d / %d</td><td>%s</td></tr>"
            (esc store-id) (esc (:name s)) (esc (kw-str kind))
            (verified-cell registered?) (verified-cell licensed?)
            commits holds
            (outcome-cell (last (filter #(= (:store-id %) store-id) ledger))))))

(defn- step-outcome-cell [{:keys [state]}]
  (case (:disposition state)
    :commit (if (audit-fact state :approval-granted)
              "<span class=\"ok\">人間承認のうえ commit</span>"
              "<span class=\"ok\">auto-commit</span>")
    :hold (outcome-cell (or (audit-fact state :approval-rejected)
                            (audit-fact state :governor-hold)))
    :escalate "<span class=\"warn\">awaiting approval</span>"
    "<span class=\"muted\">in progress</span>"))

(defn- step-why-cell
  "Why the actor landed where it did -- read back out of the run's own
  audit facts (`:approval-rejected`, `:governor-hold`'s
  `:basis`/`:phase-reason`, `:approval-requested`'s `:reason`), never
  asserted by this file."
  [{:keys [state]}]
  (let [rejected (audit-fact state :approval-rejected)
        hold (audit-fact state :governor-hold)
        req (audit-fact state :approval-requested)]
    (cond
      rejected (esc (basis-str (:basis rejected)))
      (and hold (seq (:basis hold))) (esc (basis-str (:basis hold)))
      hold (esc (kw-str (:phase-reason hold :phase-gate)))
      req (esc (kw-str (:reason req)))
      :else "<span class=\"muted\">governor clean &middot; phase auto</span>")))

(defn- step-row [{:keys [tid label request phase] :as run}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td class=\"num\">%s</td><td>%s</td><td>%s</td></tr>"
          (esc tid) (esc label)
          (esc (kw-str (:op request))) (esc (:store-id request))
          phase
          (step-outcome-cell run)
          (step-why-cell run)))

(defn- ledger-row
  "One append-only decision fact. `:basis` means different things by fact
  type and the column says which: on a commit it is the proposal's
  `:cites`, on a governor HARD hold it is the violated rule names, and a
  hold carrying only a `:phase-reason` came from the rollout phase gate
  (`tobaccoretailops.phase/gate`) with no governor violation at all --
  labelling that `phase-gate:` keeps it from reading as a rule name."
  [{:keys [t op store-id basis phase-reason violations]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (kw-str t)) (esc (kw-str op)) (esc store-id)
          (str/join " &middot; "
                    (cond-> []
                      (seq basis) (conj (esc (basis-str basis)))
                      phase-reason (conj (str "phase-gate:" (esc (kw-str phase-reason))))))
          (esc (str/join " / " (remove nil? (map :detail violations))))))

(defn- payload-str
  "Deterministic key-sorted rendering of a committed record's payload
  (the store-id already has its own column, the approver its own)."
  [m]
  (->> (dissoc m :store-id :approved-by)
       (sort-by (comp str key))
       (map (fn [[k v]] (str (kw-str k) "=" (pr-str v))))
       (str/join ", ")))

(defn- coordination-row [{:keys [op store-id payload]}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (kw-str op)) (esc store-id) (esc (payload-str payload))
          (if-let [by (:approved-by payload)]
            (str "<span class=\"ok\">" (esc by) "</span>")
            "<span class=\"muted\">—</span>")))

;; ---- static contract description (NOT runtime telemetry) ----

(def ^:private op-notes
  ;; Hand-written prose describing this actor's FIXED contract, taken
  ;; from `tobaccoretailops.governor` / `tobaccoretailops.phase` /
  ;; `tobaccoretailops.advisor`. This is documentation of behaviour that
  ;; holds for every run, NOT telemetry from the scenario above. The
  ;; boolean columns beside it ARE read from the real vars
  ;; (`governor/allowed-ops`, `governor/always-escalate-ops`,
  ;; `phase/phases`), so a change to the contract moves the table.
  {:log-sales-record
   "在庫・売上・返品の観察記録のみ。年齢確認の可否判断には一切関与しない。"
   :schedule-staffing-operation
   "売場スタッフの配置予定 (ロスター) の提案のみ。年齢確認端末/IDスキャナーの操作は行わない。"
   :coordinate-supply-order
   (str "仕入れ調達の調整提案のみ。確定発注は人間。見積コストが "
        governor/supply-cost-threshold " を超えると phase 3 でも必ず人間承認へ。")
   :flag-compliance-concern
   "懸念を人間に surface するだけの op。いかなる年齢確認判断もこの op 自身は確定しない。"})

(defn- op-gate-rows
  "Rows derived from the actor's own contract vars, with hand-written
  prose in the last column (see `op-notes`). Not runtime telemetry."
  []
  (let [{:keys [writes auto]} (get phase/phases 3)]
    (for [o (sort-by name governor/allowed-ops)]
      (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
              (esc (name o))
              (if (contains? writes o)
                "<span class=\"ok\">許可</span>" "<span class=\"critical\">不可</span>")
              (cond
                (contains? governor/always-escalate-ops o)
                "<span class=\"warn\">常に人間承認 (auto 不可・構造的)</span>"
                (contains? auto o)
                "<span class=\"ok\">governor clean なら auto-commit</span>"
                :else "<span class=\"warn\">人間承認</span>")
              (esc (get op-notes o ""))))))

(def ^:private hard-check-rows
  ;; Hand-written description of the HARD, un-overridable checks in
  ;; `tobaccoretailops.governor/check` and the four `:rule` values they
  ;; emit. Fixed contract, NOT runtime telemetry -- but every one of
  ;; these four rules is actually reached by the scenario above, so the
  ;; ledger table below is the evidence for this table.
  ["        <tr><td><code>store-unverified</code></td><td>対象店舗の記録が存在しない、または <code>:registered?</code>/<code>:licensed?</code> が店舗側の記録で確認できない。提案側の自己申告は一切信用しない。</td></tr>"
   "        <tr><td><code>effect-not-propose</code></td><td>提案の <code>:effect</code> が <code>:propose</code> 以外。ガバナンス外で直接実行を主張したとみなす。</td></tr>"
   "        <tr><td><code>op-not-allowed</code></td><td>closed allowlist (<code>:log-sales-record</code> / <code>:schedule-staffing-operation</code> / <code>:coordinate-supply-order</code> / <code>:flag-compliance-concern</code>) の外の op。</td></tr>"
   "        <tr><td><code>scope-excluded</code></td><td>年齢確認の最終判断・年齢確認/IDスキャン端末の直接操作・たばこ小売許可の停止/取消・許可当局の判断領域に触れる提案。永久禁止で、人間承認による迂回路も存在しない。</td></tr>"])

(defn render
  "Renders the full operator-console.html document from a `run-demo!`
  result. Every table below except the two explicitly-commented contract
  tables is derived from the store this run actually produced."
  [{:keys [db runs]}]
  (let [ledger (vec (store/ledger db))
        stores (store/all-stores db)
        hard-holds (->> ledger (filter hard-hold?) (mapcat :basis) distinct sort)]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-4723 &middot; specialized-tobacco-retail-operations</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Retail sale of tobacco products in specialized stores (ISIC 4723) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · 年齢確認の最終判断・許可の停止/取消は永久に scope 外</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>登録店舗ディレクトリ</h2>\n"
     "    <p class=\"muted\">Demo snapshot — <code>tobaccoretailops.store</code> の seed に対して <code>tobaccoretailops.render-html</code> が実 actor をビルド時に走らせて生成 (<code>clojure -M:dev:render-html</code>)。営業登録とたばこ小売許可はいずれも店舗側の記録から再導出しており、提案の自己申告は使わない。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Store</th><th>Name</th><th>Kind</th><th>営業登録</th><th>たばこ小売許可</th><th>commit / hold</th><th>直近の裁定</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial store-row ledger) stores)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (TobaccoRetailGovernor × rollout phase)</h2>\n"
     "    <p class=\"muted\">Phase 3 (supervised-auto) 時点の姿勢。SOFT escalate は 2 つ: LLM 信頼度が "
     governor/confidence-floor " 未満、および <code>:coordinate-supply-order</code> の <code>:estimated-cost</code> が "
     governor/supply-cost-threshold " 超。HARD hold は下表のとおり人間承認でも解除できない。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>phase 3 書き込み</th><th>自動化</th><th>境界</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (op-gate-rows)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>HARD checks（永久・上書き不可）</h2>\n"
     "    <p class=\"muted\">この 4 つの <code>:rule</code> はいずれも人間の承認で迂回できない。下の監査台帳で "
     (esc (basis-str hard-holds)) " が実際に発火している。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Rule</th><th>発火条件</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" hard-check-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>このシナリオの実行結果</h2>\n"
     "    <p class=\"muted\">1 行 = 1 回の actor 実行 (intake → advise → govern → decide → commit | hold | approval)。裁定と理由は langgraph の最終 state と audit fact から読み出したもの。phase hold は「まだ解禁されていない」であって governor 違反ではない。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Thread</th><th>Step</th><th>Op</th><th>Store</th><th>Phase</th><th>裁定</th><th>理由</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map step-row runs)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>監査台帳 (append-only)</h2>\n"
     "    <p class=\"muted\">SSoT に書き込まれた不可変の決定事実。<code>:commit</code> ノードと <code>:hold</code> ノードだけがここに書けるので、残る <code>:t</code> は <code>committed</code> / <code>governor-hold</code> / <code>approval-rejected</code> の 3 種だけ。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Store</th><th>Basis</th><th>Violation detail</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>確定した調整レコード</h2>\n"
     "    <p class=\"muted\">commit ノードだけが SSoT に書ける。承認者欄が埋まっている行は、人間が実際に sign-off したもの。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Store</th><th>Payload</th><th>承認者</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map coordination-row (store/coordination-log db))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html (render result)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger (:db result))) "ledger facts,"
             (count (store/coordination-log (:db result))) "committed records,"
             (count (:runs result)) "actor runs )")))
