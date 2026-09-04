# 10 — Approve immutable price boundaries

**What to build:** The Buyer can review distinct selling-price and OTD boundaries, apply personal limits, and explicitly approve an immutable baseline for later Dealer Offer comparisons.

**Blocked by:** 08 — Calculate and reconcile cash deal economics; 09 — Produce a transparent Market Assessment.

**Status:** ready-for-agent

- [ ] Versioned policy produces opening, target, and policy maximum from explicit Market Assessment and deal inputs.
- [ ] Selling-price and OTD boundaries are labeled separately and are converted only when jurisdiction inputs support the conversion.
- [ ] The walk-away ceiling is the lower of the policy maximum and the Buyer's applicable approved maximum.
- [ ] An apparently incompatible Buyer maximum produces a warning without manufacturing a different market value.
- [ ] Savings estimates identify their baseline and uncertainty and never guarantee an outcome.
- [ ] Only an explicit Buyer approval creates the immutable Approved Baseline and records its Deal Terms, limits, policies, and audit event.
- [ ] Later model output, provider refresh, or generated explanation cannot raise or replace an approved maximum.
- [ ] Approved Baseline versions and their audit events are inserted through JPA repositories as append-only records; no cascade or dirty-checking path can mutate an approved version.
