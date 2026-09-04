# 08 — Calculate and reconcile cash deal economics

**What to build:** A cash Buyer receives deterministic and reproducible selling-price, fee, tax, OTD, trade, credit, and net-due economics under a versioned jurisdiction policy.

**Blocked by:** 02 — Establish golden fixtures and contract evaluation; 07 — Verify Deal Terms and create an immutable Dealer Offer.

**Status:** complete

- [x] Calculations use currency-aware decimal arithmetic, retain full intermediate precision, and disclose policy-controlled rounding.
- [x] The result separates vehicle subtotal, dealer charges, taxes, government charges, OTD Total, trade terms, down payment, credits, and net amount due.
- [x] Trade allowance, down payment, outside financing, and other Buyer funds are never counted as dealer discounts.
- [x] Conditional rebates are excluded from guaranteed savings until eligibility is verified.
- [x] Unknown fees remain neutral and visible rather than being silently treated as favorable or governmental.
- [x] Recomputed totals expose discrepancy amount, tolerance, evidence, and neutral reason instead of overwriting quoted totals.
- [x] Golden and property fixtures cover the canonical formulas, caps, credits, rounding, and cash purchase without financing questions.
- [x] Persisted cash-economics and reconciliation results reference the exact Dealer Offer and policy versions through JPA mappings while all arithmetic remains in pure domain code independent of Hibernate.

## Completion notes — 2026-09-04

- Added pure, currency-aware cash economics that applies versioned taxability, tax-credit caps, full-precision intermediates, disclosed rounding, and separated OTD/net-due components.
- Preserved unknown charges neutrally, excluded unverified conditional rebates from guaranteed reductions, and created distinct immutable results when rebate eligibility is verified.
- Added authenticated calculation/latest-result endpoints and Flyway-managed JPA mappings to exact Dealer Offer and jurisdiction-policy records.
- Added golden, seeded property, and HTTP acceptance coverage for formulas, reconciliation, caps, credits, eligibility, stale-offer protection, and cash analysis without financing inputs.
- Verification passed for all ticket-specific tests, the backend suite excluding one externally blocked MCP-provider test, frontend tests/build, and `git diff --check`.
