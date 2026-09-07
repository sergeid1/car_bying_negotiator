# 13 — Compare one outside Financing Scenario

**What to build:** A Buyer can enter one outside financing offer and compare its full-term economics with dealer financing under aligned purchase assumptions.

**Blocked by:** 12 — Analyze dealer financing and financed add-ons.

**Status:** Complete

- [x] The Buyer can enter principal, APR, term, payment timing, and applicable fees for one outside offer.
- [x] The comparison identifies and aligns differing purchase assumptions before presenting a cost delta.
- [x] Results show payment, interest, scheduled payments, fees, and Total Transaction Cost for each supported scenario.
- [x] The incremental full-term effect of APR and financed add-ons is visible and reproducible.
- [x] Incomplete or non-comparable scenarios explain what is missing and do not fabricate a winner.
- [x] The outside offer remains Buyer-selected financing and is never represented as a dealer discount.
- [x] The Buyer-entered outside Finance Offer and immutable comparison result are stored through JPA repositories with explicit ownership by one Buying Case and no cascade that can alter the verified dealer offer.

## Implementation notes

- Added a Buyer-selected outside financing form and side-by-side saved comparison,
  including explicit purchase-assumption alignment and no delta for incomplete or
  non-comparable loans.
- Reused the deterministic financing calculator for full-term payments, interest,
  fees, Total Transaction Cost, APR-only counterfactuals and financed add-on effects.
- Added immutable outside Finance Offers and comparison snapshots through JPA,
  with case locking, same-case foreign keys, idempotency and historical retrieval.
- Kept dealer offers and existing reports unchanged; comparison snapshots retain
  their original dealer financing evidence and are labeled historical.
- Documented supported assumptions, formulas and endpoints in
  [the financing policy](../financing-policy.md#one-outside-financing-comparison-v1).
- Validation: full backend suite (72 tests), frontend suite (7 tests), production
  frontend build, and whitespace checks. HTTP acceptance includes restart replay,
  revisions, access, confirmation, stale evidence and unchanged dealer offers.
