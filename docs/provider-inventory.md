# Provider and subprocessor inventory

This inventory supports the `provider-terms` and `infrastructure-and-privacy-inventory`
prerequisites in [the pilot readiness record](pilot-readiness-record.md). Both are currently
**blocked**, and this document records why.

## Summary

The default development configuration sends no Buyer documents to external commercial providers.
Amazon S3 is selected for AWS document hosting (ticket 98); enabling `document.storage.provider=s3`
sends encrypted originals/pages and identifier-only keys to AWS in the configured region.
The actual region, AWS terms and deployed bucket controls remain ticket 99 obligations.
Other adapters in default use are deterministic
local implementation, a self-hosted component, or an opt-in connection to infrastructure the
operator supplies. The Anthropic and OpenAI model clients are integrated but disabled, and neither
can be enabled while its terms are unexecuted (see below). The table distinguishes available
opt-in providers from the default local adapters; availability of an adapter does not establish
executed terms or deployment evidence.

This is exactly what blocks release. A public pilot requires real automotive and processing
providers, and each one adds contractual and privacy obligations this table cannot yet state.

## Current adapters

| Capability | Implementation | Processes Buyer data | External party |
| --- | --- | --- | --- |
| Breached password check (ticket 96) | `BreachedPasswords`, enabled by default; Have I Been Pwned Pwned Passwords range API with padding and a two-second timeout; outages fail open with a fixed warning. Configurable/offline disablement documented in the account policy | Only five uppercase hex characters of a SHA-1 digest; never the password, remaining digest, email or account ID. Provider also sees the server's network address | Have I Been Pwned (`api.pwnedpasswords.com`); [range API and privacy protocol](https://haveibeenpwned.com/API/v3#SearchingPwnedPasswordsByRange). No Buyer documents are sent. Operator must record applicable terms and deployment choice before release |
| Vehicle identity | `Vin` checksum validation, Buyer-entered facts | In process | None |
| Model recalls and crash-test ratings (ticket 116) | `VehicleSafetyProvider`; deterministic fake is the development default; `vehicle-safety.provider=nhtsa` selects `NhtsaVehicleSafetyProvider`. `VehicleSafetyProductionGuard` refuses the fake in pilot/production | Year, make, model only; never VIN, case/account identifiers, location or documents. Rating-detail requests send NHTSA's returned variant ID only | **NHTSA** (`api.nhtsa.gov`), free public US government data; [API documentation](https://www.nhtsa.gov/nhtsa-datasets-and-apis), [API use policy](https://api.nhtsa.gov/). HTTPS, no redirects, 4-second per-request timeout, two attempts for transport/429/5xx failures, 1 MiB body cap. No VIN lookup is queried |
| Listing evidence | `DeterministicListingEvidenceProvider` | In process | None |
| Listing captures (ticket 115) | The Buyer's own screenshot, saved PDF or pasted text of a dealer's ad, through ordinary document intake as `LISTING_CAPTURE`. `DeterministicListingCaptureProvider` reads text-bearing captures and follows `extraction.provider=deterministic`; `ClaudeListingCaptureProvider` (prompt `listing-capture-prompt.v1`, schema `listing-capture.v1`) follows `extraction.provider=claude` and refuses startup until its own shadow comparison is recorded. Confirmed fields become a Listing Snapshot with source `BUYER_SUPPLIED_LISTING_CAPTURE`. The platform never fetches the listing page | In process | None — no listing site is contacted. Once the Claude provider is selected and enabled, it sends the capture's normalized page images only to Anthropic |
| Market comparables | `DeterministicFakeMarketDataProvider` | In process | None |
| Deal-terms extraction | `DeterministicDealerDocumentProvider`, selected by `extraction.provider=deterministic` (the default). `ClaudeDealTermsExtractionProvider` exists behind `extraction.provider=claude`, which refuses startup until the rollout gate's obligations are met | In process | None — no model provider is called. Once selected and enabled, the Claude provider sends normalized page images only to Anthropic |
| Document parsing | Confined local container or disposable JVM | In process | None |
| Malware scanning | `ClamAvMalwareScanner` against an operator-run daemon | Document bytes | Operator-run; not a third party |
| Object storage | Amazon S3, operator-selected `DOCUMENT_STORAGE_S3_REGION` (record the actual region before pilot; ticket 99) | Application AES-GCM encrypted originals and normalized pages, plus SSE-S3 | AWS; instance/task role, private bucket; local file adapter remains the development default |
| Database | PostgreSQL supplied by the operator | Case graph | Operator-run |
| Postal-code location | `PostalJurisdictionResolver` over bundled Census reference data | In process | None — the dataset ships with the application and is never queried remotely |
| Account/recovery email and Buyer notifications (ticket 97) | `SmtpRecoveryEmail` through Amazon SES SMTP, port 587 with required STARTTLS; enabled by a configured company-domain From address. `SES_REGION` defaults to `us-east-1`; actual deployed region pending ticket 93. Signed SNS bounce/complaint intake maintains V51 keyed-hash suppression | Recipient address and plain-text message text, including single-use access links; SNS sends recipient feedback to the application, which retains only keyed hashes, fixed reasons and timestamps | Amazon Web Services (SES and SNS), same operator-selected region; credentials from deployment secret store. No pixels, click/open tracking or link rewriting. Account removal deletes the matching application suppression entry; AWS retention and suppression policy require operator review before pilot |
| Reply-intake address (inbound only) | `FakeDealerMailRelay` selected by `dealer-relay.provider=fake`, the default, issues addresses under `relay.invalid` that no mail can reach; `none` issues addresses with no inbound provider. `RelayProductionGuard` refuses the fake under a pilot or production deployment. The platform sends no dealer email (ticket 84): the Buyer sends every approved message from their own mail app. A real inbound provider waits on ticket 78's inbound mail authentication prerequisite (a verified provider webhook signature or equivalent, replay protection, and binding each message to its case's address); until then the Buyer is told forwarding is not switched on and pastes replies instead | Inbound: the reply-intake address, the forwarding sender and the forwarded message content | None — nothing leaves the platform toward a dealer |
| Connected mailbox (send only, ticket 83) | `FakeMailboxSender` selected by `mailbox.sender=fake`, the default, records messages instead of sending; `live` selects `GmailMailboxSender` (Gmail API `users.messages.send`, scope `gmail.send`) and `GraphMailboxSender` (Microsoft Graph `sendMail`, scopes `Mail.Send` and `offline_access`); `none` switches connecting off. `MailboxProductionGuard` refuses the fake under a pilot or production deployment, and production stays on `none` until the operator's Google OAuth consent-screen verification and Microsoft publisher verification are complete. A grant with any scope beyond send and the sending address is refused; the platform never reads the Buyer's mailbox. Tokens and the address are encrypted with `MAILBOX_TOKEN_ENCRYPTION_KEY`, never logged or exported, and deleted on disconnect, account deletion or a provider revocation error | The Buyer's OAuth tokens and connected address; each approved dealer message the Buyer sends with **Send from my email** (dealer address, subject, body) | **Google** (Gmail) or **Microsoft** (Outlook / Microsoft 365), whichever the Buyer connects, once enabled. Each receives the OAuth authorization exchange (client ID, authorization code, PKCE verifier, refresh token), the sending address in its ID token, and the full text and recipient of each message sent through it, which it keeps in the Buyer's Sent folder under the Buyer's own agreement with that provider. Neither receives anything while `mailbox.sender` is `fake` or `none` |
| Google and Apple sign-in (ticket 113) | `SocialSignInService` with `SocialIdentityProvider.Google` (Google Identity, OpenID Connect) and `SocialIdentityProvider.Apple` (Sign in with Apple, `response_mode=form_post`, ES256 client secret). Each is offered only when its `account.social-sign-in.*` settings are complete; pilot/production refuse partial settings, plain-HTTP redirect bases and endpoint overrides. No fake provider exists | The Buyer's browser goes to the provider's consent page; the server sends the authorization code, PKCE verifier, redirect URI and client credentials to the provider's token endpoint and receives an ID token. Only the provider, a keyed hash of the `sub` and the link date are kept; provider tokens are discarded after the ID token check | **Google** (Google Identity) or **Apple** (Sign in with Apple), whichever the Buyer chooses, once configured. Each learns that the Buyer signed in to otdog and receives the client ID and code exchange; the Buyer's account with them is under their own agreement with that provider. Operator must register the callback URLs, complete Google's OAuth consent-screen review for `openid email` and Apple's Services ID and domain verification, and record applicable terms before release. Neither is contacted while unconfigured |
| Human review | Internal queue behind separate consent | Redacted fields | None |
| Model calls | `AnthropicModelGateway` or `OpenAiModelGateway` through the [AI execution harness](ai-execution-harness.md), one per deployment (`ai.model.provider`); disabled (`ai.model.enabled=false`) | Not while disabled; would send normalized page images and recognized text | Anthropic or OpenAI, once enabled — **terms unexecuted for both** |

