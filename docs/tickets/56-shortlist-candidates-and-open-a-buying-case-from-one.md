# 56 — Shortlist candidates and open a Buying Case from one

**What to build:** The Buyer sees a listed-price market check on each candidate, keeps candidates on a shortlist, and picks one or more. Each pick opens a Buying Case seeded with the Vehicle and Listing Snapshot, so the Phase 1 workflow and the Buyer Agent start from it. Spec SHOP-06, SHOP-07.

**Blocked by:** 55 — Match candidates with explained reasons and Buyer-only ordering; 09 — Produce a transparent Market Assessment.

**Status:** blocked on 55 — filed on 2026-10-03 from specification 2.0.

- [ ] Each candidate shows a market check from the existing Market Assessment module, labelled as an advertised price, not a quote, with its confidence and freshness.
- [ ] The shortlist persists per journey; adding, removing, and reordering are immediate and survive reload.
- [ ] Picking a candidate opens a Buying Case under the journey with the Vehicle and Listing Snapshot as evidence; the Buyer still confirms location and purchase method.
- [ ] Several Buying Cases can be open in one journey, one per picked candidate.
- [ ] A Buying Case opened this way behaves exactly like one created directly; existing case tests pass unchanged.
