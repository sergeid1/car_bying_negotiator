# Provider and subprocessor inventory

This inventory supports the `provider-terms` and `infrastructure-and-privacy-inventory`
prerequisites in [the pilot readiness record](pilot-readiness-record.md). Both are currently
**blocked**, and this document records why.

## Summary

No external commercial provider is integrated. Every adapter the product depends on is a
deterministic local implementation, a self-hosted component, or an opt-in connection to
infrastructure the operator supplies. There is therefore **no subprocessor, no processing
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
| Recovery email | `SmtpRecoveryEmail`, opt-in and unconfigured by default | Buyer email address | Operator's SMTP provider when enabled |
| Human review | Internal queue behind separate consent | Redacted fields | None |

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
  model provider is called. This becomes a contractual requirement the moment one is.

## What must be obtained before a public pilot

For each automotive or processing provider actually selected, written terms must cover consumer
display, caching, attribution, derived values, retention, deletion, training use, regional
processing, coverage, and cost. Until a provider is selected and its terms are executed, the
`provider-terms` prerequisite stays blocked and this table stays hypothetical.

Selecting hosting, object storage, queue, mail, and model providers also completes the
`infrastructure-and-privacy-inventory` prerequisite by making subprocessors and regions statable.
