# 24 — Upload Dealer paperwork from a phone and follow processing

**What to build:** A Buyer standing at a dealership can photograph or attach the Dealer's paperwork from the main interface and watch it process, using the document intake that already exists in the backend but is unreachable from the UI.

**Blocked by:** 05 — Accept Dealer documents securely; 23 — Start a real Buying Case from Buyer-supplied vehicle and location.

**Status:** ready-for-agent

- [ ] The case view offers a file control that accepts PDF, JPEG, PNG, and HEIC, supports multiple files and multi-page documents, and opens the camera on mobile browsers.
- [ ] Uploads post to `POST /api/v1/cases/{caseId}/documents` as multipart content; the browser performs no document parsing and the server remains the sole authority on acceptance.
- [ ] Client-side pre-checks mirror the configured file, page, and pixel limits to give fast feedback, and never accept a file the server would reject.
- [ ] Processing stage is shown from operation polling with labels the Buyer can act on, including a terminal failed state.
- [ ] Every rejection class raised by the intake exception handler maps to an actionable message naming the file and what to do next.
- [ ] A failed stage retries through `POST /api/v1/operations/{operationId}/retries` with a stable idempotency key and does not duplicate artifacts on repeated taps.
- [ ] Page-quality flags for likely missing, duplicate, rotated, or cropped pages are shown, with a path to add or replace pages before extraction.
- [ ] Document bytes are never written to browser storage, query strings, or client logs.
- [ ] Automated coverage includes acceptance, each rejection class, retry replay, polling to both terminal states, and upload on small-viewport layouts.
