# 56 — Shortlist candidates and open a Buying Case from one

**What to build:** The Buyer sees a listed-price market check on each candidate, keeps candidates on a shortlist, and picks one or more. Each pick opens a Buying Case seeded with the Vehicle and Listing Snapshot, so the Phase 1 workflow and the Buyer Agent start from it. Spec SHOP-06, SHOP-07.

**Blocked by:** 55 — Match candidates with explained reasons and Buyer-only ordering; 09 — Produce a transparent Market Assessment.

**Status:** complete — journey shortlist and case opening in `com.dealanalyzer.journey` (migration V40), as of
2026-10-04. Each candidate carries a market check from the existing Market Assessment policy
(`MarketAssessmentService.advertised`), labelled as an advertised price, not a quote, with confidence and freshness.
`journey_candidates` stores captured candidate and listing evidence; the shortlist (`GET/PUT
/api/v1/journeys/{id}/shortlist`) persists per journey in the Buyer's order. `POST
/api/v1/journeys/{id}/candidates/{candidateId}/case` opens a Buying Case through the standard creation path after the
Buyer confirms location and purchase method, with the Vehicle and Listing Snapshot as evidence; missing listing facts
stay unknown. `journey_candidate_cases` links each case to its journey and holds the case link encrypted, excluded
from exports. `LinkedJourneyAgreement` implements ticket 63's seam: agreement moves the journey to `AGREED` and other
open negotiations can be paused or stopped (`PAUSED` added in migration V39). Deletion and export cover the new
tables. Open: reopened linked cases keep the existing case-access expiry.

- [x] Each candidate shows a market check from the existing Market Assessment module, labelled as an advertised price, not a quote, with its confidence and freshness.
- [x] The shortlist persists per journey; adding, removing, and reordering are immediate and survive reload.
- [x] Picking a candidate opens a Buying Case under the journey with the Vehicle and Listing Snapshot as evidence; the Buyer still confirms location and purchase method.
- [x] Several Buying Cases can be open in one journey, one per picked candidate.
- [x] A Buying Case opened this way behaves exactly like one created directly; existing case tests pass unchanged.
