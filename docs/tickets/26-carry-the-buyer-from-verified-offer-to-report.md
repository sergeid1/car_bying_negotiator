# 26 — Carry the Buyer from a verified offer to the report and back

**What to build:** A frozen Dealer Offer flows into analysis and the report opens automatically, and the Buyer returns to the case through a secure link rather than by typing a case identifier and token into a form.

**Blocked by:** 11 — Generate the core Deal Analysis Report; 16 — Recover, export, and download a Buying Case; 25 — Verify and correct extracted terms, then freeze the Dealer Offer.

**Status:** complete — freezing a Dealer Offer runs analysis on that version and opens the report,
and the Buyer returns through a private case link, as of 2026-09-30, with a real-browser gate at 360 px.
Recovery by email still needs the case link or its case reference; see the notes.

- [x] Analysis runs from the verified offer through the existing market-assessment, cash-economics, and analysis endpoints, with stage progress shown throughout.
- [x] The report opens on completion without the Buyer navigating manually.
- [x] Return access uses the high-entropy case link and the optional recovery email; the manual case-identifier and token entry form is removed from the buyer-facing path.
- [x] Hard-coded case identifiers are removed from the lifecycle and portability panels, which operate on the current case.
- [x] An expired, revoked, or unrecognized link produces an explanation and a recovery path, and never reveals whether a case exists.
- [x] Analysis failure is actionable and retryable without re-uploading accepted documents.
- [x] Automated coverage includes the full path from frozen offer to rendered report, return access from a second browser session, and expired-link handling.

## Implementation notes — 2026-09-30

### From freeze to report

`VerifiedOfferAnalysis` takes the version ticket 25's verification view reports when the Buyer freezes
an offer (`App` ties it to the case it was frozen in). It refuses to analyze a different version than
the one requested, runs market assessment, cash economics (skipped for dealer financing) and analysis
with stage progress, and focuses the report on completion. A failure offers a retry that keeps the
analysis idempotency key after transport or report-loading failures and never touches documents.
`AppOfferToReport.test.tsx` covers the hand-off from the freeze callback to the rendered report.

### Returning does not regenerate the report

The backend creates a new report version for every analysis request with a new idempotency key, and for
revised offers it also appends a deal review. Reopening a case therefore reuses the latest report when
its `evidence.dealerOffer.id` is the current verified offer, and only runs analysis again when the
Buyer asks with "Update analysis with saved terms".

### Return access

The manual Case ID and token form is gone. The Buyer saves or copies a private link that carries the
existing high-entropy credential in the URL fragment, which is cleared from the address bar before the
case is validated; it never goes into a query string or browser storage. Expired, revoked and
unrecognized links all get the same explanation and recovery path, so the response never reveals
whether a case exists.

### Known limit: email recovery needs the case reference

`POST /recovery/requests` needs the case reference and the enrolled email. The client derives the
reference from the current, failed, or pasted saved link, so a Buyer who has lost the link entirely
cannot request a new recovery email. Removing that limit needs an email-only request that does not
leak which addresses have cases, which is a backend change outside this ticket.

### Coverage note

The browser tests for the second-session return and expired links stub the API. Server-side access,
expiry and revocation are covered by the existing backend acceptance tests.
