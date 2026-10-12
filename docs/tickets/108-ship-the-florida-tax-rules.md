# 108 — Ship the Florida tax rules

**What to build:** A sourced Florida jurisdiction pack, so the tax figures, the trade-in tax credit (ticket 103)
and the cash economics are right for a Florida Buyer. Florida charges 6% state sales tax on the price after the
trade-in, plus the discretionary sales surtax of the county where the car will be registered, and that surtax
applies only to the first $5,000 of the price. On a $35,000 car in a 1% county the surtax is $50, not $350. The
current pack format holds one flat `tax_rate`, so it cannot express this, and the only shipped pack is the
synthetic `US-NC.cash.v1`, so Florida Buyers cannot open a case at all today. Research: `docs/research/state-vehicle-tax-and-fee-rules.md`. Operator request on
2026-10-10; pilot state chosen on 2026-10-05.

**Blocked by:** none in the dependency graph. It touches tax calculation in the cash economics, the trade check
(103, 106) and case intake, so do not run it in parallel with another ticket that changes those.

**Status:** complete — migrations V53 and V54, as of 2026-10-12. `US-FL.cash.2026.v1` ships effective 2026-01-01 as 67 county scopes from DR-15DSS 2026 and Florida Statutes 212.05, 212.054 and 212.09, checked 2026-10-12 and due for review 2027-01-01. The registration county comes from the Census 2020 ZIP-to-county file, or the Buyer chooses it when a ZIP code spans counties. The state tax and the capped county surtax both deduct the trade allowance; cash economics, price boundaries and the outside trade-offer comparison share the component rules, and the operator evidence report rolls county scopes up to US-FL. Synthetic `US-NC.cash.v1` stays, labelled as a development fixture, to keep the existing deterministic fixtures. Pilot readiness approval stays with the operator. Out of scope: title, registration, plate and tire or battery fees; Florida purchases by non-residents; and vehicles bought out of state and registered in Florida.

**Sources to read in full, not from search excerpts** (the research marks Florida *Sourced*, but each value is
checked against the publication before it ships):
- Florida Statutes s. 212.05 (6% sales tax on motor vehicles), s. 212.054 (discretionary sales surtax: the
  $5,000 single-item cap, and the county of registration deciding the rate for a motor vehicle) and s. 212.09
  (trade-in allowance reducing the taxable price).
- Florida Department of Revenue form DR-15DSS, the discretionary sales surtax rate for each county, published
  for each calendar year, and GT-800019 (the motor vehicle sales tax guide linked in the research).

- [x] The pack format grows once to hold a tax made of components, each with a rate, a base and an optional
      cap on that base (Florida's surtax applies to the first $5,000 only). Existing packs keep working
      unchanged, and the format is documented so the other patterns in the research (tiers, total caps,
      floors, partial trade-in credit) can be added later without another redesign.
- [x] A `US-FL` pack ships, effective-dated, with the 6% state component and a county surtax component whose
      rate comes from a county table taken from DR-15DSS for the current year. Every value stores its
      citation (statute section or publication, year, URL) and the date it was checked, and the pack has a
      review date of the next 1 January, when county rates change.
- [x] The surtax rate is the one for the county where the car will be registered. Intake records the Buyer's
      Florida county, not their ZIP code, from the registration ZIP code the Buyer already enters. When a ZIP
      code spans more than one county, the Buyer chooses the county from those it touches, as intake does
      today for a ZIP code that crosses a state line. The county-mapping source and its licence are recorded,
      like the Census source in `V22__create_postal_jurisdictions.sql`.
- [x] The trade-in allowance reduces the base for both the state tax and the surtax, and ticket 103's
      trade-in tax credit uses the pack, so a Florida case no longer shows the credit as unavailable. The
      comparison with an outside offer shows the tax the Buyer would lose by selling the car elsewhere, using
      the surtax cap.
- [x] Tests cover, with labelled synthetic cases: a price under and over $5,000; a trade-in that brings the
      taxable price under $5,000; a 0% surtax county; a ZIP code spanning two counties; an Agreed Price and a
      fresh desk case; and the $35,000 example above.
- [x] Shipping the pack is what makes Florida a supported jurisdiction: `SupportedJurisdictions` supports a
      state only once it has a pack, and today only the synthetic `US-NC.cash.v1` exists, so a Florida Buyer
      is currently told the state is not supported. An acceptance test opens a Florida case end to end. A
      Buyer in a state with no pack still gets the existing "not supported yet" message, and the synthetic
      North Carolina pack is either kept and labelled as a development fixture or retired, with the choice
      recorded.
- [x] Out of scope, and said in the ticket's notes when it closes: title, registration, plate and tire or
      battery fees (a separate pass, as the research notes); a vehicle bought in Florida by a non-resident; and
      a vehicle bought out of state and registered in Florida.
- [x] `docs/pilot-readiness-record.md` updates the `jurisdiction-pack` prerequisite with the pack's version and
      sources. Its status changes only by the operator.
- [x] Buyer-facing copy names the county and the surtax cap where it explains the tax, and passes
      `ClaimVocabulary` and `PilotCopyClaimsTest`.
