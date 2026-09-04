# 07 — Verify Deal Terms and create an immutable Dealer Offer

**What to build:** The Buyer can compare material extracted terms with their evidence, confirm or correct them, resolve Vehicle identity conflicts, and create an immutable verified Dealer Offer.

**Blocked by:** 04 — Establish Vehicle and Listing evidence; 06 — Extract Deal Terms with field-level evidence.

**Status:** complete

- [x] The review experience shows original wording, normalized category and value, confidence, and page or region evidence for every material field.
- [x] All financially material values require Buyer confirmation, including every material value below the configured confidence threshold.
- [x] A correction preserves the candidate value and source evidence while appending a versioned correction and audit event.
- [x] Unfamiliar line items remain `UNKNOWN` and prompt a neutral clarification action.
- [x] Conflicting listing, document, and Buyer-confirmed Vehicle identity blocks Dealer Offer verification until resolved.
- [x] Completing verification creates an immutable Dealer Offer and moves the Buying Case to `READY_TO_ANALYZE`.
- [x] Repeating a correction or verification request with the same idempotency key creates no duplicate version.
- [x] Corrections, audit events, verification state, and immutable Dealer Offer versions are appended through JPA repositories in one declared transaction, with database constraints enforcing version and idempotency uniqueness.

## Completion notes — 2026-09-02

- Added authenticated correction and Dealer Offer verification endpoints with semantic validation and deterministic idempotency fingerprints.
- Preserved source candidates and evidence while exposing append-only correction history and current `CORRECTED`/`VERIFIED` review state.
- Required confirmation of financial and configured low-confidence terms, retained unknown categories with a neutral clarification action, and blocked listing/document/Buyer Vehicle conflicts.
- Added immutable, versioned Dealer Offers plus append-only term verifications and audit events in Flyway-managed JPA persistence with uniqueness constraints.
- Verification passed: complete backend suite, frontend test and production build, and `git diff --check`.
