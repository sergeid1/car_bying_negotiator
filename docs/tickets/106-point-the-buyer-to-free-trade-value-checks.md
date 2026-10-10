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

**Status:** open — agent-ready once 103 is merged.

- [ ] When the Buyer has a trade-in (`TradeVehicle.tsx`, and the trade question in `AtTheDealer.tsx`), the
      section shows "Check what your car is worth" with links to the Carfax value lookup and the CarMax
      instant offer, each with one line on what it gives (an estimate, or a written offer valid for a set
      time) and roughly how long it takes. Links open in a new tab and carry no referral or tracking code.
- [ ] The VIN and mileage the Buyer already entered are shown next to the links with a copy button, so the
      Buyer does not retype them on the other site.
- [ ] The Buyer can record an estimate: source (Carfax or other, named), a single value or a low–high range,
      and the date checked. It is stored with the Trade Vehicle, carried into every case in the journey like
      written offers, and never shown as an offer the Buyer can take to the dealer.
- [ ] A CarMax (or Carvana, or any dealer) offer is entered through "Add a written offer"; the copy steers it
      there rather than into the estimate field.
- [ ] Ticket 103's valuation comparison uses the Buyer-entered estimate when no licensed range exists,
      labelled "your Carfax estimate from <date>"; a licensed range from 102 takes precedence when present.
      An estimate older than 30 days is called old, not ignored.
- [ ] Copy says these are outside estimates and offers the Buyer gathered, makes no claim the platform
      checked or endorses them, and passes `ClaimVocabulary` and `PilotCopyClaimsTest`.
- [ ] Any migration number is agreed with the coordinator before work starts (V52 was reserved by 101 and
      left unused).
- [ ] The new panel is covered in `Accessibility.test.tsx` and fits the 360 px browser gate.
