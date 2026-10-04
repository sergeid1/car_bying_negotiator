# Provider and subprocessor inventory

This inventory supports the `provider-terms` and `infrastructure-and-privacy-inventory`
prerequisites in [the pilot readiness record](pilot-readiness-record.md). Both are currently
**blocked**, and this document records why.

## Summary

No external commercial provider processes Buyer data. Every adapter in use is a deterministic
local implementation, a self-hosted component, or an opt-in connection to infrastructure the
operator supplies. The Anthropic and OpenAI model clients are integrated but disabled, and neither
can be enabled while its terms are unexecuted (see below). There is therefore **no subprocessor, no processing
region, and no provider contract to describe** — not because the inventory is unfinished, but
because no third party processes Buyer data today.

This is exactly what blocks release. A public pilot requires real automotive and processing
providers, and each one adds contractual and privacy obligations this table cannot yet state.

## Current adapters

| Capability | Implementation | Processes Buyer data | External party |
| --- | --- | --- | --- |
| Vehicle identity | `Vin` checksum validation, Buyer-entered facts | In process | None |
| Listing evidence | `DeterministicListingEvidenceProvider` | In process | None |
| Market comparables | `DeterministicFakeMarketDataProvider` | In process | None |
| Deal-terms extraction | `DeterministicDealerDocumentProvider`, selected by `extraction.provider=deterministic` (the default). `ClaudeDealTermsExtractionProvider` exists behind `extraction.provider=claude`, which refuses startup until the rollout gate's obligations are met | In process | None — no model provider is called. Once selected and enabled, the Claude provider sends normalized page images only to Anthropic |
| Document parsing | Confined local container or disposable JVM | In process | None |
| Malware scanning | `ClamAvMalwareScanner` against an operator-run daemon | Document bytes | Operator-run; not a third party |
| Object storage | `EncryptedFileDocumentStorage` on an operator-supplied path | Encrypted originals | Operator-run |
| Database | PostgreSQL supplied by the operator | Case graph | Operator-run |
| Postal-code location | `PostalJurisdictionResolver` over bundled Census reference data | In process | None — the dataset ships with the application and is never queried remotely |
| Recovery email | `SmtpRecoveryEmail`, opt-in and unconfigured by default | Buyer email address | Operator's SMTP provider when enabled |
| Dealer email relay | `FakeDealerMailRelay` (deterministic, in memory) selected by `dealer-relay.provider=fake`, the default; `none` refuses every send. `RelayProductionGuard` refuses the fake under a pilot or production deployment. No real provider is integrated until specification §18 prerequisite 10 | Relay address, dealer address and message content; never the Buyer's email, phone or surname | None — a relay provider will receive dealer mail content once selected |
| Human review | Internal queue behind separate consent | Redacted fields | None |
| Model calls | `AnthropicModelGateway` or `OpenAiModelGateway` through the [AI execution harness](ai-execution-harness.md), one per deployment (`ai.model.provider`); disabled (`ai.model.enabled=false`) | Not while disabled; would send normalized page images and recognized text | Anthropic or OpenAI, once enabled — **terms unexecuted for both** |

## Market and listing readiness

No licensed listing or market source is integrated. These two deterministic adapters are
development fixtures. `EvidenceProviderProductionGuard` rejects either selected adapter at startup
and names it in the error when `evidence.deployment=production` (`EVIDENCE_DEPLOYMENT=production`)
or a `production`, `prod`, or `pilot` profile is active. A development property cannot override a
production profile. Future adapters must pass the reusable `EvidenceAdapterConformance` suite.
Market snapshots older than 45 days, invalid metadata, and schema drift become explicit unavailable
assessments without replacement prices; report generation also checks the age of stored snapshots.
Calls use `ProviderMeter` with request counts, status, start and finish timestamps (latency), and
zero fixture cost. Actual licensed-provider pricing, terms and measured coverage remain ticket 27.
Listing observations currently mean the listing as seen at retrieval, not a vendor's last-update date.

`GET /api/v1/operator/evidence/metrics?from=<UTC instant>&to=<UTC instant>` measures retained
market/listing calls by operation, provider and status, including known/unknown cost counts,
known USD cost, mean known call cost, latency sample count, mean and continuous p95 milliseconds.
The window includes records started at `from` and excludes records started at `to`. The combined
market/listing cost per case uses distinct cases with calls in that window, including failed calls
and retries; the report states both the mean and maximum over those case totals. Cases without calls
are excluded. The per-case ceiling comparison uses the maximum, so averaging cannot hide an overage.
Unknown-cost calls prevent an all-in cost comparison; unfinished calls prevent a complete latency
comparison. Fixture zero cost is explicitly recorded, never inferred from missing prices.

Configure `evidence.budget.per-case-cost-usd` (non-negative USD) and
`evidence.budget.p95-latency-millis` (positive milliseconds), or their Spring environment equivalents
`EVIDENCE_BUDGET_PER_CASE_COST_USD` and `EVIDENCE_BUDGET_P95_LATENCY_MILLIS`, with approved ceilings.
Neither has a default. `EvidenceBudget` rejects missing ceilings in `pilot`, `production`, `prod`
or `evidence.deployment=production`; development reports `UNSET`. Observations are compared with
the combined market/listing per-case cost ceiling and per-call p95 latency ceiling as `OVER`,
`WITHIN` or `NOT_MEASURABLE`. These are measurements, not an automatic release decision.
The existing development-adapter production guard remains independent and still blocks release.

