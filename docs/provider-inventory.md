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
| Deal-terms extraction | `DeterministicDealerDocumentProvider` | In process | None — no model provider is called |
| Document parsing | Confined local container or disposable JVM | In process | None |
| Malware scanning | `ClamAvMalwareScanner` against an operator-run daemon | Document bytes | Operator-run; not a third party |
| Object storage | `EncryptedFileDocumentStorage` on an operator-supplied path | Encrypted originals | Operator-run |
| Database | PostgreSQL supplied by the operator | Case graph | Operator-run |
| Postal-code location | `PostalJurisdictionResolver` over bundled Census reference data | In process | None — the dataset ships with the application and is never queried remotely |
| Recovery email | `SmtpRecoveryEmail`, opt-in and unconfigured by default | Buyer email address | Operator's SMTP provider when enabled |
| Human review | Internal queue behind separate consent | Redacted fields | None |
| Model calls | `AnthropicModelGateway` or `OpenAiModelGateway` through the [AI execution harness](ai-execution-harness.md), one per deployment (`ai.model.provider`); disabled (`ai.model.enabled=false`) | Not while disabled; would send normalized page images and recognized text | Anthropic or OpenAI, once enabled — **terms unexecuted for both** |

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
