# 62 — Compare quotes across candidates

**What to build:** A Buyer negotiating on several picked cars sees their latest verified quotes side by side: OTD total, selling price, fees, add-ons, and each car's market position. Spec AGT-09.

**Blocked by:** 60 — Request an itemized quote and ingest dealer replies; 56 — Shortlist candidates and open a Buying Case from one.

**Status:** complete — journey quote comparison in `com.dealanalyzer.journey` (no migration), as of 2026-10-04.
`GET /api/v1/journeys/{id}/quote-comparison` (Buyer Account session) reads the Buying Cases linked to the journey with
ticket 56's ownership, active-account and active-case checks. Figures come only from the latest VERIFIED Dealer Offer;
a car without one shows its state (awaiting quote, quote unverified) and no amounts. A car's OTD counts as complete
when the offer has a Buyer-verified quoted OTD and the stored cash reconciliation for that exact offer is within
tolerance, the rule the report already uses; a category with no lines on a reconciled quote shows "None listed",
otherwise each car says why OTD cannot be compared (no quoted OTD, figures off by an amount, unclassified charge,
suspected missing pages). Market position and market range come only from a stored report built on that same offer.
Amounts reuse `AmountEvidence` and `EvidenceSource.tsx`. `QuoteComparison.tsx` in the journey view orders by the
Buyer's shortlist by default or by a Buyer-chosen column; OTD ordering is offered only when every car is complete.

- [x] The comparison uses only verified Dealer Offers; cars without one show their state instead of an estimate.
- [x] OTD totals are compared only when each is complete; otherwise the comparison says which figures are missing.
- [x] Each amount links to its evidence, as in the report.
- [x] Ordering follows a Buyer-chosen column, never a dealer attribute.
- [x] The comparison works at 360 px in both themes and is registered in `Accessibility.test.tsx`.
