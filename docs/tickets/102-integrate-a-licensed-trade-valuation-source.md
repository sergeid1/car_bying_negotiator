# 102 — Integrate a licensed trade valuation source

**What to build:** A production `TradeValuationAdapter` backed by a licensed trade-in valuation provider
whose terms allow consumer display of the range, caching for the life of a case, and attribution. Spec
TRADE-03, prerequisite 11 in Section 18.

**Blocked by:** 101 — Define the trade valuation adapter with a deterministic fake; the operator choosing a
provider and executing its terms.

**Status:** blocked on the operator — provider selection and terms. Candidates to evaluate include
MarketCheck (already under evaluation in ticket 86, if its terms cover trade values), Black Book and
J.D. Power; record the comparison in `docs/provider-inventory.md`.

- [ ] Executed terms cover consumer display, caching, attribution, retention, deletion and per-call price.
- [ ] The adapter passes ticket 101's conformance suite against recorded licensed fixtures.
- [ ] Per-call cost is priced against the pilot budget alongside market calls (ticket 41).
- [ ] The report labels the range with the provider's name, retrieval date and condition assumption.
