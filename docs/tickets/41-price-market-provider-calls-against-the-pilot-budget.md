# 41 — Price market provider calls against the pilot budget and measure coverage

**What to build:** The parts of ticket 27 that need no licensed provider. An operator can see what
market and listing provider calls cost and how long they take, against a configured pilot budget, and
how well market evidence covers the supported jurisdiction, reported with denominators. Everything is
computed from records the service already keeps, so the licensed source plugs into the same
measurement when ticket 27 integrates it. Ticket 27 keeps the licensed-source integration, executed
terms, and the measured coverage of that source.

**Blocked by:** 09 — Produce a transparent Market Assessment; 38 — Make market and listing evidence
provider-ready without a licensed source.

**Status:** complete — a SUPPORT-only `/api/v1/operator/evidence/metrics` report prices market and
listing calls against a configured budget and measures coverage per supported jurisdiction, as of
2026-10-02. The figures describe development adapters; no budget value is set, and ticket 27 keeps the
licensed source.

- [x] Per-call cost and latency for `MARKET_RETRIEVAL` and `LISTING_RETRIEVAL` are reported per
      provider and status from `provider_usage` — call counts, calls with known cost, known cost, mean
      and p95 latency — with denominators, over a stated window. Calls without a known cost are counted
      and never treated as free.
- [x] A pilot budget for market and listing evidence (per-case cost ceiling and p95 latency ceiling)
      is configuration with no default. Startup fails closed under the `pilot`/`production` profiles or
      `evidence.deployment=production` when it is absent; development runs report the budget as
      unset rather than inventing a figure.
- [x] The report compares observed cost per case and p95 latency with the budget and states over,
      within, or not measurable (unknown cost, no calls); no automatic release decision is taken.
- [x] Market coverage and comparable relevance are measured for each supported jurisdiction
      (`jurisdiction_policies`), from stored `market_assessments` and `MARKET_COVERAGE` events: cases
      attempted, assessments available, unavailable, and too thin to conclude, and comparable counts —
      reported as counts with denominators, never as a summary adjective.
- [x] The measurements are exposed only to a `SUPPORT` operator credential, carry no case, Buyer,
      Dealer, VIN, or document identifiers, and follow the privacy-minimized metrics policy.
- [x] Tests cover unknown-cost calls, the missing-budget startup guard, empty windows, and
      jurisdiction denominators; the development adapters exercise the measurement end to end.
- [x] `docs/provider-inventory.md` and `docs/pilot-metrics-policy.md` describe the measurement and
      state that its figures describe development adapters until ticket 27 integrates a licensed source.

## Implementation notes — 2026-10-02

- `EvidenceMeasurementService` aggregates `provider_usage`, `market_assessments`, `comparable_snapshots`
  and `MARKET_COVERAGE` events over a half-open window; unknown-cost calls are counted and make the cost
  comparison `NOT_MEASURABLE` rather than free. The per-case comparison uses the maximum case total.
- `EvidenceBudget` reads `evidence.budget.per-case-cost-usd` and `evidence.budget.p95-latency-millis`
  with no defaults and fails startup under `pilot`/`production`/`prod` or `evidence.deployment=production`
  when either is missing; development reports `UNSET`.
- No migration. Coverage counts comparable selection, not independently labeled relevance.
