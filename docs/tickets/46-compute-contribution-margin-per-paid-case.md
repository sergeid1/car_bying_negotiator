# 46 — Compute contribution margin per paid case from recorded costs

**What to build:** The parts of ticket 33 that need no payment provider and no outcome decision. An
operator can see paid conversion, refunds, and completed cases over a stated window, and the
contribution margin of a paid case net of payment fees, provider and extraction costs, and human
support time, computed from records the service keeps. Ticket 33 keeps the frozen decision thresholds,
the decision recorded against them, and any published case studies.

**Blocked by:** 41 — Price market provider calls against the pilot budget; 44 — Make the one-time
offer purchasable without a payment provider.

**Status:** complete — a SUPPORT-only `/api/v1/operator/contribution/metrics` report gives paid
conversion, refunds, and completed cases with denominators for UTC-day case-start cohorts, and
contribution margin per paid case from recorded costs, configured fees, and support time, as of
2026-10-03. No fee schedule or support rate is shipped, so margin reports as unavailable until one is
configured; ticket 33 keeps the decision thresholds and the decision.

- [x] Paid conversion, refunds, and completed cases are reported with denominators over a stated
      half-open window: qualified cases started, cases with a verified offer, completed reports, paid
      purchases, and refunded purchases, each as counts with the denominator used for every rate.
      Removed cases are reported from anonymous totals that survive deletion, never imputed.
- [x] A payment fee schedule (percentage and fixed amount per purchase, currency, version, and source)
      is versioned configuration with no default. Without one, fees and margin are reported as
      `UNAVAILABLE` with a reason, never as zero. `DevelopmentNoChargeCheckout` purchases are reported
      separately and never counted as revenue.
- [x] Provider and extraction costs per case come from `provider_usage` across every metered operation.
      Calls without a known cost are counted and make that case's margin `NOT_MEASURABLE` rather than
      treating the calls as free.
- [x] A `SUPPORT`-only endpoint records human support minutes against a case, idempotently, and a
      configured support cost rate with no default prices them. Buyer credentials cannot record time.
      Support time is stored with the case and deleted with it.
- [x] Contribution margin per paid case is price minus refunds, payment fees, known provider and
      extraction costs, and priced support time, reported per case in aggregate (count, sum, mean,
      median) with the number of cases that were not measurable and why.
- [x] When a case is deleted, its revenue, refund, known cost, unknown-cost flag, and support minutes
      are added to anonymous totals, recorded in `docs/case-deletion-policy.md`, so removal does not
      silently improve or worsen the margin.
- [x] Willingness-to-pay survey responses stay reported only in `willingnessToPay` and are never
      combined with purchases into a conversion figure; a test enforces it.
- [x] The report is exposed only to a `SUPPORT` operator credential, carries no case, Buyer, Dealer,
      VIN, or document identifiers, and follows the privacy-minimized metrics policy.
- [x] Tests cover an empty window, unknown-cost calls, a missing fee schedule, refunds, development
      checkout purchases, and deletion of a paid case with support time.
- [x] `docs/pilot-metrics-policy.md` describes the report and states that no continue, reprice, or stop
      decision is taken by it.

## Implementation notes — 2026-10-03

- `ContributionService` reads the `case_contribution_facts` view (V28) for retained cases and the
  anonymous `contribution_totals` distribution for removed ones, so the median stays exact after
  deletion. Cases deleted before V28 are reported as unavailable historical coverage, never imputed.
- `POST /api/v1/operator/contribution/cases/{caseId}/support-time` records `{entryId, minutes}`
  idempotently in `case_support_time`, which is deleted with the case.
- Pricing reads `contribution.fees.{percentage,fixed-minor,currency,version,source}` and
  `contribution.support.{usd-per-minute,version,source}` with no defaults; only USD fees are measurable.
- The window is a case-start cohort, not a purchase-event window. Refunds are full refunds only, and the
  modelled payment fee stays charged on refund. SQL was exercised on H2 in PostgreSQL mode.
