# 79 — Ship a content security policy for the Buyer client

**What to build:** The browser enforces what the threat model assumes. Case links carry a bearer credential in the URL fragment and case tokens live in page memory, so the threat model requires "no third-party scripts on credential-bearing pages". Nothing enforces it: the client has no content security policy, no frame-ancestors rule and no HSTS, and the repository defines no production configuration for serving the client. Found in the 2026-10-05 codebase security review.

**Blocked by:** none.

**Status:** complete — the client header set is `frontend/client-headers.json` with its rationale in
`frontend/client-headers.md`, as of 2026-10-06. The policy is default-deny with same-origin scripts, styles, fonts and
connections, `img-src 'self' blob:` for the authenticated document previews the page builds from its own fetches,
`object-src 'none'`, `base-uri 'none'`, `frame-ancestors 'none'` and `form-action 'self'`, plus HSTS, `nosniff`,
`Referrer-Policy: no-referrer` and a restrictive `Permissions-Policy`. `vite preview` serves the same map, so every
Playwright spec runs under it; `client-content-security-policy.spec.ts` fails on any violation across intake, upload,
verification, report, negotiation, downloads, recovery and theme reload in both themes. `npm run build` runs
`scripts/check-client-security.mjs` on the built output. The dev server is unchanged. Open: a deployment must apply
the map at its HTTPS static host, including fallback routes; HSTS can only be checked there.

- [x] The built client ships a strict content security policy: scripts, styles, fonts, images and connections only from the site's own origin, no inline script or `eval`, `object-src 'none'`, `base-uri 'none'` and `frame-ancestors 'none'`. `theme-init.js` and any inline style the themes need are made to fit the policy rather than weakening it.
- [x] The repository defines the response headers the client must be served with (CSP, `Strict-Transport-Security`, `X-Content-Type-Options`, `Referrer-Policy: no-referrer`, a restrictive `Permissions-Policy`) in one place that a deployment can apply, and the hardening runbook points to it. Headers the API already sets are not duplicated or contradicted.
- [x] A build check fails when the built `index.html` or bundle references a script, stylesheet or connection outside the site's own origin.
- [x] A Playwright test loads the built client with the policy applied and fails on any CSP violation across the main Buyer paths, in both themes.
- [x] Development with the Vite dev server keeps working.
