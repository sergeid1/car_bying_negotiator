# 79 — Ship a content security policy for the Buyer client

**What to build:** The browser enforces what the threat model assumes. Case links carry a bearer credential in the URL fragment and case tokens live in page memory, so the threat model requires "no third-party scripts on credential-bearing pages". Nothing enforces it: the client has no content security policy, no frame-ancestors rule and no HSTS, and the repository defines no production configuration for serving the client. Found in the 2026-10-05 codebase security review.

**Blocked by:** none.

**Status:** ready-for-agent — filed on 2026-10-05 from a codebase security review.

- [ ] The built client ships a strict content security policy: scripts, styles, fonts, images and connections only from the site's own origin, no inline script or `eval`, `object-src 'none'`, `base-uri 'none'` and `frame-ancestors 'none'`. `theme-init.js` and any inline style the themes need are made to fit the policy rather than weakening it.
- [ ] The repository defines the response headers the client must be served with (CSP, `Strict-Transport-Security`, `X-Content-Type-Options`, `Referrer-Policy: no-referrer`, a restrictive `Permissions-Policy`) in one place that a deployment can apply, and the hardening runbook points to it. Headers the API already sets are not duplicated or contradicted.
- [ ] A build check fails when the built `index.html` or bundle references a script, stylesheet or connection outside the site's own origin.
- [ ] A Playwright test loads the built client with the policy applied and fails on any CSP violation across the main Buyer paths, in both themes.
- [ ] Development with the Vite dev server keeps working.
