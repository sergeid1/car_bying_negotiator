# 139 — Check government fees against each pilot state's schedule

**What to build:** A check of the title, registration, plate and other state fees on dealer paperwork against what
Florida, Texas and North Carolina actually charge. Today a `GOVERNMENT_CHARGE` line is summed and passed through
untaxed, and nothing says whether its amount is plausible. Padding the "tag and title" line, or charging a dealer
fee under a government-sounding label, is a common desk move, and the Buyer should be able to see and ask about it.
Ticket 137 left these fees out of scope ("title, registration, plate and inspection fees"), and
`docs/research/state-vehicle-tax-and-fee-rules.md` notes they need their own sourced pass. Filed on 2026-10-18 after
the pilot widened to three states (specification 2.4).

**Blocked by:** none in the dependency graph. It reads the same paperwork lines as ticket 140 (doc fees), so the
two must keep their data and checks in separate files (see Scope).

**Status:** complete — 2026-10-18, no migration. `government-fees.2026-10.v1` ships cited Florida, Texas and North
Carolina schedules; `GovernmentFeeCheck` and `FeeLabels` are integrated with ticket 140 and verified on the combined
result (`mvn clean verify`, `npm test`, `npm run build`, `npx playwright test`). Open points are listed in the
implementation notes: the Texas 24-month new-car registration amount is to be confirmed with TxDMV.

**Sources to read in full, not from search excerpts.** Every amount ships with its citation (statute or agency
publication, edition, URL) and the date it was checked:

- Florida: F.S. chapter 319 (title fees) and chapter 320 (registration, the initial registration fee for a new
  plate, plate and decal fees), F.S. 403.718 (new tire fee) and 403.7185 (lead-acid battery fee), and the FLHSMV fee
  schedule.
- Texas: Transportation Code chapters 501 and 502 and the TxDMV title and registration fee pages, including the
  county-set local fees and the inspection program fee collected at registration.
- North Carolina: G.S. 20-85 (title fees) and 20-87 (registration), the NCDMV fee schedule, and how the county
  vehicle property tax is collected with registration (G.S. 105-330).

## Scope

- **A code-owned fee schedule per state**, versioned (for example `government-fees.2026-10.v1`), effective-dated and
  with a review date, in its own resource file (for example `backend/src/main/resources/fees/government-fees.v1.json`),
  as ticket 138 does for insurance minimums. Use no migration unless the existing pack mechanism is clearly the
  better fit; if one is needed, it is the next free number after V61 and must be agreed with the coordinator first.
- Each fee is either a fixed amount or a range with the facts it depends on (vehicle weight or class, new plate or
  transfer, county, new or used, first-time Florida registration). Where the product does not know the fact, the
  check uses the range and says which fact decides it. Never guess a county or a weight.
- **The check:** for an itemized `GOVERNMENT_CHARGE` line, or a lump "tag, title and registration" line, compare the
  amount with the schedule for the case's state:
  - at or under the highest amount the schedule allows: no finding, or an informational note when the facts are
    unknown;
  - above that: a finding naming the line, the most the state's listed fees add up to, and a question the Buyer can
    ask the dealer ("Please itemize the state fees on this line"). The copy states the state's number and never says
    the dealer is breaking the law. A dealer may also pass through fees this schedule does not list.
  - a line labelled as a government fee that matches a dealer fee label (for example "electronic filing" or "doc
    fee"): a finding that it is a dealer charge, not a state fee.
- The finding appears where dealer-charge findings already appear: the Deal Review, the Desk Check and the "I'm at
  the dealer" result. Reuse the existing finding and report structures; do not add a new report section unless
  none fits.
- North Carolina's highway use tax stays with the tax pack (ticket 137). Do not count it twice as a government fee.
- Put the check in its own class and package (for example `economics.fees.GovernmentFeeCheck`). Ticket 140 adds a
  doc fee check next to it, so do not create a shared fee abstraction both tickets edit.
- Buyer-facing copy passes `ClaimVocabulary` and `PilotCopyClaimsTest`, and an explainer guide or report text says
  what each state's fees are for in plain words.

## Done when

- [ ] Florida, Texas and North Carolina fee schedules ship with citations, checked dates and review dates, and
      `docs/jurisdiction-tax-packs.md` (or a new `docs/government-fee-schedules.md`) lists each state's sources and a
      worked example.
- [ ] Tests cover, with labelled synthetic cases for each state: a line within the schedule; a line above the
      highest possible amount; a range that depends on an unknown fact; a lump tag-and-title line; a dealer fee
      labelled as a government fee; and a North Carolina case where the highway use tax is not counted again.
- [ ] An acceptance test shows the finding in the Deal Review and the Desk Check for a padded fee line.
- [ ] A state with no schedule makes no finding and says the fees were not checked; it never treats an amount as
      verified.
- [ ] Buyer-facing copy passes `ClaimVocabulary` and `PilotCopyClaimsTest`.
- [ ] The ticket's notes list what stays out of scope: non-resident and out-of-state registration (ticket 141), lease
      fees, and specialty plates.

## Implementation notes

Implemented on the branch, pending coordinator integration (2026-10-18).

- Data: `backend/src/main/resources/fees/government-fees.v1.json`, version `government-fees.2026-10.v1`, loaded and
  validated by `economics.fees.GovernmentFeeSchedule`. Florida and Texas are effective from 2026-01-01, North
  Carolina from 2024-07-01 (its last quadrennial fee adjustment); all were checked on 2026-10-18 and are reviewed by
  2027-01-01. No migration.
- Check: `economics.fees.GovernmentFeeCheck` (`government-fee-check.v1`) with `FeeLabels`, normalised keyword
  matching that reads abbreviations, odd punctuation and small misspellings ("TT&L", "D.M.V.", "Regis.",
  "registeration", "E-file"). Ambiguous lines are asked about rather than dropped: a label mixing a dealer charge and
  a state fee, a state fee line that names no fee, an unreadable amount, and a state fee name listed with the
  dealer's charges. No shared fee abstraction with ticket 140.
- Shown in the report as `analysis.governmentFeeCheck` (the Deal Review), in the Desk Check as `governmentFees`, and
  in the "I'm at the dealer" result as `governmentFees` with its material findings added to the existing findings
  list. Client: `frontend/src/GovernmentFees.tsx`, registered in the axe sweep.
- North Carolina's highway use tax stays with the tax pack; a line naming it is `TAX_COVERED` and not counted.
- Sources, the worked examples and the open points are in
  [docs/government-fee-schedules.md](../government-fee-schedules.md). Open points: the Texas 24-month new-car
  registration amount is not stated on the pages read, so two years of base and county fees are allowed; G.S. 20-85
  prints $25.00 where NCDOT's 2024 table lists $25.50, and $25.50 is used.

Out of scope: non-resident and out-of-state registration (ticket 141), lease fees, specialty and personalized
plates, heavy and commercial vehicles, dealer doc fees (ticket 140), and the Texas dealer vehicle inventory tax.
