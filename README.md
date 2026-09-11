# cloud-itonami-isic-4723

**Retail sale of tobacco products in specialized stores** — ISIC Rev.4 class 4723.

A coordination-only actor for specialized tobacco retail stores — tobacconists, cigar lounges, pipe & tobacco shops, and vape/e-cigarette specialty retailers (distinct from specialized food retail, ISIC 4721, and stand-alone beverage retail, ISIC 4722) — behind an independent Governor that earns advisor trust through structured oversight: proposal → advise → govern → decide → commit|hold|escalate.

## Features

- **Closed proposal-op allowlist**: `log-sales-record`, `schedule-staffing-operation`, `coordinate-supply-order`, `flag-compliance-concern` (all `:effect :propose`).
- **Three HARD governor checks** (permanent, un-overridable):
  1. **Store unverified** — the target store's business registration AND tobacco retail license must exist AND be independently registered/licensed in the store.
  2. **Effect is :propose** — any other `:effect` value is rejected.
  3. **Scope exclusion** — finalizing an age-verification override, directly operating/controlling an age-verification or ID-scanning terminal, and tobacco-retail-license suspension/revocation/licensing-authority enforcement (inspection clearance, excise/customs enforcement, compliance enforcement) are permanently blocked.
- **Two ESCALATE (SOFT) gates**, either forces human sign-off:
  - `:flag-compliance-concern` — ALWAYS escalates, regardless of confidence or phase. A "flag a concern" op is never auto-commit-eligible and never finalizes an age-verification decision itself — it only surfaces the concern for a human.
  - `:coordinate-supply-order` above a cost threshold — a large-value procurement proposal always needs a human sign-off.
  - (LLM confidence below the floor also escalates, as with every sibling actor.)
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: sales-record logging only (approval-gated)
  - Phase 2: + staffing-operation scheduling, supply-order proposals (approval-gated)
  - Phase 3: auto-commits clean, high-confidence, low-cost proposals (compliance concerns and high-cost supply orders always escalate)
- **Append-only audit ledger** — every decision is an immutable log entry.
- **langgraph-clj StateGraph** — one request = one supervised run; human-in-the-loop via `interrupt-before`.

## Out of scope (structural, not a rollout milestone)

This actor is **operations coordination only**. It never performs or authorizes:

- Finalizing an age-verification override or decision.
- Directly operating or controlling an age-verification/ID-scanning terminal (e.g. overriding an ID check, disabling an age gate).
- Tobacco-retail-license suspension or revocation.
- Tobacco-retail-licensing-authority enforcement (inspection clearance, excise/customs enforcement, compliance enforcement).

The governor's `scope-exclusion-violations` check re-scans every proposal for this failure mode independently of the advisor's own framing, and treats it as a HARD, permanent block regardless of confidence or how clean everything else is. This actor never finalizes an age-verification override under any circumstance — this is a hard, permanent block, not merely an escalation gate.

## Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
clojure -M:dev -P

# Run tests
clojure -M:dev:test

# Run linter
clojure -M:lint

# Run demo
clojure -M:run
```

## Test suite

- `test/tobaccoretailops/governor_test.cljk` — unit tests of governor hard checks, scope exclusion, and a dedicated regression test asserting the default mock-advisor proposals never self-trip scope-exclusion
- `test/tobaccoretailops/advisor_test.cljk` — advisor proposal shape and consistency
- `test/tobaccoretailops/phase_test.cljk` — rollout phase logic
- `test/tobaccoretailops/governor_contract_test.cljk` — full graph integration, audit trail
- `test/tobaccoretailops/store_contract_test.cljk` — Store protocol and MemStore implementation

## Modules

- `tobaccoretailops.store` — SSoT (MemStore, String-keyed store directory, append-only ledger)
- `tobaccoretailops.advisor` — contained intelligence node (mock + real-LLM seam)
- `tobaccoretailops.governor` — independent compliance layer
- `tobaccoretailops.phase` — staged rollout (0→3)
- `tobaccoretailops.operation` — langgraph-clj StateGraph
- `tobaccoretailops.sim` — demo driver

## License

AGPL-3.0-or-later. See LICENSE file.

## Governance

This actor is part of the cloud-itonami Wave 2 (coordination/logistics/trade) fleet. See ADR-2607121000 and ADR-2691004723 (`cloud-itonami-isic-4723-tobacco-retail-coverage`) for design decisions.
