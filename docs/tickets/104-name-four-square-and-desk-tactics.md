# 104 — Name four-square and desk tactics

**What to build:** Desk and report findings that name common worksheet tactics, show their dollar effect
where it can be calculated, and give the Buyer one plain question to ask at the desk. Rules are
deterministic over verified or as-read terms; the model only explains findings the rules produced. The
operator, a former car salesperson, supplies and reviews the list of tactics and the wording of each
question. Spec DESK-04, acceptance scenario 31.

**Blocked by:** 67 — Extract four-square worksheets; 103 — Check the trade allowance and payoff at the desk
(for the trade tactics); 12 — Analyze dealer financing and financed add-ons; 13 — Compare one outside
financing scenario.

**Status:** open — the first five rules are agent-ready; the operator's list may add more before the
ticket closes.

- [ ] **Payment packing:** a quoted payment above the payment calculated from the worksheet's own price,
      APR, term and down payment, beyond tolerance, is named as possibly including products not shown, with
      the monthly and total difference.
- [ ] **Stretched term:** a term longer than the Buyer's stated term, or a payment lowered mainly by
      lengthening the term, shows the extra interest compared with the shorter term.
- [ ] **Negative equity folded in:** negative equity added to the amount financed is named even when the
      worksheet does not label it (from ticket 103).
- [ ] **Allowance paid for by the price:** a trade allowance above the valuation range or best offer while
      the price is above the Agreed Price or market range is shown as its net effect.
- [ ] **APR above the Buyer's own offer:** a dealer APR above the Buyer's outside financing offer shows the
      interest difference over the term.
- [ ] Each finding has a stable code, its evidence regions, a dollar effect or a stated reason there is
      none, and a desk question the operator approved, stored with its policy version.
- [ ] Wording is neutral about intent ("the payment is $40 higher than its own numbers produce") and
      passes `PilotCopyClaimsTest`; the operator's review is recorded in the ticket.
- [ ] Labelled synthetic fixtures cover each tactic and a clean worksheet with no findings.
