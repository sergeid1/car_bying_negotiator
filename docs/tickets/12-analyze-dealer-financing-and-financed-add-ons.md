# 12 — Analyze dealer financing and financed add-ons

**What to build:** A financing Buyer receives independently calculated loan economics and full-term financed add-on effects within the Deal Analysis Report, without fabricated conclusions for incomplete or unsupported loans.

**Blocked by:** 02 — Establish golden fixtures and contract evaluation; 07 — Verify Deal Terms and create an immutable Dealer Offer; 11 — Generate the core Deal Analysis Report.

**Status:** Complete

- [x] A standard fixed-rate loan calculates payment, total scheduled payments, total interest, and Total Transaction Cost from verified inputs.
- [x] Zero APR follows an explicit supported branch with declared timing and rounding behavior.
- [x] Quoted and calculated payments show their difference, tolerance, and neutral possible causes.
- [x] Each financed add-on shows both cash price and incremental scheduled-payment and interest effect.
- [x] Purchase price, OTD Total, net amount due, amount financed, and Total Transaction Cost remain visibly separate.
- [x] Missing principal, APR, term, fees, or required timing yields an unavailable result rather than a guess.
- [x] Balloon or irregular schedules are flagged as unsupported and cannot receive an unqualified financing conclusion.
- [x] Golden and property fixtures cover zero APR, monotonic interest, quoted-payment mismatch, and financed add-ons.
- [x] Verified Finance Offer inputs and versioned financing results use JPA repositories, while loan formulas remain pure deterministic domain code and never depend on entity lifecycle or lazy-loaded state.

## Implementation notes

- Added Buyer-confirmed Finance Offers and versioned loan results through JPA repositories,
  protected by case locking and database uniqueness. Reports freeze both inputs and results.
- Added the dealer financing form and report section with separate purchase and loan amounts,
  payment reconciliation, and full-term add-on effects.
- Documented timing, rounding, fee treatment, supported bounds and API behavior in
  [the financing policy](../financing-policy.md).
- Verified golden and seeded property fixtures, HTTP confirmation/access/idempotency,
  persistence across restart, immutable reports, and repeated-analysis stale-evidence handling.
- Validation: full backend suite (70 tests), focused final financing/report regressions,
  frontend tests (5 tests), and production frontend build.
