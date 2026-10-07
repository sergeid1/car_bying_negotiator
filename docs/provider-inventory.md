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
| Reply-intake address (inbound only) | `FakeDealerMailRelay` selected by `dealer-relay.provider=fake`, the default, issues addresses under `relay.invalid` that no mail can reach; `none` issues addresses with no inbound provider. `RelayProductionGuard` refuses the fake under a pilot or production deployment. The platform sends no dealer email (ticket 84): the Buyer sends every approved message from their own mail app. A real inbound provider waits on ticket 78's inbound mail authentication prerequisite (a verified provider webhook signature or equivalent, replay protection, and binding each message to its case's address); until then the Buyer is told forwarding is not switched on and pastes replies instead | Inbound: the reply-intake address, the forwarding sender and the forwarded message content | None — nothing leaves the platform toward a dealer |
| Connected mailbox (send only, ticket 83) | `FakeMailboxSender` selected by `mailbox.sender=fake`, the default, records messages instead of sending; `live` selects `GmailMailboxSender` (Gmail API `users.messages.send`, scope `gmail.send`) and `GraphMailboxSender` (Microsoft Graph `sendMail`, scopes `Mail.Send` and `offline_access`); `none` switches connecting off. `MailboxProductionGuard` refuses the fake under a pilot or production deployment, and production stays on `none` until the operator's Google OAuth consent-screen verification and Microsoft publisher verification are complete. A grant with any scope beyond send and the sending address is refused; the platform never reads the Buyer's mailbox. Tokens and the address are encrypted with `MAILBOX_TOKEN_ENCRYPTION_KEY`, never logged or exported, and deleted on disconnect, account deletion or a provider revocation error | The Buyer's OAuth tokens and connected address; each approved dealer message the Buyer sends with **Send from my email** (dealer address, subject, body) | **Google** (Gmail) or **Microsoft** (Outlook / Microsoft 365), whichever the Buyer connects, once enabled. Each receives the OAuth authorization exchange (client ID, authorization code, PKCE verifier, refresh token), the sending address in its ID token, and the full text and recipient of each message sent through it, which it keeps in the Buyer's Sent folder under the Buyer's own agreement with that provider. Neither receives anything while `mailbox.sender` is `fake` or `none` |
| Human review | Internal queue behind separate consent | Redacted fields | None |
| Model calls | `AnthropicModelGateway` or `OpenAiModelGateway` through the [AI execution harness](ai-execution-harness.md), one per deployment (`ai.model.provider`); disabled (`ai.model.enabled=false`) | Not while disabled; would send normalized page images and recognized text | Anthropic or OpenAI, once enabled — **terms unexecuted for both** |

The Anthropic default model is `claude-opus-5-5`, priced at $4 input / $20 output per million
tokens, with `output_config.effort=high`. Explicitly pinned `claude-opus-5` remains priced at
$5 / $25. Both remain disabled unless the provider's terms are executed and the configured model
has its own passing shadow comparison under the AI execution harness's rollout gate.

## Market and listing readiness

Declare every pilot or production deployment with `SPRING_PROFILES_ACTIVE=pilot` or
`SPRING_PROFILES_ACTIVE=production`, or equivalently `EVIDENCE_DEPLOYMENT=pilot` or
`EVIDENCE_DEPLOYMENT=production`. The shared `DeploymentStage` decision treats `pilot`,
`production` and `prod` as production-like, ignoring case and surrounding whitespace.
`checkout.deployment` and `inventory.deployment` remain legacy aliases with the same restrictive
effect. Any production-like declaration wins over development declarations. All guards use this
decision, including parser confinement, synthetic intake, readiness, secure cookies, test malware
scanning and storage-key validation. `document.malware-scanner=test` and missing or passphrase-like
document key material fail startup in these deployments. The active/retired base64 random
key ring and legacy compatibility rules are in the [hardening runbook](hardening-runbook.md).

No licensed listing or market source is integrated. These two deterministic adapters are
development fixtures. `EvidenceProviderProductionGuard` rejects either selected adapter at startup
and names it in the error under the shared pilot/production declaration above. A development property cannot override a
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
Neither has a default. `EvidenceBudget` rejects missing ceilings under every shared pilot/production
declaration; development reports `UNSET`. Observations are compared with
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
  expiry is disclosed rather than claimed as verified erasure. Mailbox connection tokens are
  deleted as soon as account deletion is requested; the send meter (`mailbox_sends`: times,
  outcomes and a keyed dealer-address hash, no content) goes with the case and the account.
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

# VIN decoding for Reference Vehicles (ticket 52)

Reference Vehicle VINs are decoded through the replaceable `VinDecoder` adapter, selected by
`vin-decoder.provider`:

| Value | Implementation | Processes Buyer data | External party |
| --- | --- | --- | --- |
| `fake` (default) | `FakeVinDecoder`: a fixed development sample; any other VIN is undecodable | In process | None. `VinDecoderProductionGuard` refuses it under a pilot or production deployment, so sample data is never shown as a real decode |
| `nhtsa-vpic` | `NhtsaVpicVinDecoder` calling `GET {base-url}/DecodeVinValues/{VIN}?format=json` | The VIN only; no account, case, name, email or location is sent | NHTSA (US Department of Transportation) |

**NHTSA vPIC terms as recorded.** The vPIC API (https://vpic.nhtsa.dot.gov/api/) is a free, public
US government service: no account, API key, contract or fee. Its data is a US federal government
work and carries no licence restriction on display or caching. Decodes are built from information
manufacturers submit to NHTSA (49 CFR Part 565), so coverage and accuracy depend on those
submissions; NHTSA states no service level and may limit automated traffic. No signed terms exist
to execute, so this adapter adds no `provider-terms` obligation, but before enabling it for a pilot
an operator re-reads the current vPIC page for usage limits and records the date checked here.

**How the adapter behaves.** Each attempt has a timeout (`vin-decoder.nhtsa-vpic.timeout`, default
4 s, at most 30 s). A connection failure, timeout, HTTP 429 or 5xx is retried up to
`vin-decoder.nhtsa-vpic.attempts` (default 2, at most 3) after `retry-delay`; any other answer is
not retried. When no usable answer arrives, the Buyer is told the decoder is unavailable and
nothing is saved or guessed. vPIC values are kept as vPIC states them; an attribute vPIC leaves
empty stays absent, and a VIN with no year, make or model is stored as undecodable with no
attributes. Each Reference Vehicle records its source (`NHTSA vPIC`) and decoder version
(`nhtsa-vpic-decodevinvalues-adapter-v1`). vPIC is free, so it is not metered as a paid provider.

**Retention.** VINs and decoded attributes are stored in `reference_vehicles` with the brief
version they belong to, exported with the account, and deleted with the journey or the account.
NHTSA receives VINs only; what it logs is outside this service's control.

# Shopping inventory adapter (ticket 53)

`InventorySearchAdapter` accepts structured Shopping Brief criteria and a radius, returning normalized
`InventoryListing` records. It has no URL-fetch operation and performs no scraping. No licensed inventory
source is integrated. `DeterministicFakeInventorySearchAdapter` is development-only, does no I/O, and labels
its provider, dealer identity, listings, and result notice as synthetic. Its varied fixture includes a
criteria match, near-miss, duplicate VIN, stale observation, missing VIN, and unknown facts/price.

`InventorySearchProductionGuard` refuses synthetic adapters under every shared pilot/production
declaration. The reusable
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
