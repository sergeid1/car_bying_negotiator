# 128 — Define the loan pre-approval adapter with a deterministic fake

**What to build:** A replaceable adapter that submits the Buyer's loan application to a licensed lending
partner, with the Buyer's authorization, and returns soft-inquiry pre-approvals from its lenders, with a deterministic fake and a conformance suite,
following tickets 101 and 125. The live partner is ticket 130. Spec 2.3 sections 5.10, 8.12 (LOAN-01 to
LOAN-03) and 11.2.

**Blocked by:** none.

**Status:** complete — `com.dealanalyzer.loan.preapproval`, as of 2026-10-15. `PreApprovalAdapter.request`
returns code-owned partner metadata, partner-level availability, and one entry per lender with the shared
`PartnerOutcome`; only `OFFERED` carries a pre-approval. A recorded `CreditAuthorization` is required, and a response
with any non-soft or unknown inquiry type is refused with no offers (LOAN-01). `PreApprovalContract.orderedOffers`
computes payment, interest and total cost with `FinancingCalculator` and orders by total cost then lender name, with
a payment reconciliation against the lender's figure (FIN-01, FIN-02, LOAN-02). `DeterministicFakePreApprovalAdapter`
is the default and passes the conformance suite; `PreApprovalProductionGuard` refuses it under `pilot`.
`PreApprovalService.request(caseId, accountId, journeyId, …)` meters every attempt as `PRE_APPROVAL` (V57 admits
journey rows); no application facts are stored. Open for 129: recording and loading the authorization, the account
export of journey usage, and the scope parameters, which differ from 125's `InsuranceQuoteScope`.

## Scope

- `PreApprovalAdapter.request(application, authorization)` returns, per lender, a
  pre-approval or a stated outcome (declined, needs more information, unavailable, timed out). A
  pre-approval carries amount, APR, term, conditions (vehicle age, mileage, down payment), expiry, the
  inquiry type, the lender's link to continue, and source metadata.
- The adapter refuses to send without a recorded FCRA authorization, and
  refuses any result whose inquiry type is not soft (LOAN-01).
- Payment, total interest and total cost come from the existing FIN-01 calculation, never from the lender's
  figures alone; a mismatch with the lender's stated payment is shown as a reconciliation (FIN-02).
- A `ProviderMeter.Operation.PRE_APPROVAL`.
- The fake is the default; main tests never call a live lender.