Vehicle safety lookups cache successful answers by normalized year/make/model for six hours,
up to 500 model entries per process. `vehicle-safety.cache-ttl` must be positive and at most
one day; `vehicle-safety.cache-max-entries` must be 1–10,000. Failures are not cached. The
adapter settings are `vehicle-safety.nhtsa.base-url`, `.timeout` (positive, at most 30 seconds),
`.attempts` (1–3), and `.retry-delay` (0–5 seconds). HTTP is allowed only on loopback for
stub-server tests. `ProviderMeter` records `VEHICLE_SAFETY` calls, including failures and its
shared time/concurrency limit; cache hits make no provider call. No query text or VIN is stored
in metering. Model facts and their retrieval time are copied into the report JSON and its frozen
evidence, so later lookups or cache expiry cannot change saved reports. Existing report export
and case-erasure paths include that JSON without a new table or migration. A VIN without
confirmed year/make/model is shown as missing model facts; this adapter never decodes a VIN.

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

Selecting providers makes subprocessors statable; actual regions, terms and deployed verification
are still needed to approve `infrastructure-and-privacy-inventory`.

## Selected hosting stack (ticket 99; not provisioned)

| Processor/service | Proposed region | Buyer data and control |
| --- | --- | --- |
| Cloudflare Workers static assets, DNS and edge TLS | Global edge; no residency guarantee established | Client requests, IPs, cookies and API uploads in transit; terminates TLS and can see plaintext uploads. API no-store/cache bypass, no Worker request logging; actual plan/upload limit and processor terms pending |
| AWS EC2 | us-east-1 proposed; operator confirmation pending | Case processing, plaintext during scanning/parsing, app memory; encrypted EBS and loopback-only backend/scanner, origin allowlist and secret verification |
| AWS RDS PostgreSQL | Same selected AWS region; proposed us-east-1 | Case/account graph, keyed identifiers, encrypted storage and seven-day automated backups; private SG, TLS, restore/deletion reconciliation required |
| AWS S3 | Same selected AWS region; proposed us-east-1 | Application-encrypted originals/pages plus SSE-S3; private unversioned bucket, no Object Lock/replication; independent backups and purge latency unverified |
| AWS SES and SNS | Same selected AWS region; proposed us-east-1 | Recipient and plain-text transactional message; signed bounce/complaint feedback, retained keyed-hash suppression; live verification pending ticket 93 |
| AWS SSM Parameter Store/KMS and Secrets Manager | Same selected AWS region; proposed us-east-1 | Runtime credentials, document key ring, pepper, origin secret and TLS key; exact parameter/key grants. RDS master secret service-managed, separate from runtime SQL user |
| AWS ECR and GitHub Actions | ECR in selected AWS region; GitHub runner location not established | Code/container artifacts and deployment credentials, no Buyer documents; workflows manual only, environment-reviewed OIDC/deploy token |

