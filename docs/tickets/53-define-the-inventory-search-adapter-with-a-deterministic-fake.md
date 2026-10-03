# 53 — Define the inventory search adapter with a deterministic fake

**What to build:** The Shopping module can search dealer inventory for a Shopping Brief through a replaceable adapter, with a deterministic fake and a conformance suite every adapter must pass. Search returns normalized listings with dealer public identity, VIN, facts, advertised price, source, licence metadata, and retrieval time. A licensed provider is ticket 54. Spec SHOP-03.

**Blocked by:** 51 — Describe the car in a Shopping Brief; 38 — Make market evidence provider-ready without a licensed source.

**Status:** blocked on 51 — filed on 2026-10-03 from specification 2.0.

- [ ] The adapter interface accepts brief criteria and a radius and returns normalized listings; provider response objects do not leak past it.
- [ ] A deterministic fake returns a varied, labelled-synthetic inventory that exercises matches, near-misses, duplicates, stale listings, and missing VINs.
- [ ] A conformance suite covers normalization, duplicate removal, stale-listing handling, missing fields, timeouts, rate limits, and stated unavailability; the fake passes it.
- [ ] The fake cannot be selected under the `pilot` profile, enforced by a test, like ticket 38's market adapters.
- [ ] Every search run records provider, licence metadata, query, result count, retrieval time, latency, and cost through provider metering.
- [ ] No listing is fetched by scraping; the adapter interface has no URL-fetch operation.
