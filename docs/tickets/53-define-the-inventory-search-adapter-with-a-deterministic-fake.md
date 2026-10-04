# 53 — Define the inventory search adapter with a deterministic fake

**What to build:** The Shopping module can search dealer inventory for a Shopping Brief through a replaceable adapter, with a deterministic fake and a conformance suite every adapter must pass. Search returns normalized listings with dealer public identity, VIN, facts, advertised price, source, licence metadata, and retrieval time. A licensed provider is ticket 54. Spec SHOP-03.

**Blocked by:** 51 — Describe the car in a Shopping Brief; 38 — Make market evidence provider-ready without a licensed source.

**Status:** complete — inventory search adapter in `com.dealanalyzer.shopping.inventory` with journey-scoped provider
metering (migration V37), as of 2026-10-04. `InventorySearchAdapter.search(criteria, radiusMiles)` takes structured
Shopping Brief criteria and returns normalized `InventoryListing` records with dealer public identity, VIN, facts,
advertised price, source, licence metadata, and retrieval time; it has no URL-fetch operation and provider types stay
inside adapters. `DeterministicFakeInventorySearchAdapter` returns labelled-synthetic listings covering a match,
near-misses, a duplicate, a stale observation, a missing VIN, and unknown facts. The abstract
`InventorySearchAdapterConformance` suite covers normalization, duplicate removal, staleness, missing fields,
timeouts, rate limits, and stated unavailability, and the fake passes it. `InventorySearchProductionGuard` refuses
synthetic adapters under the pilot profile, enforced by a test. `InventorySearchService.search(accountId, journeyId)`
meters each run as `INVENTORY_SEARCH`, recording provider, licence, query, result count, retrieval time, latency, and
cost; `provider_usage.case_id` is now nullable with a scope check, leaving case-scoped rows unchanged. Search history
holds the postal code, so it is exported with the account and deleted with the journey and the account. Open: no
licensed source exists yet (ticket 54); there is no endpoint or UI until ticket 55.

- [x] The adapter interface accepts brief criteria and a radius and returns normalized listings; provider response objects do not leak past it.
- [x] A deterministic fake returns a varied, labelled-synthetic inventory that exercises matches, near-misses, duplicates, stale listings, and missing VINs.
- [x] A conformance suite covers normalization, duplicate removal, stale-listing handling, missing fields, timeouts, rate limits, and stated unavailability; the fake passes it.
- [x] The fake cannot be selected under the `pilot` profile, enforced by a test, like ticket 38's market adapters.
- [x] Every search run records provider, licence metadata, query, result count, retrieval time, latency, and cost through provider metering.
- [x] No listing is fetched by scraping; the adapter interface has no URL-fetch operation.