Terraform and operating steps are in [pilot deployment](pilot-deployment.md). No account,
region deployment, processing contract or backup physical-expiry result is claimed. The earlier
operator-supplied database row is superseded by the selected RDS design for the future pilot;
local development remains unchanged. Cloudflare's global edge is a separate processing boundary
from AWS regional storage. Before approval record actual regions, terms, IAM isolation,
upload limit, backup expiry and every deployed check; infrastructure selection alone leaves
`infrastructure-and-privacy-inventory` BLOCKED.

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

# Trade valuation adapter (ticket 101)

`TradeValuationAdapter.value(TradeVehicle, zip)` returns an ordered low/typical/high USD range in
minor units or stated unavailability with a fixed reason. Its domain records carry code-owned source,
provider version, licence metadata, retrieval and observation times, condition assumption and reported
cost. `TradeVehicle.from(TradeService.Trade)` projects only VIN, mileage, condition and optional trim;
payoff, lender, offers and Buyer identifiers do not cross the adapter boundary. ZIP is a structured
five-digit postal code. There is no scraping, URL-fetch operation or platform fallback estimate.

No licensed trade valuation source is integrated; ticket 102 owns that work.
`DeterministicFakeTradeValuationAdapter` performs no I/O and labels its ranges as synthetic development
fixtures. A clock supplies repeatable normal, wide-range, unknown-trim, high-mileage, stale, unavailable,
timed-out and rate-limited scenarios. Missing VIN, mileage or condition produces `MISSING_FIELDS`;
missing trim remains unknown and widens the synthetic fixture range. Observation or retrieval evidence
older than 45 days is unavailable with no range. This development freshness policy must be checked
against a licensed provider's terms and coverage in ticket 102. Every replacement adapter must extend
`TradeValuationAdapterConformance` with recorded fixture scenarios. Provider payload types stay internal.

