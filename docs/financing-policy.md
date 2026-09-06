# Dealer financing policy v1

`dealer-financing.v1` calculates an ordinary fixed-rate monthly annuity from
Buyer-confirmed USD inputs. The first payment is one month after funding; all
payments are in arrears. Balloon, irregular, variable-rate, and other first-payment
timing are unsupported. Missing inputs produce `UNAVAILABLE`, including missing
fees, cash paid, deferred charges, timing, or an unconfirmed add-on list. Zero fees
and an empty add-on list must be explicit. No quoted payment is needed to calculate
a loan; its absence makes only the comparison unavailable.

All monetary inputs and outputs use integer USD cents. APR is a percentage with
at most eight decimal places. The monthly rate is APR / 1200. Nonzero APR uses
`principal * rate / (1 - (1 + rate)^(-months))`. Zero APR explicitly divides
principal by months. DECIMAL128 precision is retained internally. Regular payments
round HALF_UP to cents. The final payment is the remaining balance plus one month's
interest, rounded HALF_UP to cents. This can differ from the regular payment.
Schedules whose rounded payments produce a negative final payment or exceed the
supported arithmetic range are unavailable. Input bounds are $1 billion per
amount, 0–100% APR, and 1–1200 months; these are calculation bounds, not advice.

- Scheduled payments = regular payment × (months − 1) + final payment.
- Total interest = scheduled payments − principal.
- Principal must equal net amount due + financed lender fees.
- Total Transaction Cost = cash paid at or before closing + scheduled payments +
  known deferred charges. Cash paid **includes** upfront lender fees; deferred
  charges **exclude** scheduled loan payments. Financed fees are already in the
  principal and are not added a second time.
- Quoted minus calculated payment is signed. The inclusive tolerance is $1.00.
  A mismatch prompts neutral reconciliation: rounding, timing, fees, principal,
  or different terms may explain it. The calculation does not diagnose the cause.
- Each add-on compares the full loan against the same loan with that add-on's cash
  price removed from principal. Incremental interest equals the scheduled-payment
  difference minus the add-on cash price. APR, timing, term, fees and all other
  add-ons stay fixed. Add-on effects need not add exactly due to rounding. Changes
  to taxes, refunds, prepayment and early cancellation are excluded.

Purchase price (after applicable discounts and rebates), OTD, net due, principal,
fees, cash paid and deferred charges remain separately visible as Buyer inputs.
Loan outputs are labeled estimates under the declared schedule, not contractual
promises. Trade value is not added to Total Transaction Cost; the canonical cost
measures cash and scheduled payments, not the opportunity cost of the trade.

## Verification and persistence

`POST /api/v1/cases/{caseId}/finance-offers` requires a Case-Access-Token,
Idempotency-Key, and `{dealerOfferId, confirmed: true, loan: {...}}`.
The form under the core report controls provides the same flow. Only FINANCE cases
with a current verified Dealer Offer can save a Finance Offer. Known extracted
principal, APR, term, quoted payment and OTD cannot be contradicted; correct and
reverify the Dealer Offer first. Buyer confirmation supplies missing loan details.
The complete detached input is stored with its source, confirmation status and time.

A case lock serializes writes; JPA repositories store immutable Finance Offer and
result rows atomically. Database uniqueness protects `(case, version)`,
`(case, idempotency key)` and `(Finance Offer, policy version)`. Reusing a key with
different inputs conflicts. `GET /api/v1/cases/{caseId}/finance-offers/latest`
returns the saved detached inputs and result. Report versions freeze both, include
them in fingerprints, and remain unchanged after revisions or restart. Changed
Dealer Offers or vehicle evidence make prior financing stale in new reports.

Financing never receives `MATCHES_APPROVED_BASELINE` in v1: an approved financing
baseline comparison is not implemented. Supported calculations yield review
required; missing, stale and unsupported loans yield unable to verify and a partial
report. The report retains useful financing when market evidence is unavailable.

## Validation

`fixtures/financing/golden-loans.json` freezes independently calculated standard,
zero-APR and payment-mismatch/add-on examples. `properties.json` fixes the seed and
ranges for zero-interest, increasing-APR interest, payment-total conservation and
positive add-on interest checks. HTTP acceptance covers Buyer confirmation,
authorization, immutable reports, idempotency, missing fees, unsupported balloons,
stale Dealer Offers and application restart. Frontend tests cover amounts,
neutral mismatch text, source links, unsupported output and preserving unknown fees.
