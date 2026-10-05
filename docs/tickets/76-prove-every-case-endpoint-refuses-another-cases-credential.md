# 76 — Prove every case endpoint refuses another case's credential

**What to build:** Case isolation becomes a property the build checks rather than a review habit. About 90 endpoints take a `Case-Access-Token`, and each service enforces it with its own `require…` call. The threat model's mitigation for case substitution is to "re-run every new endpoint with another case's token and object IDs", which today is done by hand per ticket. A test lists every registered endpoint, the way `OneTimePurchaseAcceptanceTest` already does for the paid report gate, and fails when a case-scoped endpoint accepts a missing token, a wrong token or another case's token, or when a new endpoint is not yet registered in the test. Found in the 2026-10-05 codebase security review.

**Blocked by:** none.

**Status:** ready-for-agent — filed on 2026-10-05 from a codebase security review.

- [ ] The test reads every mapping from `RequestMappingHandlerMapping` and classifies it as case-scoped (has `{caseId}` or takes `Case-Access-Token`), account-scoped, operator-scoped or public. An unclassified endpoint fails the test with its path, so a new endpoint cannot ship without a decision.
- [ ] Every case-scoped endpoint is called with no token, a malformed token, an expired token, and a valid token for a second case. Each call is refused with the same generic response as an unknown case, and no response body, header or timing branch reveals whether the case exists.
- [ ] Endpoints that also take nested object IDs (documents, reports, operations, relay messages, quotes, appointments) are called with the right case token but another case's object ID, and are refused the same way.
- [ ] Account-scoped endpoints are called with another account's session and with no session; operator endpoints are called without and with a wrong operator token.
- [ ] The public endpoints (case creation, recovery, sign-in, dealer opt-out) are listed explicitly with a one-line reason each.
- [ ] Any endpoint the test finds that does not refuse is fixed in this ticket, and the finding is recorded in the status line.
- [ ] The threat model's case-substitution row names this test as its evidence in place of the manual re-run obligation.
