# 44 — Make the one-time offer purchasable without a payment provider

**What to build:** The server side of ticket 32 that needs no payment provider and no permissioned
case. An operator-approved one-time price is served as an offer with its terms. The Deal Analysis
Report and the features built on it require a purchase while an offer is active. A purchase goes
through a replaceable checkout interface whose only adapter is a development no-charge adapter that
cannot start in production. A redacted, labelled sample report is built from a synthetic case. Ticket 32
keeps the redacted real sample, the real payment provider, and enabling either.

**Blocked by:** 29 — Lead the report with the total, the questions, and the next action. Ticket 45
builds the client against the interface below and does not block this ticket.

**Status:** complete — an approved one-time offer is served with its terms, report and analysis requests
return 402 until the case is purchased while an offer is active, purchases go through a
`CheckoutProvider` whose only adapter is a development no-charge checkout refused under `pilot` or
production, and a redacted synthetic sample report is public, as of 2026-10-02. The shipped
configuration has no approval, so the free flow is unchanged; ticket 32 keeps the real provider and sample.

- [x] The price is an experiment value with a recorded approval (experiment key, amount, currency,
      approver, approval time, rationale stating it was not copied from a competitor's advertised
      price). No offer is served without a complete approval record, and the shipped configuration
      has none, so local development and existing tests keep today's free flow.
- [x] `GET /api/v1/offer` serves the contract below without a case token. The free and paid scope,
      refund terms, scope limits, pilot jurisdiction (or its absence), and excluded purchase types
      come from versioned configuration, not from Buyer-facing copy in Java.
- [x] Subscriptions, recurring billing, and success fees cannot be configured; a test fails if an
      offer kind other than a one-time per-case purchase can be.
- [x] While an offer is active, every paid capability refuses an unpurchased case with `402` and
      `{"code":"PURCHASE_REQUIRED","offerId":...}`: report read (latest and by version), report
      HTML/PDF export, report explanation, negotiation drafts, reply assistance, and revised-offer
      review. A test enumerates the report-bearing endpoints so a new one cannot bypass the gate.
      Case start, vehicle evidence, document upload, extraction, term verification, case recovery,
      the Buyer's own document export, and case deletion stay free.
- [x] Purchases go through a `CheckoutProvider` interface. `DevelopmentNoChargeCheckout` completes a
      purchase immediately without collecting anything and reports `DEVELOPMENT_NO_CHARGE`.
      Like `EvidenceProviderProductionGuard`, startup fails closed naming the adapter under a
      `production`, `prod`, or `pilot` profile. No payment credential is ever received or stored
      (specification §13).
- [x] Purchase creation requires an `Idempotency-Key`; replaying it returns the same purchase.
      Purchases are stored with the case and deleted with it, with an anonymous purchase/refund
      count surviving deletion like the existing cohort totals, recorded in
      `docs/case-deletion-policy.md`.
- [x] A SUPPORT-only endpoint records a refund against a purchase. A refunded purchase still grants
      report access; the refund is recorded for measurement. Buyer credentials cannot record refunds.
- [x] Pilot metrics report paid cases and refunds with denominators, and ticket 42's cost per paid
      case becomes computable from them instead of staying unavailable.
- [x] `GET /api/v1/sample-report` serves a report produced by the real analysis pipeline from a
      synthetic golden fixture, passed through a redaction step that removes VIN, dealer identity,
      listing URL, Buyer location below the jurisdiction, document references and page regions, and
      free-text fields. It is labelled `EXAMPLE` with source `SYNTHETIC_CASE` and the redaction method
      and version. A test proves no redacted field survives.
- [x] The pilot-readiness record gains a blocked `payment-provider` prerequisite naming the
      development adapter and ticket 32, and `PilotReadinessTest` still passes.
- [x] `docs/offer-and-purchase-policy.md` documents the approval record, the free/paid boundary, the
      gate, the checkout interface and its guard, refunds, and the sample redaction method.

## Shared interface with ticket 45

Agreed before dispatch; change it only through the coordinator.

`GET /api/v1/offer` (no case token):

```json
{ "status": "UNAVAILABLE" }
```

