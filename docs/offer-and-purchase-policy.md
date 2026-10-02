# One-time offer and purchase policy

The shipped `purchase/offer.v1.json` has no approval; `/api/v1/offer` returns UNAVAILABLE
and existing cases retain the free flow. `purchase.offer-config` can select an operator-reviewed
versioned resource at startup. The approval records experimentKey, amountMinor, ISO currency,
approver, approvedAt, and rationale explicitly stating the price was not copied from a
competitor's advertised price, plus `notCopiedFromCompetitorAdvertisedPrice: true`.
Missing or incomplete approval disables the offer. Invalid terms fail startup. A changed
approval or terms require a new offerId and configuration version; restart publishes that version.
Only ONE_TIME_PER_CASE with BEFORE_REPORT is supported. Recurring, subscription, and
success-fee kinds fail configuration validation even without approval.

Free/paid codes, refund window and terms, jurisdiction (null until a pilot state is selected),
excluded purchase types, and scope limits are served from the versioned configuration.
Case start, vehicle evidence, upload/extraction, term verification, recovery, original document
export, and deletion are free. Report reads, explanations, negotiations and replies, revised
review, price plans and approved baselines require purchase while an approved offer is active.
Report HTML/PDF and ZIP archives containing reports require purchase at creation and download.
Archives of cases without reports and direct original-document exports remain free.
Analysis regeneration and market-assessment creation are also gated before work begins.
Synthetic fixture case start remains free; it may compute its fixture report during case creation.
Every gated request verifies case access before returning 402 PURCHASE_REQUIRED with offerId.
Refunds do not revoke access. One paid purchase grants the case across offer changes.

Purchases require Case-Access-Token and Idempotency-Key. Case locking serializes creation;
replays return the same purchase, with 200 instead of 201. Every accepted request key is
stored with the case, including additional keys returning an existing purchase. Unknown/stale offer IDs return 409
OFFER_CHANGED; no active offer returns 409 NO_ACTIVE_OFFER. Prices come from the approval,
never from the purchase request. `CheckoutProvider` accepts purchase identity and approved price,
and returns status/checkout URL. DevelopmentNoChargeCheckout is the sole adapter, completes
PAID immediately, and reports DEVELOPMENT_NO_CHARGE. It receives and stores no payment
credential. CheckoutProductionGuard fails startup naming it under production, prod, or pilot
profiles (or checkout.deployment). Ticket 32 owns real payment integration and enablement.

`POST /api/v1/operator/purchases/{purchaseId}/refunds` requires an authenticated SUPPORT
operator. It records the first refund time and operator once; repeat calls do not double-count.
Buyer and REVIEWER credentials cannot record refunds. This records measurement only;
the development adapter has no funds to return. See the deletion and pilot metrics policies
for anonymous count retention and denominators.

The public sample is labelled EXAMPLE / SYNTHETIC_CASE. `sample-golden.v1.json` matches the
standard golden cash-deal fixture; the real CashDealAnalyzer calculator/rating pipeline and
ReportAssembler produce it with fixed synthetic market evidence and no provider calls.
STRUCTURAL_ALLOWLIST / sample-redaction.v1 then removes identity, VIN, listing URLs,
locations below jurisdiction, document references, page/region evidence, and free text.
Unknown textual fields are null by default; only schema/policy and enumerated structural
fields survive. This sample is not a permissioned real case; ticket 32 owns that separate sample.
