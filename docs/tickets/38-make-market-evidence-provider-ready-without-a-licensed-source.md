# 38 — Make market and listing evidence provider-ready without a licensed source

**What to build:** The parts of ticket 27 that need no licensed provider. Development-only market and
listing adapters can never serve production, every adapter must pass one conformance suite, and the
report states data freshness, uncertainty, thin comparables, and provider unavailability honestly.
Ticket 27 keeps the licensed-source integration, executed terms, coverage measurement, and per-call
cost against the pilot budget.

**Blocked by:** 04 — Establish Vehicle and listing evidence; 09 — Produce a transparent Market Assessment.

**Status:** complete — development market and listing adapters refuse to start under the `pilot`
profile or `EVIDENCE_DEPLOYMENT=production`, every adapter runs one conformance suite, and the report
states retrieval date, observation window, uncertainty, thin comparables and unavailability in Buyer
wording at 360 px, as of 2026-10-02. No licensed source is integrated; ticket 27 keeps that work.

- [x] `DeterministicFakeMarketDataProvider` and `DeterministicListingEvidenceProvider` cannot be selected
      under production configuration; startup fails closed naming the adapter, and a test fails if either
      can be selected in production.
- [x] A reusable conformance suite defines the contract every `MarketDataProvider` and
      `ListingEvidenceProvider` implementation must pass — snapshot metadata, currency and minor-unit
      amounts, observation timestamps, unavailability without substituted values, and no vendor types
      crossing into the domain — and the deterministic adapters pass it.
- [x] The report displays the market data's retrieval time and observation window, and the assessment's
      uncertainty, in Buyer-facing wording at 360 px.
- [x] When comparables are too thin to support a conclusion, the report says so and draws no market
      rating from them.
- [x] Provider unavailability, schema drift, missing snapshot metadata, and stale snapshots produce a
      stated unavailability in the report; synthetic or cached-stale values are never substituted silently,
      and tests prove each case.
- [x] Market and listing provider calls are recorded through the existing provider metering with
      latency, so ticket 27 can price them against the pilot budget.
- [x] The provider inventory records that no licensed source is integrated and which guard prevents
      development adapters from reaching production.

## Implementation notes — 2026-10-02

- `EvidenceProviderProductionGuard` fails startup naming any `DevelopmentEvidenceAdapter` when a
  `production`, `prod` or `pilot` profile is active or `evidence.deployment=production`; a production
  profile cannot be overridden back to development.
- `EvidenceAdapterConformance` is the reusable suite; `DeterministicEvidenceConformanceTest` runs it
  against both development adapters. `MarketProviderContract` and `ListingProviderContract` validate
  responses at the boundary.
- Provider outage, schema drift, missing snapshot metadata and snapshots older than 45 days become a
  stated unavailability with no substituted price; the age rule also applies when a new report reuses a
  cached assessment. Listing failures keep the confirmed vehicle facts (`UNAVAILABLE_FALLBACK`).
- `MarketReportEvidence` freezes Buyer paragraphs into `analysis.marketEvidence` with plain UTC
  calendar dates; screen, HTML and PDF render the same saved text. Older reports say the details were
  not recorded. Thin comparables draw no market rating.
- Market and listing calls keep their `ProviderMeter` operations, with contract failures classified
  and start/finish times persisted for latency.
- The development market fixture derives observation times from an injected `Clock` (default
  `Clock.systemUTC()`), so it no longer expires on a fixed calendar date and is repeatable in tests.
- No migration; V26 was not used. See `docs/provider-inventory.md`.
