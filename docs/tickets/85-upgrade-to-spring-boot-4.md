# 85 — Upgrade to Spring Boot 4

**What to build:** The backend runs on a Spring Framework release that receives public security fixes. Spring
Boot 3.5.16 ships Spring Framework 6.2.19, which is affected by CVE-2026-47884 (remote code execution through
`XsltView`); no 6.2.x fix is published, and the only fix is Spring Framework 7.0.9. The application does not use
`XsltView`, so the finding is accepted in `.trivyignore` until 2026-12-31 and guarded by the Semgrep rule
`no-xslt-view`. This ticket removes the need for that exception. Filed on 2026-10-06 when the advisory failed the
`scans` job on PR #53.

**Blocked by:** none.

**Status:** ready-for-agent — filed on 2026-10-06 from a CI security scan.

- [ ] The backend builds on Spring Boot 4 with Spring Framework 7.0.9 or later, and every dependency that Boot no
  longer manages is pinned to a supported release.
- [ ] Code, configuration and tests are migrated to the Boot 4 and Framework 7 changes, with no behaviour change visible
  to a Buyer or to the API: the endpoint list, the response headers, error bodies and the case-isolation inventory are
  unchanged.
- [ ] The `CVE-2026-47884` entry is removed from `.trivyignore`, and `scripts/security-checks.sh` passes with no
  exception for it. The `no-xslt-view` Semgrep rule stays.
- [ ] The release image and the confined parser image build and pass their CI checks.
- [ ] `mvn clean verify`, `npm test`, `npm run build` and `npx playwright test` pass.
