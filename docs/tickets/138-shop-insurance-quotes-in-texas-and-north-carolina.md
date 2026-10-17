# 138 — Shop insurance quotes in Texas and North Carolina

**What to build:** The insurance journey from ticket 126 works for a Texas or North Carolina Buyer the way it does for a
Florida Buyer: the coverage guide names that state's required minimums, the application starts from them, and every
required limit can be chosen. Today the journey is written for Florida only. The pilot covers three states
(specification 2.4, 2026-10-16), and ticket 137 found these Florida-only places:

- `InsuranceShoppingService.java` coverage guide: "Florida requires at least $10,000" (property damage) and "Florida
  requires $10,000" (PIP), shown to every Buyer.
- `InsuranceQuotes.tsx`: `STARTING_COVERAGE` starts every application at Florida's PIP and property damage minimums,
  and the driver's licence state and the garaging state default to `FL`.
- `InsuranceQuotes.tsx` limit choices (`PER_PERSON`, `PER_ACCIDENT`) cannot express Texas's minimum: there is no
  $30,000 per person or $60,000 per accident.

**Blocked by:** none for the journey against the ticket 125 fake. Quotes from a real partner still wait on 123 and 127,
and the Phase 5 licensing route must be confirmed for each pilot state (specification §18 item 12).

**Status:** open — filed on 2026-10-16.

**State minimums to confirm against the official text before they ship** (from official pages via search on
2026-10-16; read each in full, as ticket 137 did for tax):

- **Florida:** $10,000 PIP and $10,000 property damage liability; bodily injury liability not required for most
  drivers. Keep today's values, now cited as data.
- **Texas:** 30/60/25 liability ($30,000 per person, $60,000 per accident bodily injury, $25,000 property damage).
  PIP and uninsured/underinsured motorist coverage are included or offered unless the Buyer rejects them in writing.
  Sources: [TDI auto insurance guide](https://www.tdi.texas.gov/pubs/consumer/cb020.html),
  [OPIC basics](https://www.opic.texas.gov/auto-insurance/basics/).
- **North Carolina:** 50/100/50 liability for policies written or renewed on or after 2025-07-01 (it was 30/60/25
  before); underinsured motorist coverage is included in every new or renewed policy from that date. Check whether
  uninsured motorist coverage is required and at what limits. Sources:
  [NCDOI 2025 changes](https://www.ncdoi.gov/changes-rating-automobile-insurance-policies-effective-july-1-2025),
  [NCDMV insurance requirements](https://www.ncdot.gov/dmv/title-registration/insurance-requirements/Pages/default.aspx),
  [G.S. 20-279.21](https://www.ncleg.gov/enactedlegislation/statutes/html/bysection/chapter_20/gs_20-279.21.html).
  NCDMV form MVR-106 Rev. 04/26 still prints 30/60/25; do not use it for this value.

## Scope

- State minimums become versioned, cited data (state, coverage type, limits, citation, checked date, review date),
  in the same spirit as the jurisdiction tax packs. No state-specific branching in code.
- The coverage guide names the required minimum for the Buyer's garaging state, or says the state has no minimum for
  that coverage. Copy for an optional coverage that a state includes unless rejected in writing (Texas PIP and
  uninsured motorist) says so plainly and leaves the choice with the Buyer.
- The application starts at the garaging state's minimums. The garaging state and the first driver's licence state
  default to the case's or journey's state when one is known, and otherwise to no selection, never to `FL`.
- The limit choices include every pilot state's minimums (add $30,000 per person and $60,000 per accident).
- Changing the garaging state after the Buyer has edited coverage does not silently overwrite their choices; offer to
  reset to the new state's minimums instead.
- When the chosen coverage is below the garaging state's minimum, say so before the application is sent. Whether to
  block or only warn is the operator's call; ship a warning and record the choice.
- A garaging state outside the pilot: the journey still works, without a minimums line, and says the state's
  minimums are not listed yet.
- Out of scope: the partner's own eligibility rules, state-specific consent text (ticket 134), rating factors,
  and states outside the pilot.

## Done when

- [ ] Florida, Texas and North Carolina minimums ship as cited data, each value checked against its official source,
      with a review date; no Buyer-facing copy hard-codes a state's minimum.
- [ ] A Texas, a North Carolina and a Florida application each start at that state's minimums, and the coverage guide
      names them. A Buyer outside the pilot sees no invented minimum.
- [ ] No form field defaults to `FL` unless the case or journey is in Florida.
- [ ] Coverage below the garaging state's minimum shows a warning before sending; tests cover each pilot state.
- [ ] The Texas $30,000 / $60,000 limits can be chosen, and the insurance pages still pass the axe sweep in
      `Accessibility.test.tsx` and the 360 px browser gate.
- [ ] Copy passes `ClaimVocabulary` and `PilotCopyClaimsTest`.
