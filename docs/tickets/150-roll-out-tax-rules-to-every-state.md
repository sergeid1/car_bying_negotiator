# 150 — Roll out tax rules to every state

**What to build:** Sourced jurisdiction packs for every state beyond the three pilot states, so a Buyer anywhere in the
United States gets the full report with a tax figure. Operator decision, 2026-10-22 (specification 2.8): once the
main flow is tested and working in production for Florida, Texas and North Carolina, roll the other states out
quickly rather than one pilot at a time.

Every tax figure, in the pilot states too, carries a short note. It says the figure is calculated from the state's
published rules as of the checked date and should match what the Buyer owes, and asks the Buyer to confirm the tax
with the dealer or the state's tax office before signing. The note should reassure without promising: the wording
may not say "guarantee" (`ProhibitedClaims`), so it says what the figure rests on and how to confirm it. Draft:

> We calculated this tax from {state}'s published rules, checked on {date}. It should match what you'll owe. Rates
> and local rules can change, so confirm the tax with the dealer or the {state} tax office before you sign.

**Blocked by:** ticket 22 (the controlled pilot release) and evidence that the main flow works in production for the
three pilot states. The tax note can ship earlier, on its own.

**Status:** open — filed on 2026-10-22.

## Scope

- One versioned pack per state in the `jurisdiction-tax-components.v1` format (tickets 108 and 137), each value read
  in full from statute and agency publications, with citations, checked dates and review dates.
- States with local rates (county, city or district taxes on vehicles) need a ZIP-to-rate source or a county table
  like Florida's. Where the local rate cannot be pinned down from the registration address, show the state part and
  say the local part is not included, rather than guess.
- Cross-state purchases (ticket 141) can then be calculated where both states' rules cover it, with sourced
  reciprocity and credit rules per state pair.
- Government fee schedules (ticket 139) and doc fee rules (ticket 140) for the same states, or a clear "not checked
  for this state" in their place.
- The tax note above, in English and Spanish, on the report, Desk Check and "I'm at the dealer".

## Done when

- [ ] The tax note ships for the pilot states and passes `ClaimVocabulary` and `PilotCopyClaimsTest` in both
      languages.
- [ ] Every new pack has sourced values, an effective date and a review date, and table-driven tests.
- [ ] A state with local rates never shows a tax total that leaves out a local part without saying so.
- [ ] Intake accepts Buyers registering in every state with a pack.
