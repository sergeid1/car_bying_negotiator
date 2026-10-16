# 129 — Shop loan pre-approvals and use one at the desk

**What to build:** The Buyer journey for financing. The Buyer fills in one loan application and
authorizes soft inquiries, and a licensed lending partner returns pre-approvals from many lenders.
Pre-approvals come back side by side, ordered by total cost. A pre-approval the Buyer chooses becomes the
outside Finance Offer, so the report and the Desk Check compare the dealer's financing with it, and say so
plainly when the dealer's financing costs less. Spec 2.3 section 5.10, LOAN-01 to LOAN-03.

**Blocked by:** 124, 128. Live lenders need 123 and 130.

**Status:** complete against the ticket 128 fake, as of 2026-10-16. `com.dealanalyzer.loan` adds the application
(V59: `loan_applications`, `loan_pre_approval_requests`, `loan_offers`) and case and journey routes under `/loan`, with
`/pre-approval-requests`, `/own-offers` and `/chosen-offer`. A case in a journey uses the journey's application, as
insurance and trades do. Requests need the current written credit authorization; partner offers are kept only from soft
inquiries (code and a V59 check). Offers for the same amount and term are ordered by FIN-01 total cost, Buyer-entered
offers on the same terms. "Use this pre-approval" records the choice; the outside Finance Offer form can fill from it
(`preApprovalId`, source `BUYER_CHOSEN_PRE_APPROVAL`), and desk start, the Desk Check and DESK-04 use the later of the
chosen offer and a manual outside offer. When the dealer's APR is lower, the new `DEALER_FINANCING_COSTS_LESS` finding
says so with the interest difference, whatever lenders pay otdog (`desk-tactics.v3`). Export and deletion follow ticket
126. Open in ticket 135 (after 123 and 130): otdog stores no name, address, date of birth or SSN, so a real soft
inquiry needs a hand-off to the partner's form; the authorization text is a draft (`otdog-credit-authorization.draft-1`).

## Scope

- Application: amount, term, the vehicle (from the case when there is one), income, housing, and the
  identity details a soft inquiry needs, entered in the partner's hosted form when ticket 123 chooses that
  route. otdog never stores Social Security number.
- An FCRA authorization in plain words before anything goes to the partner, and the "how otdog makes
  money" line from ticket 124 beside the results.
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

- [x] Acceptance tests cover authorization, soft-only refusal, sending only to the partner, ordering,
      the outside Finance Offer hand-off, the dealer-wins case, export and deletion.
- [x] Client tests, the axe sweep and the 360 px gate pass.
