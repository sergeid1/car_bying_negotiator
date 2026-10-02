# 38 — Make market and listing evidence provider-ready without a licensed source

**What to build:** The parts of ticket 27 that need no licensed provider. Development-only market and
listing adapters can never serve production, every adapter must pass one conformance suite, and the
report states data freshness, uncertainty, thin comparables, and provider unavailability honestly.
Ticket 27 keeps the licensed-source integration, executed terms, coverage measurement, and per-call
cost against the pilot budget.

**Blocked by:** 04 — Establish Vehicle and listing evidence; 09 — Produce a transparent Market Assessment.

**Status:** ready-for-agent

- [ ] `DeterministicFakeMarketDataProvider` and `DeterministicListingEvidenceProvider` cannot be selected
      under production configuration; startup fails closed naming the adapter, and a test fails if either
      can be selected in production.
- [ ] A reusable conformance suite defines the contract every `MarketDataProvider` and
      `ListingEvidenceProvider` implementation must pass — snapshot metadata, currency and minor-unit
      amounts, observation timestamps, unavailability without substituted values, and no vendor types
      crossing into the domain — and the deterministic adapters pass it.
- [ ] The report displays the market data's retrieval time and observation window, and the assessment's
      uncertainty, in Buyer-facing wording at 360 px.
- [ ] When comparables are too thin to support a conclusion, the report says so and draws no market
      rating from them.
- [ ] Provider unavailability, schema drift, missing snapshot metadata, and stale snapshots produce a
      stated unavailability in the report; synthetic or cached-stale values are never substituted silently,
      and tests prove each case.
- [ ] Market and listing provider calls are recorded through the existing provider metering with
      latency, so ticket 27 can price them against the pilot budget.
- [ ] The provider inventory records that no licensed source is integrated and which guard prevents
      development adapters from reaching production.
