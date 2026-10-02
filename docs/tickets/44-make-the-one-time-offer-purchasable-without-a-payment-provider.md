# 44 — Make the one-time offer purchasable without a payment provider

**What to build:** The server side of ticket 32 that needs no payment provider and no permissioned
case. An operator-approved one-time price is served as an offer with its terms. The Deal Analysis
Report and the features built on it require a purchase while an offer is active. A purchase goes
through a replaceable checkout interface whose only adapter is a development no-charge adapter that
cannot start in production. A redacted, labelled sample report is built from a synthetic case. Ticket 32
keeps the redacted real sample, the real payment provider, and enabling either.

**Blocked by:** 29 — Lead the report with the total, the questions, and the next action. Ticket 45
builds the client against the interface below and does not block this ticket.

**Status:** ready-for-agent

- [ ] The price is an experiment value with a recorded approval (experiment key, amount, currency,
      approver, approval time, rationale stating it was not copied from a competitor's advertised
      price). No offer is served without a complete approval record, and the shipped configuration
      has none, so local development and existing tests keep today's free flow.
- [ ] `GET /api/v1/offer` serves the contract below without a case token. The free and paid scope,
      refund terms, scope limits, pilot jurisdiction (or its absence), and excluded purchase types
      come from versioned configuration, not from Buyer-facing copy in Java.
- [ ] Subscriptions, recurring billing, and success fees cannot be configured; a test fails if an
      offer kind other than a one-time per-case purchase can be.
- [ ] While an offer is active, every paid capability refuses an unpurchased case with `402` and
      `{"code":"PURCHASE_REQUIRED","offerId":...}`: report read (latest and by version), report
      HTML/PDF export, report explanation, negotiation drafts, reply assistance, and revised-offer
      review. A test enumerates the report-bearing endpoints so a new one cannot bypass the gate.
      Case start, vehicle evidence, document upload, extraction, term verification, case recovery,
      the Buyer's own document export, and case deletion stay free.
- [ ] Purchases go through a `CheckoutProvider` interface. `DevelopmentNoChargeCheckout` completes a
      purchase immediately without collecting anything and reports `DEVELOPMENT_NO_CHARGE`.
      Like `EvidenceProviderProductionGuard`, startup fails closed naming the adapter under a
      `production`, `prod`, or `pilot` profile. No payment credential is ever received or stored
      (specification §13).
- [ ] Purchase creation requires an `Idempotency-Key`; replaying it returns the same purchase.
      Purchases are stored with the case and deleted with it, with an anonymous purchase/refund
      count surviving deletion like the existing cohort totals, recorded in
      `docs/case-deletion-policy.md`.
- [ ] A SUPPORT-only endpoint records a refund against a purchase. A refunded purchase still grants
      report access; the refund is recorded for measurement. Buyer credentials cannot record refunds.
- [ ] Pilot metrics report paid cases and refunds with denominators, and ticket 42's cost per paid
      case becomes computable from them instead of staying unavailable.
- [ ] `GET /api/v1/sample-report` serves a report produced by the real analysis pipeline from a
      synthetic golden fixture, passed through a redaction step that removes VIN, dealer identity,
      listing URL, Buyer location below the jurisdiction, document references and page regions, and
      free-text fields. It is labelled `EXAMPLE` with source `SYNTHETIC_CASE` and the redaction method
      and version. A test proves no redacted field survives.
- [ ] The pilot-readiness record gains a blocked `payment-provider` prerequisite naming the
      development adapter and ticket 32, and `PilotReadinessTest` still passes.
- [ ] `docs/offer-and-purchase-policy.md` documents the approval record, the free/paid boundary, the
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
