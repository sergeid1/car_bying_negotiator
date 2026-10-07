# 86 — Evaluate MarketCheck and settle per-case storage terms

**What to build:** Evidence that MarketCheck can supply the market and listing evidence that tickets 27 and 54
need, and written terms that permit how the application stores it. MarketCheck was chosen on 2026-10-06
after CarGurus, Edmunds, Carfax and KBB proved to offer no self-serve API, and scraping consumer sites was
ruled out by their terms. Its free tier allows evaluation, but rule R2 of its terms of service (sections 5.4 and
7) forbids persistent caching: "Cache a response only long enough to render one user request. No database
mirrors, snapshots or historical archives." The application stores market evidence per case in
`market_provider_snapshots`, `comparable_snapshots`, `listing_snapshots` and `market_assessments`, freezes it
into an immutable report, and includes it in the Buyer's export. The standard API terms do not permit that,
so the storage model has to be agreed before integration.

**Blocked by:** none.

**Status:** blocked on the operator — filed on 2026-10-06. Every criterion needs an operator action (a
MarketCheck account, vendor correspondence, expert review of results), so this is not agent-ready.

- [ ] A MarketCheck evaluation account exists under the company email. Its API key is held only in the
      operator's environment, never in the repository, and is not configured in any deployment of the
      application.
- [ ] 30 to 50 Florida vehicles are queried with a standalone script outside the application, so no response
      reaches the application database. Raw responses are discarded after review; only aggregate measurements
      are kept, to stay within R2.
- [ ] Coverage is recorded with denominators: comparables returned per vehicle, the share within the plan's
      search radius, the share with VIN and price, and the count of vehicles too thin for a conclusion.
- [ ] The operator, as expert reviewer, records whether the price estimates and comparables are realistic for
      the sampled vehicles, with the disagreements listed.
- [ ] MarketCheck answers in writing: whether evidence may be retained per case for the case's lifetime and
      included in that Buyer's export; whether values the application derives (a range and its comparable
      count) are "MarketCheck data" under section 5.4; the price of its stored data feed product; consumer
      display and attribution requirements; how the plan's search radius is measured; and pilot pricing.
- [ ] One storage model is chosen and recorded: a per-case retention licence, derived values only, the data
      feed product, or another provider. If it is derived values only, a ticket is filed to stop persisting
      raw comparables and listings.
- [ ] `docs/provider-inventory.md` records MarketCheck's terms, retention limits and attribution, and tickets 27
      and 54 are updated to name the provider.
