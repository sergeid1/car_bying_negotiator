# 101 — Define the trade valuation adapter with a deterministic fake

**What to build:** A replaceable adapter that returns a trade-in value range for a Trade Vehicle from its
VIN, mileage, condition and ZIP, with a deterministic fake and a conformance suite every adapter must
pass, following tickets 38 and 53. Each result carries source, licence metadata, retrieval time and the
condition it assumes. A licensed provider is ticket 102. Spec TRADE-03, 11.2.

**Blocked by:** 100 — Record the trade-in and what is owed on it.

**Status:** open — agent-ready (100 complete). Reserve migration V52 only if metering needs a new scope (V50 and V51 went to tickets 100 and 97).

- [ ] `TradeValuationAdapter.value(tradeVehicle, zip)` returns a low/typical/high trade-in range in minor
      units, or a stated unavailability with a reason. Provider response objects do not leak past it.
- [ ] The deterministic fake returns labelled-synthetic ranges covering a normal value, a wide range, an
      unknown trim, high mileage, a stale observation and unavailability.
- [ ] The conformance suite covers normalization, staleness, missing fields, timeouts, rate limits and
      stated unavailability; the fake passes it.
- [ ] The fake cannot be selected under the `pilot` profile, enforced by a test.
- [ ] Every valuation call is metered with provider, licence, result, latency and cost.
- [ ] No value is estimated by the platform when the adapter is unavailable, and there is no scraping or
      URL-fetch operation.
