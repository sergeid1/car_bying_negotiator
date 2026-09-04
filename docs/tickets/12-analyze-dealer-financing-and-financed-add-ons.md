# 12 — Analyze dealer financing and financed add-ons

**What to build:** A financing Buyer receives independently calculated loan economics and full-term financed add-on effects within the Deal Analysis Report, without fabricated conclusions for incomplete or unsupported loans.

**Blocked by:** 02 — Establish golden fixtures and contract evaluation; 07 — Verify Deal Terms and create an immutable Dealer Offer; 11 — Generate the core Deal Analysis Report.

**Status:** ready-for-agent

- [ ] A standard fixed-rate loan calculates payment, total scheduled payments, total interest, and Total Transaction Cost from verified inputs.
- [ ] Zero APR follows an explicit supported branch with declared timing and rounding behavior.
- [ ] Quoted and calculated payments show their difference, tolerance, and neutral possible causes.
- [ ] Each financed add-on shows both cash price and incremental scheduled-payment and interest effect.
- [ ] Purchase price, OTD Total, net amount due, amount financed, and Total Transaction Cost remain visibly separate.
- [ ] Missing principal, APR, term, fees, or required timing yields an unavailable result rather than a guess.
- [ ] Balloon or irregular schedules are flagged as unsupported and cannot receive an unqualified financing conclusion.
- [ ] Golden and property fixtures cover zero APR, monotonic interest, quoted-payment mismatch, and financed add-ons.
- [ ] Verified Finance Offer inputs and versioned financing results use JPA repositories, while loan formulas remain pure deterministic domain code and never depend on entity lifecycle or lazy-loaded state.
