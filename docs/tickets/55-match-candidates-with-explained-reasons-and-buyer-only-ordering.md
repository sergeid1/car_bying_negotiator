# 55 — Match candidates with explained reasons and Buyer-only ordering

**What to build:** Search results become Candidate Vehicles, each showing which brief criteria and reference attributes it matches and misses, ordered only by the Buyer's criteria and evidence. A test proves no dealer or commercial field can change ordering or wording (credo 3). Spec SHOP-04, SHOP-05, acceptance scenarios 16 and 17.

**Blocked by:** 52 — Add Reference Vehicles by VIN; 53 — Define the inventory search adapter with a deterministic fake.

**Status:** ready-for-agent (52 and 53 complete) — filed on 2026-10-03 from specification 2.0.

- [ ] Matching is deterministic and versioned; each candidate lists matched and missed criteria in plain words, with no hidden score.
- [ ] Must-have criteria exclude non-matching listings; nice-to-have criteria and relevant reference attributes only affect ordering.
- [ ] Ordering uses only Buyer criteria and listing evidence. A property test shows that changing dealer identity, dealer name, or adding any commercial field leaves ordering and wording unchanged.
- [ ] The matching module cannot read any commercial, channel, or payment data, enforced by an isolation test like `AcquisitionChannelIsolationTest`.
- [ ] Each candidate shows its Listing Snapshot source and retrieval time; stale listings are labelled.
- [ ] The results list works at 360 px in both themes and is registered in `Accessibility.test.tsx`.
