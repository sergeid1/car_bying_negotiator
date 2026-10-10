# 101 — Define the trade valuation adapter with a deterministic fake

**What to build:** A replaceable adapter that returns a trade-in value range for a Trade Vehicle from its
VIN, mileage, condition and ZIP, with a deterministic fake and a conformance suite every adapter must
pass, following tickets 38 and 53. Each result carries source, licence metadata, retrieval time and the
condition it assumes. A licensed provider is ticket 102. Spec TRADE-03, 11.2.

**Blocked by:** 100 — Record the trade-in and what is owed on it.

**Status:** complete — `com.dealanalyzer.trade.valuation`, no migration (V52 unused), as of 2026-10-09.
`TradeValuationAdapter.value(TradeVehicle, zip)` returns a low/typical/high range in minor units or a stated
unavailability, each result carrying provider, licence, retrieval and observation time and the assumed condition.
`DeterministicFakeTradeValuationAdapter` covers normal, wide, unknown trim, high mileage, stale, unavailable,
timed-out and rate-limited fixtures and passes the reusable `TradeValuationAdapterConformance` suite;
`TradeValuationProductionGuard` refuses synthetic adapters under `pilot`, so pilot startup needs ticket 102.
Consumers call `TradeValuationService.value(caseId, TradeVehicle.from(trade), zip)`, which meters every attempt
through `ProviderMeter` (operation `TRADE_VALUATION`; no VIN, ZIP or payoff is stored). Open: the 45-day freshness
limit follows market evidence and needs checking against ticket 102's provider terms; valuation availability
reuses the `inventory_availability` usage column.

- [x] `TradeValuationAdapter.value(tradeVehicle, zip)` returns a low/typical/high trade-in range in minor
      units, or a stated unavailability with a reason. Provider response objects do not leak past it.
- [x] The deterministic fake returns labelled-synthetic ranges covering a normal value, a wide range, an
      unknown trim, high mileage, a stale observation and unavailability.
- [x] The conformance suite covers normalization, staleness, missing fields, timeouts, rate limits and
      stated unavailability; the fake passes it.
- [x] The fake cannot be selected under the `pilot` profile, enforced by a test.
- [x] Every valuation call is metered with provider, licence, result, latency and cost.
- [x] No value is estimated by the platform when the adapter is unavailable, and there is no scraping or
      URL-fetch operation.
