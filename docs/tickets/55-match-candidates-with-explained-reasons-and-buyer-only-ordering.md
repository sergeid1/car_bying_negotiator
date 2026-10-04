# 55 — Match candidates with explained reasons and Buyer-only ordering

**What to build:** Search results become Candidate Vehicles, each showing which brief criteria and reference attributes it matches and misses, ordered only by the Buyer's criteria and evidence. A test proves no dealer or commercial field can change ordering or wording (credo 3). Spec SHOP-04, SHOP-05, acceptance scenarios 16 and 17.

**Blocked by:** 52 — Add Reference Vehicles by VIN; 53 — Define the inventory search adapter with a deterministic fake.

**Status:** complete — Candidate Vehicle matching in `com.dealanalyzer.shopping.matching`, as of 2026-10-04.
`POST /api/v1/journeys/{id}/candidates` searches inventory for the current Shopping Brief and returns Candidate
Vehicles computed on each request (no persistence, no migration). `CandidateMatcher` (version `candidate-matching.v1`)
lists matched, missed, and unconfirmed criteria in plain words and states its ordering rule; there is no score.
A listing that states a fact contradicting a must-have is left out and shown with its misses; a must-have the listing
does not state keeps the candidate but ranks it lower and marks it unconfirmed. Nice-to-have features and relevant
Reference Vehicle attributes only affect order. Matching reads listings only through `ListingEvidence`, which omits
the dealer and licence; `CandidateRankingIntegrityTest` shows that changing dealer identity or name, or adding any of
14 commercial fields, leaves ordering and wording unchanged, and `CandidateMatchingIsolationTest` limits the package's
imports. Each candidate shows its Listing Snapshot source and retrieval time, with stale and undated listings
labelled. The results list in `CandidateVehicles.tsx` is in the axe sweep and the 360 px gate in both themes.
Open: shortlisting and persistence are ticket 56.

- [x] Matching is deterministic and versioned; each candidate lists matched and missed criteria in plain words, with no hidden score.
- [x] Must-have criteria exclude non-matching listings; nice-to-have criteria and relevant reference attributes only affect ordering.
- [x] Ordering uses only Buyer criteria and listing evidence. A property test shows that changing dealer identity, dealer name, or adding any commercial field leaves ordering and wording unchanged.
- [x] The matching module cannot read any commercial, channel, or payment data, enforced by an isolation test like `AcquisitionChannelIsolationTest`.
- [x] Each candidate shows its Listing Snapshot source and retrieval time; stale listings are labelled.
- [x] The results list works at 360 px in both themes and is registered in `Accessibility.test.tsx`.
