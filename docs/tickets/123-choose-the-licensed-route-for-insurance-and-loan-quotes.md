# 123 — Choose the licensed route for insurance and loan quotes

**What to build:** Nothing in code. The operator, with counsel, settles how otdog may legally send a
Buyer's insurance and loan applications to several providers and be paid for it, and records the result.
Spec 2.3 (section 18, prerequisite 12, and the "Licensed route for Phase 5" open decision). Operator
decision on 2026-10-14: insurance and loan quote shopping, paid by the insurers and lenders the Buyer
chooses, is how the free tool is funded.

**Blocked by:** the operator and counsel.

**Status:** open — operator action, filed on 2026-10-14.

## Questions to settle

- **Insurance.** In Florida, taking an insurance application and sending it to insurers for pay is
  generally licensed agency work (Fla. Stat. 626.112), and an unlicensed referrer may not be paid a fee
  that depends on a policy being bought (626.112(8)). Choose one:
  1. a licensed insurance comparison marketplace takes the application and the per-policy commission,
     and pays otdog a flat fee per completed quote (fastest; otdog stays unlicensed); or
  2. ClearSight Labs becomes a licensed Florida insurance agency with a licensed agent and contracts with
     insurers directly (higher revenue per policy; months of licensing and carrier appointments).
- **Loans.** Whether to work through a licensed lending marketplace or contract with lenders and credit
  unions directly; whether Florida treats otdog as a loan broker or needs another license; that the Buyer
  never pays otdog a fee; and the FCRA written authorization for soft inquiries.
- **Disclosure.** Whether the general "how otdog makes money" line (spec 1.2) is enough, or whether
  FTC or Florida rules need more; the operator prefers not to show what each provider pays.
- **Data.** GLBA privacy notice and the FTC Safeguards Rule's written security program; whether
  Social Security number and date of birth are entered only in the partner's hosted form (recommended, so
  otdog never holds them); TCPA consent if any provider may call or text the Buyer.
- **Partner terms** must forbid reselling or sharing the application, forbid contacting the Buyer beyond
  the quote without the Buyer's consent, allow showing quotes side by side ordered by cost, and give
  otdog a flat fee that does not vary with the quote's position.

## Done when

- [ ] The chosen route for insurance and for loans is recorded in `docs/provider-inventory.md` with the
      partners, their licences, data they receive, retention, and fees.
- [ ] Counsel's conclusions on licensing, FCRA, GLBA and TCPA are recorded (without privileged detail).
- [ ] Partner terms are executed, or the blocker is recorded.
