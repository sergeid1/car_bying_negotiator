# 04 — Establish Vehicle and Listing evidence

**What to build:** A Buyer can establish the Vehicle from VIN or confirmed manual facts, preserve supported listing evidence, fall back safely from unsupported listing retrieval, and resolve material identity conflicts.

**Blocked by:** 03 — Create and securely reopen an ephemeral Buying Case.

**Status:** complete

- [x] A Buyer can enter a VIN or the supported structured manual Vehicle facts and confirm the selected Vehicle.
- [x] Applicable VINs are checksum-validated and invalid values receive actionable feedback.
- [x] Supported provider facts are normalized behind an adapter and retain source, retrieval time, provider version, and license metadata.
- [x] A retrieved listing becomes an immutable Listing Snapshot rather than being presented as current truth.
- [x] Unsupported listing retrieval falls back to VIN or manual entry without hidden scraping.
- [x] Material conflicts remain visible and a VIN mismatch prevents reliable analysis until the Buyer resolves it.
- [x] Vehicle, Dealer, and immutable Listing Snapshot metadata are mapped as persistence-internal JPA entities and accessed through module-owned Spring Data repositories without exposing provider payloads or creating a cross-module entity graph.

## Completion notes — 2026-08-31

- Implemented on `feature/vehicle-listing-evidence`.
- Added authenticated create/read vehicle-evidence endpoints and a conflict-resolution endpoint for
  keeping the Buyer-confirmed vehicle or selecting the snapshotted listing vehicle.
- Added ISO 3779/NHTSA-style VIN checksum validation, structured manual facts, explicit unsupported
  listing fallback, normalized deterministic provider evidence, provenance, and immutable snapshots.
- Added Flyway-managed, persistence-internal Vehicle, Dealer, Listing Snapshot, and identity-conflict
  JPA mappings with module-owned Spring Data repositories and scalar references instead of ORM graphs.
- Acceptance coverage exercises VIN and manual confirmation, actionable validation, supported and
  unsupported listing paths, both VIN-conflict resolutions, and restart durability.
- Verification passed: 30 backend tests, the frontend test, TypeScript checks, the production
  frontend build, and `git diff --check`.
