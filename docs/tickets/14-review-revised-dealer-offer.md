# 14 — Review a revised Dealer Offer

**What to build:** A Buyer can submit a second Dealer document and receive a new immutable review comparing it line by line with both the Approved Baseline and the immediately preceding offer.

**Blocked by:** 07 — Verify Deal Terms and create an immutable Dealer Offer; 10 — Approve immutable price boundaries; 11 — Generate the core Deal Analysis Report; 12 — Analyze dealer financing and financed add-ons.

**Status:** Complete

- [x] A revised document follows the same secure upload, extraction, evidence, verification, and correction workflow as the initial offer.
- [x] The resulting Deal Review records its baseline version, current offer version, previous offer version, and tolerance policy.
- [x] Every changed, missing, uncertain, and newly added term is shown, including differences below materiality.
- [x] Discrepancies record expected and observed values, meaningful delta, severity, reason, evidence, confidence, and Buyer action.
- [x] Hidden-add-on fixtures show the complete pre-tax delta and separately calculated tax effect.
- [x] An offer above an Approved Baseline limit creates a material violation and cannot raise the limit.
- [x] The original Dealer Offer, Approved Baseline, Deal Review, and delivered report remain immutable.
- [x] Revised Dealer Offer and Deal Review versions are appended through JPA repositories with explicit references to baseline, previous, and current versions; ORM relationships cannot cascade updates into any referenced immutable record.


## Implementation notes

- Reuses secure document intake, extraction, evidence, correction and verification
  for each revision; verified offers append under the case lock.
- Analysis atomically appends a Deal Review and report. Reviews retain baseline,
  previous and current version references, frozen evidence and tolerance policy,
  with JPA immutability and same-case foreign keys without update cascades.
- Displays baseline and previous-offer differences, including informational,
  missing, added and uncertain terms, with evidence and Buyer actions.
- Checks both quoted and calculated amounts against approved ceilings. Hidden
  add-on fixtures separately verify complete pre-tax differences and tax effects.
- Documents endpoints and comparison rules in the
  [revised-offer policy](../revised-offer-policy.md).
- Validation covers HTTP upload/correction, retries, third-offer ancestry,
  unchanged historical records, authorization and restart replay, plus policy
  fixtures and the report component.
