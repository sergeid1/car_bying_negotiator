# 05 — Accept Dealer documents securely

**What to build:** A Buyer can upload a supported Dealer document and follow its asynchronous processing, while unsafe or unsupported files fail closed with an actionable explanation and retained retryable case state.

**Blocked by:** 03 — Create and securely reopen an ephemeral Buying Case.

**Status:** complete

- [x] PDF, JPEG, PNG, and HEIC uploads within configured file, page, pixel, and resolution limits are accepted.
- [x] File signatures are verified independently of names and claimed content types.
- [x] Executable, encrypted, malformed, oversized, decompression-risk, malware-positive, and unsupported files are rejected before extraction.
- [x] Accepted originals are encrypted outside transactional storage and represented by checksum, lifecycle, retention, and media metadata.
- [x] Page normalization preserves order and flags likely missing, duplicate, rotated, or cropped pages.
- [x] Upload is idempotent, exposes operation stage state, and can retry a failed stage without duplicating its artifact.
- [x] Source Document, page, lifecycle, retention, and processing-operation metadata use JPA entities and repositories; document bytes remain outside PostgreSQL and no ORM mapping can load raw content.

## Security review remediation

The following findings must be resolved before this ticket can be considered complete:

- [x] Enforce retention deadlines. A scheduled lifecycle process must find expired Source Documents, delete every associated original and derived artifact from object storage, and persist deletion progress and final lifecycle state. Processing must be safe to retry, and tests must prove that artifacts do not remain accessible after their deadline.
- [x] Replace the deterministic EICAR-string check as the production malware scanner. Production must use a real malware-scanning service or engine, must not select the test scanner by default, and must fail closed without storing or advancing an upload when the scanner is unavailable, times out, errors, or returns an indeterminate result. Tests may continue to use a deterministic test double.
- [x] Perform real document normalization in an isolated worker. The worker must parse/decode accepted documents, render or otherwise produce normalized pages in order, persist those outputs and their metadata outside transactional storage, and advance to `READY_FOR_EXTRACTION` only after all required outputs are durably written. Corrupt, hostile, resource-exhausting, or partially normalized input must fail closed without exposing downstream extraction to unnormalized content.
- [x] Validate image structure and decodability with the hardened decoder used by the isolated processing environment. PNG/JPEG/HEIC acceptance must not rely only on magic bytes or marker searches; malformed chunk/segment lengths, invalid PNG CRCs, missing image data, corrupt metadata, and undecodable pixels must be rejected with an actionable error. Add regression coverage for a PNG whose IHDR dimensions are changed without updating its CRC.
- [x] Enforce PDF page limits from a validated PDF page tree. Reject a PDF whenever its declared and validated page count exceeds `maxPages`, including files whose textual `/Type /Page` occurrence count is small but whose page-tree `/Count` is larger. Malformed, contradictory, cyclic, or otherwise untrustworthy page trees must fail closed before expensive downstream work. Add regression coverage for a one-page-looking PDF that declares `/Count 1000`.
- [x] Re-check the latest `SERVICE_PROCESSING` consent immediately before reading or persisting multipart content. A Buyer who has revoked or lacks that consent must not be able to upload, reopen an upload flow, store bytes, or dispatch processing. Add coverage for consent revocation between case creation/reopen and upload.
- [x] Make failed-stage retries idempotent. Persist and evaluate each retry `idempotencyKey`, return the existing retry operation for a replay, and dispatch work only after an atomic transition from the retryable failed state. Concurrent or repeated retries while an operation is `NORMALIZING` (or otherwise already running) must not launch duplicate workers or create duplicate artifacts. Add concurrency and replay regression coverage.

Completion requires integration or acceptance tests for the behavior above, including fail-closed scanner outages, malformed documents, retention cleanup, revoked consent, and concurrent retry replay.

## Completion notes — 2026-09-01

- Initial implementation completed on `feature/secure-dealer-document-upload` in commit `16cbc45`; subsequent security review reopened the ticket with the remediation items above.
- Moved the Spring Boot project into `backend/` alongside `frontend/`.
- Added authenticated multipart upload, operation polling, document metadata retrieval, and failed-stage retry endpoints.
- Added signature-based PDF/JPEG/PNG/HEIC inspection, configured safety limits, deterministic malware scanning, actionable fail-closed responses, and page-quality flags.
- Added AES-256-GCM external artifact storage plus Flyway-managed Source Document, page, retention, lifecycle, and processing-operation metadata with no raw-byte ORM mapping.
- Verification passed: the complete backend suite, frontend tests, TypeScript checks, production frontend build, and `git diff --check`.

## Security remediation completion — 2026-09-01

- Added retryable scheduled retention cleanup with persisted original/page deletion progress and a final `DELETED` lifecycle.
- Made ClamAV the default production scanner and confined deterministic EICAR scanning to explicit tests; scanner failures now reject before storage.
- Added parser-backed PDF validation/rendering, hardened PNG/JPEG decoding, configurable HEIC decoding, encrypted normalized page artifacts, and durable output gates before extraction readiness.
- Revalidated current service-processing consent around multipart reads and persistence.
- Added persisted retry idempotency keys and pessimistic operation claims to prevent duplicate workers and artifacts.
- Verification passed: the complete backend test suite and `git diff --check`.
