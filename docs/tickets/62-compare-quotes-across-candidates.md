# 62 — Compare quotes across candidates

**What to build:** A Buyer negotiating on several picked cars sees their latest verified quotes side by side: OTD total, selling price, fees, add-ons, and each car's market position. Spec AGT-09.

**Blocked by:** 60 — Request an itemized quote and ingest dealer replies; 56 — Shortlist candidates and open a Buying Case from one.

**Status:** ready-for-agent (60 and 56 complete) — filed on 2026-10-03 from specification 2.0.

- [ ] The comparison uses only verified Dealer Offers; cars without one show their state instead of an estimate.
- [ ] OTD totals are compared only when each is complete; otherwise the comparison says which figures are missing.
- [ ] Each amount links to its evidence, as in the report.
- [ ] Ordering follows a Buyer-chosen column, never a dealer attribute.
- [ ] The comparison works at 360 px in both themes and is registered in `Accessibility.test.tsx`.
