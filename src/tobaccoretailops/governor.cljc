(ns tobaccoretailops.governor
  "TobaccoRetailGovernor -- the independent compliance layer that earns
  the TobaccoRetailAdvisor the right to commit. The advisor has no notion
  of whether a store is actually registered and licensed (i.e. its
  business registration AND tobacco retail license are on file), whether
  its own proposed `:effect` secretly claims a direct actuation instead
  of a mere proposal, or whether it has silently drifted into a
  permanently out-of-scope decision area, so this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- OPERATIONS COORDINATION
  ONLY (sales-record logging, staffing-operation scheduling, supply-order
  coordination, compliance-concern flagging) for specialized tobacco
  retail stores (tobacconist, cigar lounge, pipe & tobacco shop, vape
  specialty retailer). It NEVER performs or authorizes:
    - finalizing an age-verification override/decision
    - directly operating or controlling an age-verification or
      ID-scanning terminal
    - tobacco-retail-license suspension or revocation
    - tobacco-retail-licensing-authority enforcement (inspection
      clearance, excise/customs enforcement, compliance enforcement)

  Three HARD checks, ALL permanent, un-overridable by any human approval:

    1. Store unverified          -- the target store (business
                                    registration + tobacco retail
                                    license) record must exist AND be
                                    independently confirmed
                                    `:registered?`/`:licensed?` in the
                                    store before ANY proposal for it may
                                    commit or even escalate. Never trusts
                                    a proposal's own claim about the
                                    store -- re-derived from the store's
                                    own record, the same 'ground truth,
                                    not self-report' discipline every
                                    sibling actor's governor uses.
    2. Effect not :propose       -- every proposal's `:effect` MUST be
                                    `:propose`. Any other effect value
                                    is, by construction, a claim to
                                    directly actuate/commit outside
                                    governance -- HARD block, not merely
                                    low-confidence.
    3. Scope exclusion           -- ANY proposal (regardless of op)
                                    whose op, rationale, summary,
                                    citations or draft value touches
                                    age-verification-override-finalization/
                                    age-verification-or-id-scanning-
                                    terminal-actuation/tobacco-retail-
                                    license-suspension-or-revocation/
                                    tobacco-retail-licensing-authority
                                    territory is a HARD, PERMANENT block
                                    -- this actor's charter excludes that
                                    territory structurally, not as a
                                    rollout milestone. Evaluated
                                    UNCONDITIONALLY on every proposal. An
                                    op outside the closed four-op
                                    allowlist is the SAME failure mode
                                    (an advisor proposing something it
                                    was never authorized to propose) and
                                    is folded into this same check. This
                                    is also the SOLE authority that can
                                    ever act on an age-verification
                                    override -- and it never grants one;
                                    it only ever hard-blocks a proposal
                                    that attempts to finalize one, with
                                    no auto-commit-eligible or
                                    human-approval-eligible path around
                                    this block.

  Two ESCALATE (SOFT) gates, either forces human sign-off:
    - LLM confidence below the floor.
    - The op is `:flag-compliance-concern` -- ALWAYS escalates to a
      human, regardless of confidence, regardless of how clean the
      proposal otherwise is. This op only ever SURFACES a concern for a
      human -- it never itself finalizes any age-verification decision.
      `tobaccoretailops.phase` independently agrees:
      `:flag-compliance-concern` is never a member of any phase's
      `:auto` set either -- two layers, not one.
    - A `:coordinate-supply-order` whose drafted `:value` names an
      `:estimated-cost` above `supply-cost-threshold` -- a large-value
      procurement proposal always needs a human sign-off, even when the
      governor and phase would otherwise allow auto-commit.

  Scope-exclusion-term discipline (see ADR-2607121000 / sibling actors'
  own fix history): terms are phrased as the finalization/execution
  ACTION (e.g. \"finalize the age-verification override\", \"control the
  age verification terminal\"), never as a bare noun (e.g. bare \"age\"
  or bare \"verification\") -- a bare noun would accidentally match this
  SAME namespace's own default mock-advisor disclaimer text for a
  legitimate, allowed proposal (e.g. `advisor`'s own
  \"...年齢確認の実施可否については関与しない\" / \"...年齢確認端末の設定変更は
  行わない\" rationale strings, which legitimately discuss age
  verification WITHOUT finalizing or actuating it), causing the actor to
  self-block on its own happy path. See
  `tobaccoretailops-scope-exclusion-test`'s dedicated
  `default-mock-advisor-proposals-never-self-trip-scope-exclusion` test,
  which asserts every default op's proposal clears this check. Every
  \"flag a concern\" op (`:flag-compliance-concern`) is intentionally
  ABSENT from `allowed-ops`' path to `:commit` -- see `always-escalate-ops`
  and `tobaccoretailops.phase`'s permanently-absent `:auto` membership --
  a concern-flag never itself finalizes anything, it only ever routes to
  a human."
  (:require [kotoba.lang.text :as str]
            [tobaccoretailops.store :as store]))

(def confidence-floor 0.6)

(def supply-cost-threshold
  "Example specialized-tobacco-retail procurement threshold
  (USD-equivalent units, domain-illustrative -- not a universal
  cross-domain constant). A `:coordinate-supply-order` proposal citing an
  `:estimated-cost` above this value ALWAYS escalates to human sign-off,
  regardless of confidence or rollout phase."
  750.0)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a scope
  violation by construction (see `scope-exclusion-violations`). No op in
  this set ever finalizes an age-verification override -- that authority
  is permanently out of scope, see `scope-excluded-terms`."
  #{:log-sales-record :schedule-staffing-operation
    :coordinate-supply-order :flag-compliance-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not.
  `:flag-compliance-concern` only ever SURFACES a concern -- it must
  never be auto-commit-eligible, in any phase, for any confidence."
  #{:flag-compliance-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- finalizing an
  age-verification override, directly operating/controlling an
  age-verification or ID-scanning terminal, or tobacco-retail-license
  suspension/revocation/licensing-authority enforcement. Scanned across
  the proposal's op/summary/rationale/cites/value, never trusting the
  advisor's own framing of its intent.

  Every term is phrased as the finalization/execution ACTION, not a bare
  noun -- see the namespace docstring's self-trip-avoidance note."
  ["age verification override" "override age verification"
   "overrode age verification" "overriding age verification"
   "finalize age verification" "finalized age verification"
   "finalize the age verification" "finalize the age-verification override"
   "age-verification override" "bypass age verification"
   "bypassed age verification" "waive age verification"
   "waived age verification" "skip age verification"
   "skipped age verification" "ignore age verification"
   "ignored age verification" "disable the age gate" "disable age gate"
   "override the age gate" "bypass the age gate" "override the id check"
   "bypass the id check" "override id scan" "actuate the id scanner"
   "control the id scanner" "control the age verification terminal"
   "actuate the age verification terminal"
   "年齢確認を確定" "年齢確認の確定" "年齢確認を上書き" "年齢確認の上書き"
   "年齢確認を無視" "年齢確認をバイパス" "年齢確認をスキップ"
   "年齢確認端末を操作" "年齢確認端末の直接操作" "IDスキャナーを操作"
   "IDスキャナーの直接操作"
   "tobacco license suspension" "license-suspension" "license suspension"
   "permit revocation" "permit-revocation" "regulatory enforcement"
   "compliance enforcement" "inspection clearance" "excise enforcement"
   "たばこ小売許可停止" "たばこ小売許可取消" "営業許可取消" "許可証停止" "許可証取消"])

