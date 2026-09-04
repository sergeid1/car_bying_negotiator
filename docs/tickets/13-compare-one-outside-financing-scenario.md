# 13 — Compare one outside Financing Scenario

**What to build:** A Buyer can enter one outside financing offer and compare its full-term economics with dealer financing under aligned purchase assumptions.

**Blocked by:** 12 — Analyze dealer financing and financed add-ons.

**Status:** ready-for-agent

- [ ] The Buyer can enter principal, APR, term, payment timing, and applicable fees for one outside offer.
- [ ] The comparison identifies and aligns differing purchase assumptions before presenting a cost delta.
- [ ] Results show payment, interest, scheduled payments, fees, and Total Transaction Cost for each supported scenario.
- [ ] The incremental full-term effect of APR and financed add-ons is visible and reproducible.
- [ ] Incomplete or non-comparable scenarios explain what is missing and do not fabricate a winner.
- [ ] The outside offer remains Buyer-selected financing and is never represented as a dealer discount.
- [ ] The Buyer-entered outside Finance Offer and immutable comparison result are stored through JPA repositories with explicit ownership by one Buying Case and no cascade that can alter the verified dealer offer.
