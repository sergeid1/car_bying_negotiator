# 129 — Shop loan pre-approvals and use one at the desk

**What to build:** The Buyer journey for financing. The Buyer fills in one loan application, selects
participating lenders (with what each pays otdog shown), authorizes soft inquiries, and approves sending.
Pre-approvals come back side by side, ordered by total cost. A pre-approval the Buyer chooses becomes the
outside Finance Offer, so the report and the Desk Check compare the dealer's financing with it, and say so
plainly when the dealer's financing costs less. Spec 2.3 section 5.10, LOAN-01 to LOAN-03.

**Blocked by:** 124, 128. Live lenders need 123 and 130.

**Status:** open — filed on 2026-10-14. The coordinator reserves a migration number.

## Scope

- Application: amount, term, the vehicle (from the case when there is one), income, housing, and the
  identity details a soft inquiry needs, entered in the partner's hosted form when ticket 123 chooses that
  route. otdog never stores Social Security number.
- Lender selection with compensation shown, an FCRA authorization that names each selected lender, a review
  step, and an explicit "Send to N lenders".
- The Buyer can add an offer from a non-participating lender (for example, their credit union) through the
  existing outside-financing entry (`OutsideFinancing.tsx`), compared on the same terms.
- Results ordered by total cost for the same amount and term, with APR, payment, total interest,
  conditions and expiry.
- "Use this pre-approval" sets the outside Finance Offer; FIN-04 and the desk tactic for a dealer APR above
  the Buyer's own offer (DESK-04) use it, as they do today for a manually entered offer.
- A desk test proves that when the dealer's financing costs less than the chosen pre-approval, the Desk
  Check says so, whatever the lender pays otdog.
- Export and deletion as in ticket 126.

## Done when

- [ ] Acceptance tests cover authorization, soft-only refusal, sending only to selected lenders, ordering,
      the outside Finance Offer hand-off, the dealer-wins case, export and deletion.
- [ ] Client tests, the axe sweep and the 360 px gate pass.
