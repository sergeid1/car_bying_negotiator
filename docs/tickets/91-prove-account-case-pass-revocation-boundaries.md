# 91 — Prove account case pass revocation boundaries

**What to build:** Executable replay evidence for the account case pass after detachment,
case-link rotation and its own expiry. `CaseAccessService` signs the case ID, expiry, session hash
and current case-link hash; `AccountCasePassAdmission` requires an active session and current
attachment. `AccountCaseOpeningAcceptanceTest` covers sign-out/session expiry/deletion, foreign
cookies/cases and forgery, but does not exercise these remaining invalidation boundaries. Found
during ticket 78's threat-model evidence review on 2026-10-07; this is a test gap, not evidence
that the intended invalidation currently fails.

**Blocked by:** None; use the existing account/case acceptance harness.

**Status:** complete — no migration, as of 2026-10-07. `AccountCaseOpeningAcceptanceTest` proves an account case pass
stops working after the case's attachment is removed, after verified recovery rotates the case link and at its own
expiry (zero lifetime), while the session and case stay active; a fresh pass and the rotated link work. Another
session, account or case, and altered or malformed fields, fail on reads and writes with the unknown-case 404. One
defect found and fixed: an out-of-range expiry made `CaseAccessService` throw instead of refusing; it now returns the
same 404. Detachment is tested at the persistence seam; there is still no public detach feature.

- [x] Issue a pass, remove the attachment through the available lifecycle seam, and verify replay
  fails with the same unavailable response as an unknown case; do not add a new public detach feature.
- [x] Issue a pass, rotate the case link through verified case recovery, and prove the old pass fails
  with the original active cookie; a newly opened pass and new case link work.
- [x] Exercise the pass's own expiry while the case/session remain active, including altered expiry
  and malformed pass fields, without sleeping for the default lifetime.
- [x] Cover account/session substitution and case-isolation behavior on reads and writes using passes;
  preserve the existing case-link route and sign-out semantics.
- [x] Update the threat model with executable class names; fix any discovered admission defect within
  this ticket and report the behavior change.
