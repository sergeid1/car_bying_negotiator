# 141 — Handle a car bought in one state and registered in another

**What to build:** A defined answer for a Buyer who buys from a dealer in one state and registers the car in
another, for example a North Carolina resident buying in Texas, or a Floridian buying across the Georgia line.
Today tax follows the Buyer's location, and tickets 108 and 137 left non-resident and out-of-state purchases out of
scope, so the case quietly applies the home state's tax even when the dealer collects the other state's tax or
none. Filed on 2026-10-18 after the pilot widened to three states (specification 2.4).

**Blocked by:** the operator, for the product decision below. The rule research and implementation can follow
once the decision is recorded.

**Status:** open — operator decision, filed on 2026-10-18.

## Decision needed

Pick one, and record it in this ticket and the next specification version:

1. **Detect and decline.** Ask where the car will be registered and where the dealer is. If they differ, say the
   product cannot calculate tax for this purchase yet, and do not show a tax total or a tax finding.
2. **Detect and explain.** As option 1, but show what each state's rule says about which tax is due and where, and
   compare the dealer's tax line against the registration state's rate as an informational figure.
3. **Calculate.** Ship sourced non-resident and reciprocity rules for the three pilot states and their neighbours,
   and calculate the tax due.

Option 1 is the least work and never shows a wrong number. Option 3 needs a sourced rule pass per state pair.

## Scope (after the decision)

- Intake asks for the registration state when it may differ from the dealer's state, without adding a step for
  the usual case where they match.
- The tax pack, trade-in credit (tickets 103 and 106) and government fees (ticket 139) apply according to the
  decision, and a case never presents a tax total it cannot support.
- Sources for any rule ship with citations, checked dates and review dates, as for the tax packs.

## Done when

- [ ] The operator's decision is recorded here and in the specification.
- [ ] Tests cover a same-state case (unchanged), a cross-state case under the chosen behaviour, and a dealer in a
      state with no pack.
- [ ] Buyer-facing copy passes `ClaimVocabulary` and `PilotCopyClaimsTest`.
