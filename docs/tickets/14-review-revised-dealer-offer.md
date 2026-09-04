# 14 — Review a revised Dealer Offer

**What to build:** A Buyer can submit a second Dealer document and receive a new immutable review comparing it line by line with both the Approved Baseline and the immediately preceding offer.

**Blocked by:** 07 — Verify Deal Terms and create an immutable Dealer Offer; 10 — Approve immutable price boundaries; 11 — Generate the core Deal Analysis Report; 12 — Analyze dealer financing and financed add-ons.

**Status:** ready-for-agent

- [ ] A revised document follows the same secure upload, extraction, evidence, verification, and correction workflow as the initial offer.
- [ ] The resulting Deal Review records its baseline version, current offer version, previous offer version, and tolerance policy.
- [ ] Every changed, missing, uncertain, and newly added term is shown, including differences below materiality.
- [ ] Discrepancies record expected and observed values, meaningful delta, severity, reason, evidence, confidence, and Buyer action.
- [ ] Hidden-add-on fixtures show the complete pre-tax delta and separately calculated tax effect.
- [ ] An offer above an Approved Baseline limit creates a material violation and cannot raise the limit.
- [ ] The original Dealer Offer, Approved Baseline, Deal Review, and delivered report remain immutable.
- [ ] Revised Dealer Offer and Deal Review versions are appended through JPA repositories with explicit references to baseline, previous, and current versions; ORM relationships cannot cascade updates into any referenced immutable record.
