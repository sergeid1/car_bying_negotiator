# 106 — Point the Buyer to free trade value checks

**What to build:** A Buyer with a trade-in learns what it is worth before reaching the dealership, without the
platform licensing a valuation source. When the Buyer says they have a trade-in, the trade section links
to free checks the Buyer runs themselves: Carfax's value lookup (quick, VIN or plate, an estimate) and
CarMax's online instant offer (longer, but a written offer with an expiry). The Buyer types back what they
found. A CarMax offer goes through the existing written-offer path; a Carfax value is recorded as a
Buyer-entered estimate naming where and when it was looked up, and stands in for the valuation range in
ticket 103's comparison until ticket 102 brings a licensed source. The platform never queries these sites
itself. Operator request on 2026-10-10.

**Blocked by:** 100 — Record the trade-in and what is owed on it; 103 — Check the trade allowance and payoff
at the desk (the estimate feeds its valuation comparison; start after 103 merges so the interface is fixed).

**Status:** complete — migration V52, as of 2026-10-10. A recorded trade-in shows "Check what your car is worth"
with plain links to Carfax's value lookup and CarMax's instant offer (new tab, no referral or tracking code; the
platform never queries either site) and the VIN and mileage to copy; the same links appear at the trade question
in "I'm at the dealer". The Buyer records estimates (`trade_estimates`: source, single value or range, date
checked) through `POST /api/v1/cases/{caseId}/trade/estimates`; they belong to the Trade Vehicle, carry into every
case in the journey, are exported and deleted with it, and are never shown or compared as offers. CarMax, Carvana
and dealer offers are steered to "Add a written offer". `trade-desk-check.v2` uses the most recently checked
estimate in place of the valuation range when the adapter gives no licensed range (unavailable, or the synthetic
development range), labelled "your <source> estimate from <date>" and called old after 30 days; a licensed range
from ticket 102 takes precedence. Open: the operator confirms the two public URLs and the time estimates ("about
2 minutes", "10 to 15 minutes"); the valuation provider is still called when an estimate stands in.

- [x] When the Buyer has a trade-in (`TradeVehicle.tsx`, and the trade question in `AtTheDealer.tsx`), the
      section shows "Check what your car is worth" with links to the Carfax value lookup and the CarMax
      instant offer, each with one line on what it gives (an estimate, or a written offer valid for a set
      time) and roughly how long it takes. Links open in a new tab and carry no referral or tracking code.
- [x] The VIN and mileage the Buyer already entered are shown next to the links with a copy button, so the
      Buyer does not retype them on the other site.
- [x] The Buyer can record an estimate: source (Carfax or other, named), a single value or a low–high range,
      and the date checked. It is stored with the Trade Vehicle, carried into every case in the journey like
      written offers, and never shown as an offer the Buyer can take to the dealer.
- [x] A CarMax (or Carvana, or any dealer) offer is entered through "Add a written offer"; the copy steers it
      there rather than into the estimate field.
- [x] Ticket 103's valuation comparison uses the Buyer-entered estimate when no licensed range exists,
      labelled "your Carfax estimate from <date>"; a licensed range from 102 takes precedence when present.
      An estimate older than 30 days is called old, not ignored.
- [x] Copy says these are outside estimates and offers the Buyer gathered, makes no claim the platform
      checked or endorses them, and passes `ClaimVocabulary` and `PilotCopyClaimsTest`.
- [x] Any migration number is agreed with the coordinator before work starts (V52 was reserved by 101 and
      left unused).
- [x] The new panel is covered in `Accessibility.test.tsx` and fits the 360 px browser gate.
