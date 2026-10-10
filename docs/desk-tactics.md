# Desk tactic policy v1 — Phase 1 of ticket 104

`desk-tactics.v1` is a deterministic documentary policy. It accepts the Dealer Offer's verified
candidate terms or the latest extraction's corrected terms as read, plus the Buyer's saved outside
Finance Offer. At the dealer, a Buyer-entered APR can override the saved APR. An Agreed Price's
baseline term supplies the stated shorter term when there is no outside offer. No model selects or
creates tactics. Questions are drafts, stored with every finding and policy version, with
`PENDING_OPERATOR_REVIEW`; operator approval is still required.

## Rules and calculations

- `PAYMENT_PACKING`: quoted payment minus the worksheet calculation must be **strictly greater
  than 100 cents per month**, the existing financing reconciliation tolerance. A $1 difference is
  within tolerance. Show the monthly difference and that difference times the stated term. This is
  an indicative payment gap, not an identified product or confirmed charge. Timing, rounding or
  different principal can also explain it. The finding says products may be included and asks for
  reconciliation, without asserting intent.
- `STRETCHED_TERM`: worksheet term exceeds the Buyer's stated term. Hold principal and APR fixed;
  show the signed monthly effect (negative means lower monthly payment) and extra scheduled interest
  from the longer term. Zero APR can give a lower payment and zero extra interest. Do not claim a
  causal conclusion about why the dealer chose the term.
- `APR_ABOVE_OWN_OFFER`: any supported dealer APR strictly above the Buyer's APR. Hold worksheet
  principal and term fixed and show extra scheduled interest and the monthly difference. This is an
  APR-only counterfactual, not a comparison of different lender fees or a recommendation of a lender.

All calculations call `FinancingCalculator.partialSchedule`, sharing its bounds, DECIMAL128
intermediates and HALF_UP payment cents, with the actual rounded final payment included in interest.
The ordinary monthly schedule assumes the first payment is one month after funding. Documentary
inputs do not establish actual loan timing or support balloon or irregular loan conclusions.

Principal is the worksheet's explicit `AMOUNT_FINANCED`. If absent, a bare worksheet with no trade
allowance or payoff may use its selling price less explicitly stated cash down, with this basis shown.
That comparison excludes taxes, fees and products outside those figures; it is not an OTD calculation.
Missing down is never zero. No trade equity is calculated. Ambiguous duplicate candidates, incorrect
units, invalid numbers and out-of-bound numbers are not selected or silently converted. Payment packing
needs complete supported payment inputs; observed longer-term or higher-APR findings can remain with
a stated reason the dollar effect is unavailable.

## Evidence and integration

Each finding stores a stable code, policy version, question and review state, evidence regions with
field names and verification flags, monthly/total effect in USD cents or a reason unavailable, and its
calculation basis. Saved outside-offer evidence includes its version and input snapshot; typed APR
evidence states that source. Findings over extraction candidates are always as read, even after a
correction. Fresh-case reports freeze the exact input terms and findings in the report JSON; HTML,
PDF and the report UI render the saved findings rather than recalculating.

`DeskCheckService.View.tacticFindings` is appended separately from Agreed Price differences. The
dealer-start result also appends `tacticFindings` and projects those into its existing lead selection.
The frontend uses `DeskTactics.tsx`. No migration or endpoint was added.

`DeskTacticPolicy.Rule` is the Phase 2 extension point: an adapter can close over the actual ticket
103 trade-result record and return a finding. It must consume that result, not calculate equity or
valuation here. The trade rules are pending coordinator authorization and ticket 103's interface.

The labelled fixtures in `backend/src/test/resources/fixtures/desk/tactics.json` are synthetic
development checks, covering each Phase 1 tactic and a clean worksheet. They do not measure accuracy
on real paperwork.
