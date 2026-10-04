# 33 — Obtain paid-conversion and unit-economics evidence

**What to build:** Evidence that Buyers will pay and that a completed case is worth more than it costs to serve, measured from purchases rather than from stated willingness to pay.

**Blocked by:** 19 — Capture privacy-minimized feedback and pilot metrics; 32 — Publish an honest sample report and a defined one-time offer.

**Status:** blocked on live pilot purchases — narrowed on 2026-10-04: the remaining machinery is built. Operators can freeze
continue, reprice and stop thresholds before outcome review and record a human decision against them. A case-study
policy and template gate any publication. No real purchases, decision or case study exist yet, so the
evidence criteria stay open.

- [ ] Paid conversion, refunds, and completed cases are reported with denominators over a defined window.
- [ ] Contribution margin is computed net of payment fees, provider and extraction costs, and human support time per case.
- [x] Survey willingness-to-pay responses are stored and reported separately from purchases and are never combined into one conversion figure.
- [ ] Buyer-reported outcomes remain distinct from verified document evidence in every dashboard and write-up.
- [ ] Decision thresholds for continuing, repricing, or stopping are frozen before outcome review, and the decision is recorded against them.
- [ ] Permissioned case studies, where published, carry denominators and state what was not measured.

## Implementation notes — 2026-10-04

- `/api/v1/operator/contribution/decisions` (SUPPORT only) freezes versioned threshold sets on PAID_PURCHASE_RATE,
  REFUND_RATE, COMPLETED_REPORT_RATE and MEDIAN_CONTRIBUTION_USD, with minimum denominators, for a cohort window.
  It opens a review that saves the first report snapshot, and it records one human CONTINUE, REPRICE or STOP
  decision per window. Each decision carries a rationale code, the frozen version and a met, not met or not
  measurable result for each threshold. Records are append-only and `@Immutable` in V43, and they carry no case
  or Buyer identifiers.
- The first read of the contribution report for a window opens its review and permanently refuses freezing for
  that window and any overlapping one. There is no reset, so operators preregister before they look.
  A single guard row serializes freezing and first reads, and contention returns 409. Not-measurable or partial
  metrics never count as met.
- Decision evidence comes only from the ticket 46 report. Willingness-to-pay and Buyer-entered outcomes are
  excluded, and a test enforces it. The paid conversion denominator is all case starts in the cohort, as in ticket 46.
- `docs/case-study-policy.md` and `docs/case-studies/template.md` require specific Buyer permission,
  denominators, separate evidence categories and a "what was not measured" section. `CaseStudyPublicationTest`
  checks every file under `docs/case-studies/published/`, and none exist yet.
- Still open: real paid conversion, refunds and margin from pilot purchases with an approved fee schedule and
  support rate, thresholds frozen for the pilot window, the decision recorded against them, and any permissioned
  case study.
