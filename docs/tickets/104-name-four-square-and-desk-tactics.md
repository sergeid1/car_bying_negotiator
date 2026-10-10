# 104 — Name four-square and desk tactics

**What to build:** Desk and report findings that name common worksheet tactics, show their dollar effect
where it can be calculated, and give the Buyer one plain question to ask at the desk. Rules are
deterministic over verified or as-read terms; the model only explains findings the rules produced. The
operator, a former car salesperson, supplies and reviews the list of tactics and the wording of each
question. Spec DESK-04, acceptance scenario 31.

**Blocked by:** 67 — Extract four-square worksheets; 103 — Check the trade allowance and payoff at the desk
(for the trade tactics); 12 — Analyze dealer financing and financed add-ons; 13 — Compare one outside
financing scenario.

**Status:** open — all five rules implemented, no migration (V53 unused), as of 2026-10-10; operator review of the
desk questions pending. `desk.tactic.DeskTacticPolicy` (`desk-tactics.v2`) names payment packing (tolerance $1 a
month), stretched term and APR above the Buyer's own offer through `FinancingCalculator`, and names negative equity
folded in and an allowance paid for by the price from ticket 103's `TradeDeskCheck.Result` without recomputing it.
The two trade tactics label their existing `TRADE` line (`tradeFindingCode`) instead of repeating its dollar
figure. Findings appear in the Desk Check (`tacticFindings`), the "I'm at the dealer" result (which now uses this
APR rule in place of ticket 105's, firing on any difference rather than 0.1 points) and frozen reports; saved v1
reports render unchanged. All five desk questions are stored as `PENDING_OPERATOR_REVIEW` and the Buyer sees a
pending-review note until they are approved. The operator may add tactics before this closes.

- [x] **Payment packing:** a quoted payment above the payment calculated from the worksheet's own price,
      APR, term and down payment, beyond tolerance, is named as possibly including products not shown, with
      the monthly and total difference.
- [x] **Stretched term:** a term longer than the Buyer's stated term, or a payment lowered mainly by
      lengthening the term, shows the extra interest compared with the shorter term.
- [x] **Negative equity folded in:** negative equity added to the amount financed is named even when the
      worksheet does not label it (from ticket 103).
- [x] **Allowance paid for by the price:** a trade allowance above the valuation range or best offer while
      the price is above the Agreed Price or market range is shown as its net effect.
- [x] **APR above the Buyer's own offer:** a dealer APR above the Buyer's outside financing offer shows the
      interest difference over the term.
- [ ] Each finding has a stable code, its evidence regions, a dollar effect or a stated reason there is
      none, and a desk question the operator approved, stored with its policy version.
- [ ] Wording is neutral about intent ("the payment is $40 higher than its own numbers produce") and
      passes `PilotCopyClaimsTest`; the operator's review is recorded in the ticket.
- [x] Labelled synthetic fixtures cover each tactic and a clean worksheet with no findings.
