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

**Status:** complete for text-bearing paperwork and recorded trades — no migration (V52 unused), as of 2026-10-10.
`trade.desk.TradeDeskCheck` (`trade-desk-check.v1`) compares a paperwork trade allowance with the best unexpired
written offer, the valuation range from ticket 101's adapter (labelled as an estimate from its source, or
unavailable), the Buyer's payoff (stale or unknown never matches) and the trade VIN (new deal-terms field
`TRADE_VIN`; a different VIN blocks). It shows equity, and negative equity's loan cost through
`FinancingCalculator` as a financed add-on. Findings lead the Desk Check's `TRADE` group, join the "I'm at the
dealer" result and the report (`analysis.tradeCheck`). The trade-in tax credit comes from the case's jurisdiction
pack and enters the outside-offer comparison. Open: no US-FL jurisdiction pack exists, so Florida cases show the
tax effect as unavailable until a sourced pack lands (it also needs the county surtax on the first $5,000); the
Claude extraction prompt needs a new version to read `TRADE_VIN` from photos; valuations are fetched on read with
a 10-minute in-memory reuse, to revisit against ticket 102's caching terms; real value ranges need ticket 102.

- [x] An allowance below the best unexpired written offer leads the trade findings with the shortfall in
      dollars and names the offer it is measured against.
- [x] An allowance below the low end of the valuation range is flagged with the gap, labelled as an
      estimate from the named source; within or above the range is said plainly.
- [x] A payoff on the paperwork that differs from the Buyer's current payoff is flagged with the
      difference; a stale or unknown Buyer payoff is said, never treated as matching.
- [x] Trade equity is shown. Negative equity added to the amount financed shows its scheduled-payment and
      interest effect through `FinancingCalculator`, like a financed add-on.
- [x] The trade VIN on the paperwork is checked against the Buyer's Trade Vehicle; a different VIN is a
      blocking finding.
- [x] A trade shortfall is never counted as a dealer discount, and an allowance above value never offsets
      a price finding silently (DEAL-09, TRADE-05).
- [x] Florida's trade-in sales-tax credit is applied through the existing jurisdiction policy, so the
      comparison with an outside offer includes the tax the Buyer would lose by selling the car elsewhere.
- [x] Findings appear in the Desk Check (`TRADE` group, ahead of totals) and in a fresh desk case's report.
- [x] Explanations and the copy stay within `ClaimVocabulary` and `PilotCopyClaimsTest`.
