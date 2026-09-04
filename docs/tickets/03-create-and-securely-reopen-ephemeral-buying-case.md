# 03 — Create and securely reopen an ephemeral Buying Case

**What to build:** A Buyer can create and retrieve an account-free Buying Case through a high-entropy link, provide the minimum starting facts, and control separate service, research, and human-review consent.

**Blocked by:** 01 — Prove the synthetic cash-deal walking skeleton.

**Status:** complete

- [x] Creating a case requires buyer jurisdiction, dealer location, and cash or finance purchase method, while Buyer limits remain optional.
- [x] Case access uses an unguessable secret with at least 128 bits of entropy and stores only the protected form needed for verification.
- [x] Invalid or expired case credentials reveal no Buying Case data.
- [x] Service processing, human review, and anonymized research have separate versioned consent records with grant and revocation history.
- [x] Research or human-review consent is not required to use the core service.
- [x] Repeating case creation with the same idempotency key returns the existing case.
- [x] Spring Data JPA with Hibernate becomes the default persistence mechanism for transactional metadata; the walking-skeleton Buying Case, Operation, and Report records are migrated behind JPA repositories without losing restart recovery, idempotency, or immutable report history.
- [x] Flyway remains the only production schema-authoring mechanism, Hibernate schema generation is disabled, and startup validates entity mappings against the migrated schema.
- [x] JPA entities remain persistence-internal and are not exposed as controller requests, responses, provider contracts, or report-schema types.
- [x] Aggregate ownership, fetch behavior, cascade behavior, optimistic or pessimistic locking, and transaction boundaries are explicit; direct JDBC or native SQL requires a documented locking, bulk, append-only, or measured performance reason.

## Completion notes — 2026-08-31

- Implemented on `feature/secure-ephemeral-buying-case` in commit `550f004`.
- Review fixes completed in commit `9ad85db`.
- Verification passed: 22 backend tests, the frontend test suite, TypeScript checks, the production frontend build, and `git diff --check`.
- Standards and Spec reviews completed against `main` (`006db0c`).
- Pull request: [#1 — Create and securely reopen ephemeral buying cases](https://github.com/sergeid1/car_bying_negotiator/pull/1).

## Review fixes completed

- [x] Prevent unauthenticated idempotency-key replay from reproducing or returning an existing case's access token. An idempotency key may be human-readable and must not become the effective case credential. Enforce adequate access-secret configuration and retain idempotent creation without disclosing the credential to an unauthorized replay.
- [x] Centralize case-access verification and fail-closed not-found behavior while fixing credential replay so ephemeral and synthetic case paths apply one authorization policy.
- [x] Persist new ephemeral Buying Cases with the canonical `DRAFT` status instead of the unsupported `CREATED` status.
- [x] Record the actor for every initial consent, grant, decline, and revocation, and carry it through the Flyway schema, JPA entity, persistence API, and returned consent history. Include the required source and schema metadata for the material consent record.
