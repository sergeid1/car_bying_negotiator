# 17 — Expire and delete the complete Buying Case lifecycle

**What to build:** Retention and Buyer-requested deletion revoke access promptly and remove the complete family of original and derived case artifacts through a verifiable lifecycle.

**Blocked by:** 05 — Accept Dealer documents securely; 14 — Review a revised Dealer Offer; 16 — Recover, export, and download a Buying Case.

**Status:** implemented

- [x] Raw Document Artifacts receive a disclosed default retention of no more than 30 days and support a shorter configured Buyer choice.
- [x] Expiration and deletion operations are idempotent and use explicit `DELETION_PENDING` and verified `DELETED` lifecycle states.
- [x] A deletion request immediately invalidates case and recovery access before background removal finishes.
- [x] Deletion covers originals, normalized pages, OCR and extraction artifacts, exports, caches, derived personal data, and provider-held copies where supported.
- [x] Progress, attempts, failures, completion evidence, and truthful backup-expiry metadata are recorded without sensitive content.
- [x] Active-system deletion is measurable against the declared 24-hour p95 objective.
- [x] Deletion fixtures verify both complete removal and safe retry after partial failure.
- [x] Deletion Job state and evidence use JPA repositories, while deletion of the complete case graph uses an explicit, reviewed repository deletion plan; bulk JPQL, native SQL, or JDBC is allowed where measured and documented rather than relying on broad ORM cascades.

Implementation, reviewed deletion plan, measurements and backup limitations: [case deletion policy](../case-deletion-policy.md).

Validation: full Maven suite (92 tests), frontend suite (13 tests), production frontend build, and `git diff --check` passed. The missing-storage/normalized-page review finding was reproduced, corrected and re-reviewed.
