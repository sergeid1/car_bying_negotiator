# 125 — Define the insurance quote adapter with a deterministic fake

**What to build:** A replaceable adapter that sends one Buyer-approved insurance application to the
insurers the Buyer selected and returns their quotes, with a deterministic fake and a conformance suite
every adapter must pass, following tickets 38, 53 and 101. The licensed partner is ticket 127. Spec 2.3
sections 5.10, 8.12 (INS-01 to INS-04) and 11.2.

**Blocked by:** none.

**Status:** open — filed on 2026-10-14.

## Scope

- `InsuranceQuoteAdapter.request(application, selectedProviders)` returns, per provider, a quote or a
  stated outcome (declined, needs more information, unavailable, timed out). A quote carries premium and
  term (6 or 12 months), coverages with limits and deductibles, the coverage the insurer actually priced,
  expiry, the provider's link for the Buyer to continue, and source and retrieval metadata.
- Coverage normalization: a quote is "same coverage" only when every limit and deductible matches the
  Buyer's choice; anything else is labelled with its differences and not ranked as cheaper (INS-03).
- Yearly difference against the Buyer's current premium for the same coverage, computed in code, never
  worded as savings (INS-04).
- A `ProviderMeter.Operation.INSURANCE_QUOTE` so partner calls are metered like other providers.
- The fake returns deterministic quotes, declines and timeouts from the application, and is the default.
  Main tests never call a live partner.
