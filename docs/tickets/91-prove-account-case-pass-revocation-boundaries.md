# 91 — Prove account case pass revocation boundaries

**What to build:** Executable replay evidence for the account case pass after detachment,
case-link rotation and its own expiry. `CaseAccessService` signs the case ID, expiry, session hash
and current case-link hash; `AccountCasePassAdmission` requires an active session and current
attachment. `AccountCaseOpeningAcceptanceTest` covers sign-out/session expiry/deletion, foreign
cookies/cases and forgery, but does not exercise these remaining invalidation boundaries. Found
during ticket 78's threat-model evidence review on 2026-10-07; this is a test gap, not evidence
that the intended invalidation currently fails.

**Blocked by:** None; use the existing account/case acceptance harness.

**Status:** ready-for-agent — missing executable evidence for existing admission logic.

- [ ] Issue a pass, remove the attachment through the available lifecycle seam, and verify replay
  fails with the same unavailable response as an unknown case; do not add a new public detach feature.
- [ ] Issue a pass, rotate the case link through verified case recovery, and prove the old pass fails
  with the original active cookie; a newly opened pass and new case link work.
- [ ] Exercise the pass's own expiry while the case/session remain active, including altered expiry
  and malformed pass fields, without sleeping for the default lifetime.
- [ ] Cover account/session substitution and case-isolation behavior on reads and writes using passes;
  preserve the existing case-link route and sign-out semantics.
- [ ] Update the threat model with executable class names; fix any discovered admission defect within
  this ticket and report the behavior change.
