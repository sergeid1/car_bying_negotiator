# Desk tactic policy — ticket 104

The current rule set is `desk-tactics.v2`. The v1 rules and calculations below remain the basis for
the three financing tactics; existing v1 reports keep their stored questions, effects and rendering.

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

`DeskTacticPolicy.Rule` remains an extension point for additional documentary rules. The Phase 2
trade rules consume ticket 103's actual `TradeDeskCheck.Result` directly, without calculating equity
or valuation here.

The labelled fixtures in `backend/src/test/resources/fixtures/desk/tactics.json` are synthetic
development checks, covering each Phase 1 tactic and a clean worksheet. They do not measure accuracy
on real paperwork.

## Phase 2 — trade tactics in v2

- `NEGATIVE_EQUITY_FOLDED_IN` names ticket 103's `NEGATIVE_EQUITY_FINANCED` finding when
  `negativeEquityFinancedMinor` is positive. It does not require a worksheet label saying negative
  equity. It copies `negativeEquityCost.paymentIncreaseMinor` and `scheduledPaymentsIncreaseMinor`
  when the cost is available. Otherwise the known principal amount is shown, with the recorded reason
  the payment and interest effect is unavailable. The evidence retains `equityBasis` and the complete
  ticket 103 result, including the interest effect and policy version.
- `ALLOWANCE_PAID_FOR_BY_PRICE` names ticket 103's `ALLOWANCE_OFFSET_BY_PRICE` finding only when
  `priceTogether` shows both an allowance above its reference and a positive price increase. Its
  total effect is the already computed `netEffectMinor`: positive means the Buyer is ahead, negative
  means behind. There is no monthly effect for this purchase-price comparison. A fresh case without
  an Agreed Price has a null price increase and receives no tactic; the informational trade line remains.

Both tactics consume the existing `TradeDeskCheck.Result`, including its offer/valuation reference.
They do not calculate equity, payoff, value or the net effect, and they make no provider call. Report
trade tactics use the same offer terms as the saved `analysis.tradeCheck`, even if newer extraction
terms are used by the financing tactics. The existing same-offer guard is retained.

Each linked finding appends `tradeFindingCode`. In the Desk Check and report UI its label, draft
question, review state and evidence appear on the corresponding trade line. It is filtered out of
the standalone `DeskTactics` list; the repeated general Desk Check line is also omitted. The dealer-start
result carries `tacticCode` on its existing TRADE entry and retains that entry's amount, with no
second merged finding. Saved HTML/PDF attach the label and draft question to the trade line rather
than adding a separate worksheet-tactic item. The complete-payload reference appendix still preserves
all stored fields. v1 findings have no trade reference and render as before.

The new questions also remain `PENDING_OPERATOR_REVIEW`. No Phase 1 question has been changed or
marked approved. Labelled synthetic trade worksheets, frozen ticket 103 results and a clean trade
worksheet are in `fixtures/desk/trade-tactics.json`.