```json
{
  "status": "AVAILABLE",
  "offerId": "string",
  "kind": "ONE_TIME_PER_CASE",
  "price": { "amountMinor": 0, "currency": "USD" },
  "paymentPoint": "BEFORE_REPORT",
  "free": ["CASE_START", "VEHICLE_EVIDENCE", "DOCUMENT_UPLOAD", "TERM_VERIFICATION",
           "CASE_RECOVERY", "DOCUMENT_EXPORT", "CASE_DELETION"],
  "paid": ["DEAL_ANALYSIS_REPORT", "REPORT_EXPORT", "REPORT_EXPLANATION",
           "NEGOTIATION_DRAFTS", "REPLY_ASSISTANCE", "REVISED_OFFER_REVIEW"],
  "refund": { "windowDays": 0, "terms": "FULL_REFUND_ON_REQUEST_WITHIN_WINDOW" },
  "jurisdiction": { "code": "string", "name": "string" },
  "excludedPurchaseTypes": ["NEW_VEHICLE", "LEASE", "PRIVATE_PARTY", "AUCTION", "MOTORCYCLE",
                            "RV", "COMMERCIAL_VEHICLE"],
  "scopeLimits": ["NO_DEALER_CONTACT", "NO_LEGAL_OR_TAX_CONCLUSIONS", "NO_VEHICLE_HISTORY",
                  "NO_TRADE_IN_VALUATION", "NO_FINAL_CONTRACT_REVIEW"],
  "checkout": "DEVELOPMENT_NO_CHARGE"
}
```

`jurisdiction` is `null` while no pilot state is selected. Codes are stable; the client owns the
wording.

`GET /api/v1/cases/{caseId}/entitlement` (`Case-Access-Token`):

```json
{ "reportAccess": "FREE_NO_OFFER" | "PURCHASE_REQUIRED" | "PURCHASED", "offerId": "string|null" }
```

`POST /api/v1/cases/{caseId}/purchases` (`Case-Access-Token`, `Idempotency-Key`, body
`{ "offerId": "string" }`) returns `201` (or `200` on replay):

```json
{ "purchaseId": "string", "status": "PAID" | "PENDING", "checkoutUrl": "string|null",
  "checkout": "DEVELOPMENT_NO_CHARGE" }
```

A stale or unknown `offerId` returns `409` `{"code":"OFFER_CHANGED"}`; no active offer returns `409`
`{"code":"NO_ACTIVE_OFFER"}`. `PENDING` with a `checkoutUrl` is reserved for a real provider (ticket 32).

Paid endpoints on an unpurchased case: `402` `{"code":"PURCHASE_REQUIRED","offerId":"string"}`.

`GET /api/v1/sample-report` (no case token):

```json
{ "label": "EXAMPLE", "source": "SYNTHETIC_CASE",
  "redaction": { "method": "string", "version": "string", "removed": ["VIN", "..."] },
  "report": { "...": "same shape as GET /cases/{caseId}/reports/latest, redacted fields null" } }
```

## Implementation notes — 2026-10-02

- Migration `V27__create_case_purchases.sql` adds `case_purchases`, `case_purchase_requests`
  (every accepted idempotency key), anonymous `purchase_totals`, and
  `acquisition_channel_totals.removed_paid_count`.
- The offer is a versioned resource, `classpath:purchase/offer.v1.json` (`purchase.offer-config`);
  changing approval or terms needs a new `offerId`. `approval` is `null` as shipped.
- `PaidCapabilityGate` is one MVC interceptor over the report, deal-review, price-plan,
  approved-baseline and report-total route families plus `POST` analyses and market assessments. It
  runs before the controller, so an unpurchased case gets 402 even when no report exists and no paid
  work runs. `DealAnalysisController` is unchanged. Report ZIP exports are gated; document-only exports
  of a case without a report stay free.
- `CheckoutProductionGuard` refuses `DevelopmentNoChargeCheckout` under `production`, `prod` or
  `pilot` profiles or `checkout.deployment=production`. Unknown purchase-body fields are rejected.
- Refunds are recorded by `POST /api/v1/operator/purchases/{purchaseId}/refunds` (SUPPORT only) and
  do not revoke access. Pilot metrics add `paidCases`, `refunds`, and channel `costPerPaidCase`.
- `GET /api/v1/sample-report` runs the golden cash-deal input through the real analyzer and
  assembler with fixed synthetic market evidence and redaction `FIELD_CATEGORIES` /
  `sample-redaction.v2`. Named identity, location, document, page-region and source-authored text
  fields are removed; generated wording, amounts and rates are kept. A reviewed inventory of every
  textual path fails the build when a new one appears.
- The pilot-readiness record gains a blocked `payment-provider` prerequisite. See
  `docs/offer-and-purchase-policy.md`.
