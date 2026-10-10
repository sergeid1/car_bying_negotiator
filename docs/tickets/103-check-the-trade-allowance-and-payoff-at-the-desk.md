# 103 — Check the trade allowance and payoff at the desk

**What to build:** When dealer paperwork (a four-square, buyer's order or quote) shows a trade allowance,
the analysis and the Desk Check compare it with the Buyer's best unexpired written trade offer and the
valuation range, check the payoff the dealer used against the Buyer's, and show trade equity. Negative
equity added to the loan is shown with its cost over the term. The allowance is judged together with the
vehicle price, so a generous allowance paid for by a higher price shows its net effect. Spec 5.9,
TRADE-04, TRADE-05, acceptance scenarios 28 to 30.

**Blocked by:** 100 — Record the trade-in and what is owed on it; 101 — Define the trade valuation adapter
with a deterministic fake; 66 — Check desk paperwork against the Agreed Price; 67 — Extract four-square
worksheets.

**Status:** open — agent-ready (100 and 101 complete). Real value ranges need ticket 102; Buyer-held written
offers work without it.

- [ ] An allowance below the best unexpired written offer leads the trade findings with the shortfall in
      dollars and names the offer it is measured against.
- [ ] An allowance below the low end of the valuation range is flagged with the gap, labelled as an
      estimate from the named source; within or above the range is said plainly.
- [ ] A payoff on the paperwork that differs from the Buyer's current payoff is flagged with the
      difference; a stale or unknown Buyer payoff is said, never treated as matching.
- [ ] Trade equity is shown. Negative equity added to the amount financed shows its scheduled-payment and
      interest effect through `FinancingCalculator`, like a financed add-on.
- [ ] The trade VIN on the paperwork is checked against the Buyer's Trade Vehicle; a different VIN is a
      blocking finding.
- [ ] A trade shortfall is never counted as a dealer discount, and an allowance above value never offsets
      a price finding silently (DEAL-09, TRADE-05).
- [ ] Florida's trade-in sales-tax credit is applied through the existing jurisdiction policy, so the
      comparison with an outside offer includes the tax the Buyer would lose by selling the car elsewhere.
- [ ] Findings appear in the Desk Check (`TRADE` group, ahead of totals) and in a fresh desk case's report.
- [ ] Explanations and the copy stay within `ClaimVocabulary` and `PilotCopyClaimsTest`.
