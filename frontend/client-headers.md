# Buyer client response headers

Apply every name/value in [client-headers.json](client-headers.json) as an HTTP response
header when serving the built client, including HTML fallback routes and static assets.
The Vite production preview reads this same definition; the development server does not.
These are deployment headers, not an HTML meta policy: `frame-ancestors` and HSTS require
response headers. See the [release procedure](../docs/hardening-runbook.md#buyer-client-response-headers).

`img-src 'self' blob:` permits the existing accessible document preview images. Page scripts
fetch normalized images using the case credential in a header, then create and revoke local
blob URLs. `connect-src 'self'` restricts those fetches to the site's origin; no third-party
image origin or `data:` image is permitted. Blob permission applies only to images, never
scripts, workers or frames. Only trusted page scripts should create these URLs; CSP does
not protect against a compromised same-origin script.

The policy permits React's CSSOM assignments for source-region outlines and price markers;
it does not permit inline style tags/HTML style attributes, inline scripts or `eval`.
Fonts and the pre-paint `theme-init.js` remain external same-origin assets.
