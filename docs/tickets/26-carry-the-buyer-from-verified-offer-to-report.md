# 26 — Carry the Buyer from a verified offer to the report and back

**What to build:** A frozen Dealer Offer flows into analysis and the report opens automatically, and the Buyer returns to the case through a secure link rather than by typing a case identifier and token into a form.

**Blocked by:** 11 — Generate the core Deal Analysis Report; 16 — Recover, export, and download a Buying Case; 25 — Verify and correct extracted terms, then freeze the Dealer Offer.

**Status:** ready-for-agent

- [ ] Analysis runs from the verified offer through the existing market-assessment, cash-economics, and analysis endpoints, with stage progress shown throughout.
- [ ] The report opens on completion without the Buyer navigating manually.
- [ ] Return access uses the high-entropy case link and the optional recovery email; the manual case-identifier and token entry form is removed from the buyer-facing path.
- [ ] Hard-coded case identifiers are removed from the lifecycle and portability panels, which operate on the current case.
- [ ] An expired, revoked, or unrecognized link produces an explanation and a recovery path, and never reveals whether a case exists.
- [ ] Analysis failure is actionable and retryable without re-uploading accepted documents.
- [ ] Automated coverage includes the full path from frozen offer to rendered report, return access from a second browser session, and expired-link handling.