Coverage lists every effective supported jurisdiction from `jurisdiction_policies`, including zero
samples. It counts attempted cases, available/unavailable/too-thin latest assessments, report coverage
events and included/excluded comparable decisions with their denominators. These figures describe
development adapters until ticket 27 integrates a licensed source. Comparable inclusion is the
recorded selection policy's decision, not independently labeled comparable relevance.

## Bundled reference data

Postal-code to jurisdiction resolution uses the U.S. Census Bureau 2020 ZCTA-to-county
relationship file, retrieved 2026-09-29 and seeded by
`V22__create_postal_jurisdictions.sql`. It is a work of the United States Government and is
uncopyrighted under 17 U.S.C. 105, so display, caching, derived-value and retention rights need
no contract and no attribution obligation is imposed. The table is queried locally, so a Buyer's
postal code is never sent to a third party.

Two limitations are recorded because they change Buyer-facing behavior rather than being
incidental: a ZCTA is not a USPS ZIP code, so PO-box-only ZIPs are absent and are reported as
unrecognized instead of unsupported; and 137 ZCTAs cross a state line, for which the Buyer is
asked which state applies rather than having a tax jurisdiction inferred for them.

Locating a postal code is separate from supporting it. A jurisdiction is usable only once it has
an effective-dated policy pack in `jurisdiction_policies`, which remains counsel-reviewed work
under the blocked `jurisdiction-pack` prerequisite.

## Purposes, retention, and deletion

- **Purpose.** Buyer data is processed only to produce and retain the Buyer's own Deal Analysis
  Report. Operational measurement is part of service operation; anonymized research requires
  separate consent, recorded per purpose in `consent_records`.
- **Retention.** Raw document artifacts default to 30 days (`document.retention`) and the Buyer
  may shorten it. Case access expires under `case.access.lifetime`.
- **Deletion.** Deletion revokes access immediately and a retryable worker erases the case graph
  and stored artifacts, as described in [the deletion policy](case-deletion-policy.md). Backup
  expiry is disclosed rather than claimed as verified erasure.
- **Training.** No customer document is used for model training, which is trivially true while no
  model provider is called. The Anthropic and OpenAI clients are integrated but off, and the model
  rollout gate refuses to start the application with either on until executed terms for that
  provider, validating data retention and the exclusion of customer documents from training, are
  recorded in `backend/src/main/resources/ai/model-vendor-terms.json`. Both records currently
  state `UNEXECUTED`, so this obligation is **unmet** for both. For OpenAI the terms must also
  cover any retention of API inputs and outputs for abuse monitoring; the client already sends
  `store: false` so responses are not kept as stored objects.

## What must be obtained before a public pilot

For each automotive or processing provider actually selected, written terms must cover consumer
display, caching, attribution, derived values, retention, deletion, training use, regional
processing, coverage, and cost. Until a provider is selected and its terms are executed, the
`provider-terms` prerequisite stays blocked and this table stays hypothetical.

Selecting hosting, object storage, queue, mail, and model providers also completes the
`infrastructure-and-privacy-inventory` prerequisite by making subprocessors and regions statable.
# Development checkout (ticket 44)

DevelopmentNoChargeCheckout is local, collects no payment or payment credentials, has no
external processor, and immediately records DEVELOPMENT_NO_CHARGE / PAID. It cannot start
under production, prod, or pilot. Ticket 32 must select and approve a real payment provider
before commercial enablement; payment-provider is a blocked readiness prerequisite.
# Shopping inventory adapter (ticket 53)

`InventorySearchAdapter` accepts structured Shopping Brief criteria and a radius, returning normalized
`InventoryListing` records. It has no URL-fetch operation and performs no scraping. No licensed inventory
source is integrated. `DeterministicFakeInventorySearchAdapter` is development-only, does no I/O, and labels
its provider, dealer identity, listings, and result notice as synthetic. Its varied fixture includes a
criteria match, near-miss, duplicate VIN, stale observation, missing VIN, and unknown facts/price.

`InventorySearchProductionGuard` refuses synthetic adapters under `pilot`, `prod`, or `production`
profiles and under `inventory.deployment=production` or `evidence.deployment=production`. The reusable
`InventorySearchAdapterConformance` suite must be extended by each future licensed adapter using
recorded available, unavailable, timed-out, and rate-limited scenarios. Vendor payloads stay inside adapters.
Listings use minor-unit prices, normalized VINs and explicit unknown fields. Duplicate VINs (or
source/listing IDs when VIN is absent) keep the newest observation. Observations older than seven days
are marked `STALE`; missing observation times are `UNKNOWN`. Retained stale listings are for explanation;
later matching must respect those flags. Fresh retrieval never makes an old observation current.

`InventorySearchService.search(accountId, journeyId)` reads the current Shopping Brief and meters one
`INVENTORY_SEARCH` call through the existing timeout/concurrency limits. V37 adds journey-scoped usage
without altering case-scoped callers or the V21/V28 view definitions. Usage stores code-owned provider
and licence metadata, the brief version and query/radius, normalized result count, retrieval time, elapsed
latency, status/availability and reported cost (null means unpriced, including failed calls). An unsuccessful
call records its attempt time as retrieval time and returns stated unavailability with no substituted listings.
The fixture reports zero cost. No Buyer identity or contact details cross the adapter boundary; queries
include postal code and are therefore personal data. Account exports include search history; journey and
account deletion remove and verify usage before deleting the parent graph. Search-start takes account then
journey locks, and late responses only update existing rows, so deletion cannot recreate search history.
