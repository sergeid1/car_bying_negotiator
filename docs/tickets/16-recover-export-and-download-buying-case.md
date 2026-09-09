# 16 — Recover, export, and download a Buying Case

**What to build:** A Buyer can recover case access when requested, export the case's retained data, and download a PDF that presents the same immutable report payload as the accessible HTML report.

**Blocked by:** 11 — Generate the core Deal Analysis Report.

**Status:** implemented

- [x] A Buyer may opt into email recovery or delivery without making email mandatory for analysis.
- [x] Recovery uses an expiring single-use link and reveals no case data before successful verification.
- [x] Export is idempotent and includes the Buyer's retained case data and material version history in an understandable form.
- [x] PDF generation is idempotent and renders the same immutable report payload used by HTML without recalculating outcomes.
- [x] Exported HTML and PDF retain essential evidence references, labels, limitations, and readable mobile or printed layout.
- [x] Failed recovery, export, or rendering preserves the case and supports a safe retry without duplicate report versions.
- [x] Recovery tokens, delivery requests, and export jobs use JPA repositories with expiry and single-use updates protected by database constraints or locking; export assembly reads explicit projections without exposing entities.

Implementation and verification: [case portability policy](../case-portability-policy.md).