;; ----------------------------- checks -----------------------------

(defn- store-unverified-violations
  "The target store (business registration + tobacco retail license) must
  exist AND be independently `:registered?`/`:licensed?` in the store --
  never trust the proposal's own `:store-id` claim without a store
  lookup."
  [{:keys [store-id]} st]
  (let [s (store/store-record st store-id)]
    (when-not (and s (:registered? s) (:licensed? s))
      [{:rule :store-unverified
        :detail (str store-id " は未登録または未検証の店舗(営業登録/たばこ小売許可) -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim to
  directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist, or
  one whose content touches age-verification-override-finalization/
  age-verification-or-id-scanning-terminal-actuation/tobacco-retail-
  license-suspension-or-revocation/licensing-authority territory,
  regardless of confidence or how clean every other check is. Evaluated
  UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "年齢確認の最終判断/年齢確認端末の直接操作/たばこ小売許可の停止・取消/たばこ小売許可当局の判断領域に触れる提案は永久に禁止"}])))

(defn- high-cost-supply-order?
  "A `:coordinate-supply-order` proposal citing an `:estimated-cost`
  above `supply-cost-threshold` -- always needs human sign-off (SOFT
  escalate, not a hard block: the order itself is in scope, only its
  size requires a human)."
  [proposal]
  (and (= :coordinate-supply-order (:op proposal))
       (some-> proposal :value :estimated-cost (> supply-cost-threshold))))

(defn check
  "Censors a TobaccoRetailAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [store-id (or (:store-id proposal) (:store-id request))
        hard (into []
                   (concat (store-unverified-violations {:store-id store-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (or (always-escalate-ops (:op proposal))
                              (high-cost-supply-order? proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :store-id   (:store-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
