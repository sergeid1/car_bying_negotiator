# 128 — Define the loan pre-approval adapter with a deterministic fake

**What to build:** A replaceable adapter that sends one Buyer-approved loan application to the lenders the
Buyer selected and returns soft-inquiry pre-approvals, with a deterministic fake and a conformance suite,
following tickets 101 and 125. The live partner is ticket 130. Spec 2.3 sections 5.10, 8.12 (LOAN-01 to
LOAN-03) and 11.2.

**Blocked by:** none.

**Status:** open — filed on 2026-10-14.

## Scope

- `PreApprovalAdapter.request(application, selectedLenders, authorization)` returns, per lender, a
  pre-approval or a stated outcome (declined, needs more information, unavailable, timed out). A
  pre-approval carries amount, APR, term, conditions (vehicle age, mileage, down payment), expiry, the
  inquiry type, the lender's link to continue, and source metadata.
- The adapter refuses to send without a recorded FCRA authorization naming each selected lender, and
  refuses any result whose inquiry type is not soft (LOAN-01).
- Payment, total interest and total cost come from the existing FIN-01 calculation, never from the lender's
  figures alone; a mismatch with the lender's stated payment is shown as a reconciliation (FIN-02).
- A `ProviderMeter.Operation.PRE_APPROVAL`.
- The fake is the default; main tests never call a live lender.
