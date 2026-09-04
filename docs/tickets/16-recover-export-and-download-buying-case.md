# 16 — Recover, export, and download a Buying Case

**What to build:** A Buyer can recover case access when requested, export the case's retained data, and download a PDF that presents the same immutable report payload as the accessible HTML report.

**Blocked by:** 11 — Generate the core Deal Analysis Report.

**Status:** ready-for-agent

- [ ] A Buyer may opt into email recovery or delivery without making email mandatory for analysis.
- [ ] Recovery uses an expiring single-use link and reveals no case data before successful verification.
- [ ] Export is idempotent and includes the Buyer's retained case data and material version history in an understandable form.
- [ ] PDF generation is idempotent and renders the same immutable report payload used by HTML without recalculating outcomes.
- [ ] Exported HTML and PDF retain essential evidence references, labels, limitations, and readable mobile or printed layout.
- [ ] Failed recovery, export, or rendering preserves the case and supports a safe retry without duplicate report versions.
- [ ] Recovery tokens, delivery requests, and export jobs use JPA repositories with expiry and single-use updates protected by database constraints or locking; export assembly reads explicit projections without exposing entities.
