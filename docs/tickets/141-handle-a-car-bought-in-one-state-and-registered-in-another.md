# 141 — Handle a car bought in one state and registered in another

**What to build:** A defined answer for a Buyer who buys from a dealer in one state and registers the car in
another, for example a North Carolina resident buying in Texas, or a Floridian buying across the Georgia line.
Today tax follows the Buyer's location, and tickets 108 and 137 left non-resident and out-of-state purchases out of
scope, so the case quietly applies the home state's tax even when the dealer collects the other state's tax or
none. Filed on 2026-10-18 after the pilot widened to three states (specification 2.4).

**Blocked by:** none. The operator decided on 2026-10-22 (below).

**Status:** complete — 2026-10-22, no migration. `economics.CrossStatePurchase` compares the dealer's state (from
`dealer_location`, a city and state or an older ZIP code) with the registration state; an unknown side is never a
difference. For a cross-state case `CashEconomicsService` passes the dealer's state to the calculator, which takes
the dealer's tax lines (or the paperwork's tax total) as written, applies no trade-in tax credit, and records
`taxCalculation.status = NOT_CHECKED` with `dealerState`; the result gets its own fingerprint, so a same-state
result is never reused. Results stored before this read as `CALCULATED`. The quoted total still reconciles against
the sum of the dealer's lines, and says so when the paperwork shows no tax line. Price boundaries offer no
out-the-door conversion; the trade check reports `NOT_CHECKED`; Desk Check drops the titling-tax note; the revised
offer comparison names the dealer's tax lines; the report's scope, top reasons and tax breakdown carry the
not-checked note, in English and Spanish (`FindingText`). Same-state results are unchanged. Tests:
`CrossStatePurchaseAcceptanceTest` (NC registration with a Texas dealer, a South Carolina dealer with no pack, and a
same-state control), `CashDealEconomicsCalculatorTest`, `CrossStatePurchaseTest`, `PriceBoundaryPolicyTest`,
`TradeDeskCheckTest`, `FindingTextTest`, `TradeCheck.test.tsx`.

## Decision (operator, 2026-10-22)

Option 1, narrowed to tax. When the dealer's state differs from the state where the car will be registered, the
Buyer gets the report and every other check as usual. Only tax is skipped:

- The tax figure is the dealer's tax line as written. It is not calculated, judged or used in any finding, and the
  Buyer is told that tax is not checked for a car bought in one state and registered in another, and to confirm it
  with the registration state's tax office or DMV.
- The out-the-door total still checks that the dealer's lines add up, using the dealer's tax figure.
- The trade-in tax credit is not calculated, because it is part of the tax.
- Price targets stay in selling-price terms; the out-the-door conversion needs a tax rate, so it is not offered.
- The doc fee is checked against the dealer's state rules (ticket 140, unchanged), and title and registration fees
  against the registration state's schedule (ticket 139, unchanged).
- Intake adds no step: the ZIP code already asks where the car will be registered, and the dealer's state is
  already asked. A dealer in a state with no pack is handled the same way. A Buyer registering in a state with no
  pack is still told the state is not supported.

Calculating cross-state tax (option 3) waits for the all-states rollout in ticket 150.

## Options considered

The three options put to the operator on 2026-10-18:

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

- [x] The operator's decision is recorded here and in the specification.
- [x] Tests cover a same-state case (unchanged), a cross-state case under the chosen behaviour, and a dealer in a
      state with no pack.
- [x] Buyer-facing copy passes `ClaimVocabulary` and `PilotCopyClaimsTest`.