`TradeValuationProductionGuard` uses the existing `DevelopmentEvidenceAdapter` marker and shared
`DeploymentStage` decision to refuse development or synthetic adapters under `pilot`, `prod`,
`production` or equivalent evidence deployment configuration, naming the adapter at startup.

Ticket 103 can call `TradeValuationService.value(caseId, vehicle, zip)` after authorizing the case and
reading its recorded Trade Vehicle. Each attempt uses `ProviderMeter`'s existing timeout, concurrency
bound and safe failure classification. Case-scoped `TRADE_VALUATION` rows store provider, version,
licence, availability, result count (one range or zero), retrieval time, elapsed latency and cost.
The existing `inventory_availability` database column also holds valuation availability; no migration
or new scope is needed. The synthetic fixture reports zero cost; unknown cost remains null, and a
reported cost is retained even when the boundary rejects stale evidence. VIN, ZIP and payoff are not
stored in usage. Existing case retention and deletion govern these operational rows, and late results only
update existing rows. This ticket adds no endpoint, UI or saved valuation evidence; ticket 103 owns
consumer integration and immutable report evidence.

# Loan pre-approval adapter (ticket 128)

`PreApprovalAdapter.request(LoanApplication, CreditAuthorization)` is the replaceable loan-partner
boundary. The application contains requested USD principal, term, vehicle facts, income and housing;
it has no SSN, date of birth or driver's licence field. Identity entry belongs in the licensed partner's
form. The caller must supply the Buyer's recorded written FCRA authorization version and timestamp.
The service and fake refuse missing or future authorization before invoking the partner. Recording
and loading that authorization, application persistence and the Buyer flow belong to ticket 129.

The result carries code-owned provider/version/licence metadata, retrieval time, reported cost and
explicit per-lender outcomes (`PartnerOutcome`). Only `OFFERED` carries terms: amount, APR, term,
explicit upfront fees, optional lender-stated payment, vehicle age/mileage/down-payment conditions,
expiry, inquiry type, HTTPS continuation link and source reference/observation time. The contract
rejects any non-soft inquiry, expired offer, differing requested principal or unsupported calculation;
the metered boundary drops all offers in such a response and reports a fixed reason. No provider
message becomes a diagnostic. The platform does not accept an offer or sign a loan.

`PreApprovalContract.orderedOffers` uses the existing FIN-01 `FinancingCalculator.partialSchedule`
for standard fixed-rate monthly loans, including zero APR; total loan cost is scheduled payments plus
explicit upfront fees. This is a loan comparison, not a Total Transaction Cost for a verified purchase.
FIN-02 `reconcilePayment` supplies the payment tolerance and difference from lender-stated payment.
Ordering uses computed full-term cost, with lender name as a stable tie-breaker; no compensation input
exists. Different terms are visible and ranked for the same requested principal. Purchase-aligned
outside Finance Offer selection and dealer comparison remain ticket 129.

`DeterministicFakePreApprovalAdapter` is the local default, makes no inquiries or network calls,
reports zero cost and labels results synthetic. Fixed-clock fixtures cover normal, zero APR, payment
mismatch, all lender outcomes, non-soft inquiry, expiry, unavailability, timeout and rate limiting.
Every future partner runs `PreApprovalAdapterConformance` against recorded fixtures. The
`PreApprovalProductionGuard` refuses synthetic/development adapters in pilot, prod, production or
equivalent evidence deployment configuration. No live lender is integrated; that is ticket 130.

`PreApprovalService.request` meters each authorized attempt as `PRE_APPROVAL`, with the existing
timeout and concurrency bounds, either for a case or an account/journey without a case. Callers must
authorize the scope first. Usage contains only scope IDs, code-owned provider metadata, availability,
offer count, retrieval time, latency, status and reported cost (null when unknown, including transport
failures). Rejected response cost is retained. No application facts, authorization text, lender terms,
source references or continuation links are stored in usage. Existing retention/deletion applies;
late results only update existing usage rows. No migration, endpoint or saved application is added.
The existing V40 `provider_usage_scope` constraint still restricts journey operations to inventory
and market retrieval. Journey pre-approval persistence requires the coordinator's shared schema
change to admit `PRE_APPROVAL` (and ticket 125's `INSURANCE_QUOTE`); this ticket leaves migrations
unchanged as assigned. `PreApprovalUsageAcceptanceTest` preserves the failing journey scenario
until that integration dependency is resolved.

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

# Insurance quote adapter (ticket 125)

`InsuranceQuoteAdapter.request(InsuranceApplication, InsuranceQuoteConsent)` returns, per insurer behind the
licensed partner, a quote or a stated `PartnerOutcome` (declined, needs more information, unavailable, timed
out), or partner-level unavailability, time-out or rate limiting with a fixed reason and no substituted quote.
A quote carries the premium in USD minor units, its 6- or 12-month term, the coverage the insurer actually
priced (limits and deductibles per line), quote and expiry times, and the provider's HTTPS link for the Buyer
to continue; the result carries the code-owned partner name, version, licence metadata, retrieval time and
reported cost. Responses with a different provider, future or stale (over one hour) timestamps, an expired
quote, or duplicate insurers are rejected. Nothing is sent without the Buyer's consent, recorded as the
consent text version and agreement time (INS-02).

The application holds rating facts only: drivers' age, years licensed and recent accidents and violations;
the garaging ZIP and state; the vehicle (optional VIN, year, make, model, trim, annual mileage); the chosen
coverage; and optionally the current insurer, coverage and premium. Social Security number, date of birth and
driver's license number are never fields of it (spec section 13); the conformance suite checks this. The case
or journey an application belongs to does not cross the adapter boundary.

`CoverageComparison` treats a quote as the same coverage only when every line, limit and deductible matches the
Buyer's choice, orders those by yearly premium then insurer name, and lists other quotes with their
differences and no rank (INS-03). `YearlyDifference` is the quote's yearly premium minus the Buyer's current
yearly premium for same-coverage quotes, with whether the current policy was entered at the chosen coverage
(INS-04). Order never depends on the partner's order or on anything a provider pays.

No licensed partner is integrated; ticket 127 owns that work. `DeterministicFakeInsuranceQuoteAdapter` is the
default, performs no I/O and labels its quotes as synthetic. Five fixed insurers derive premiums and outcomes
from the application: two quote the chosen coverage (12 and 6 months), one prices different coverage, one
declines a driver with two or more at-fault accidents or three or more violations, and one needs more
information without annual mileage and otherwise times out. A clock and scenario supply repeatable
unavailable, timed-out and rate-limited partner results. Every replacement adapter must extend
`InsuranceQuoteAdapterConformance`. `InsuranceQuoteProductionGuard` refuses development or synthetic adapters
under `pilot`, `prod`, `production` or an equivalent evidence deployment.

Ticket 126 can call `InsuranceQuoteService.request(scope, application, consent)` after authorizing the case or
journey. Each attempt is metered through `ProviderMeter` as `INSURANCE_QUOTE` with the shared timeout,
concurrency bound and failure classification. Usage rows store partner, version, licence, availability, the
number of insurers that quoted, retrieval time, latency and cost; no applicant, vehicle, address, insurer or
premium detail is stored. Case-scoped rows need no migration. Journey-scoped rows set `journey_id` and
`account_id` under the same account then journey locks as inventory search, but the `provider_usage_scope`
check (V40) admits journey rows only for inventory search and market retrieval, so they need ticket 126's
migration to widen it. This ticket adds no endpoint, UI or stored application.
