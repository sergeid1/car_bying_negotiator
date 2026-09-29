# 24 — Upload Dealer paperwork from a phone and follow processing

**What to build:** A Buyer standing at a dealership can photograph or attach the Dealer's paperwork from the main interface and watch it process, using the document intake that already exists in the backend but is unreachable from the UI.

**Blocked by:** 05 — Accept Dealer documents securely; 23 — Start a real Buying Case from Buyer-supplied vehicle and location.

**Status:** complete — the case view uploads Dealer paperwork, follows processing, and retries a
failed stage as of 2026-09-29, with a real-browser gate at 360 px. Page replacement is limited to
adding a corrected document, because intake has no page-replacement endpoint; see the notes.

- [x] The case view offers a file control that accepts PDF, JPEG, PNG, and HEIC, supports multiple files and multi-page documents, and opens the camera on mobile browsers.
- [x] Uploads post to `POST /api/v1/cases/{caseId}/documents` as multipart content; the browser performs no document parsing and the server remains the sole authority on acceptance.
- [x] Client-side pre-checks mirror the configured file, page, and pixel limits to give fast feedback, and never accept a file the server would reject.
- [x] Processing stage is shown from operation polling with labels the Buyer can act on, including a terminal failed state.
- [x] Every rejection class raised by the intake exception handler maps to an actionable message naming the file and what to do next.
- [x] A failed stage retries through `POST /api/v1/operations/{operationId}/retries` with a stable idempotency key and does not duplicate artifacts on repeated taps.
- [x] Page-quality flags for likely missing, duplicate, rotated, or cropped pages are shown, with a path to add or replace pages before extraction.
- [x] Document bytes are never written to browser storage, query strings, or client logs.
- [x] Automated coverage includes acceptance, each rejection class, retry replay, polling to both terminal states, and upload on small-viewport layouts.

## Implementation notes — 2026-09-29

### The client learns the limits instead of guessing them

`GET /api/v1/document-limits` publishes `acceptedMediaTypes`, `maxFileBytes`, `maxPages`,
`maxPixels`, `minWidth` and `minHeight` from the same `document.limits.*` configuration intake
enforces. Without it a pre-check would have to hard-code thresholds that drift from the service and
start refusing files the service would have accepted, or accepting files it will reject.

`maxFileBytes` is the smaller of `document.limits.max-file-bytes` and
`spring.servlet.multipart.max-file-size`, because a request over the multipart ceiling is refused by
the container before intake sees it. `publishedUploadLimitsAreTheLimitsIntakeEnforces` proves the
published numbers are the enforced ones by uploading one byte and one pixel past each of them and
getting `FILE_TOO_LARGE`, `PIXEL_LIMIT_EXCEEDED` and `RESOLUTION_TOO_LOW` back.

### What a pre-check can honestly check

Two acceptance criteria pull against each other: pre-checks are to mirror the file, page and pixel
limits, and the browser is to parse no documents. The split is:

| Limit | Pre-checked | How |
| --- | --- | --- |
| File size, empty file | Yes | `File.size`, no bytes read |
| Accepted type | Yes | The reported MIME type or the filename extension |
| Pixel count, minimum resolution | Yes, for images | `createImageBitmap` for dimensions only |
| Page count | No | Counting PDF pages means parsing the PDF |

So the page limit is displayed and left to the service, and `PAGE_LIMIT_EXCEEDED` is mapped to
"split it and add each part as its own document". A pre-check only ever refuses on evidence: a file
with neither a reported type nor a known extension, or a photo this browser cannot decode — HEIC in
most desktop browsers — is sent for the service to judge rather than refused locally. The `accept`
attribute is treated as a hint, not a gate, because file managers let it be overridden; the test
that covers an unsupported file passes `applyAccept: false` for exactly that reason.

### Every rejection class has guidance, checked against the backend

`refusalGuidance` holds one actionable sentence per code, naming the file and the next step. A test
extracts the codes from the Java sources rather than restating them: the `InvalidDocumentException`
constructions across `com.dealanalyzer.document`, the allowlist `DocumentParserProcess.rejected`
narrows an untrusted worker code to, and `DocumentIntakeExceptionHandler`'s own `FILE_TOO_LARGE`.
It also covers the codes a failed operation carries back through polling — `NORMALIZATION_FAILED`,
`EXTRACTION_FAILED`, `DOCUMENT_EXPIRED` and the `ProviderMeter.Status` failures — so adding a
rejection class on the server fails the client suite until a Buyer-facing sentence exists for it.
The service's own `detail` is a fallback for an unrecognized code, not the normal path.

### A retry key derived from the failure being retried

The retry idempotency key is `retry-{operationId}-{the failed operation's updatedAt}`. Repeated taps
against one failure replay the same key, which `DocumentIntakePersistence.retry` answers without
dispatching or writing a second artifact; a later, distinct failure carries a new `updatedAt` and so
a new key, which dispatches. A key of `retry-{operationId}-{retryCount}` would have looked stable
and quietly broken the second retry, because `retryCount` does not change when a retried stage fails
again. The button is also disabled while a retry is in flight.

The upload key is derived the same way, from the file rather than the attempt:
`upload-{size}-{lastModified}-{name}`. Adding the same paperwork again after a dropped connection
then replays the first upload instead of storing a second copy of it, which is the likely outcome of
a Buyer re-tapping when a phone connection stalls. A same-key-different-content collision is
answered by intake with a 409, and the client resends once under a fresh random key, which cannot
duplicate a file whose content differs from what is stored.

### Polling

Stages come from `GET /api/v1/operations/{id}`, which serves the document operation, with a label
per stage — waiting, preparing the pages, reading the deal terms, terms read, failed — and a
progress meter. A poll answered with a 4xx stops following that document and explains what to do,
since a refusal will not resolve itself; a 5xx or a dropped request is retried by the next poll,
which is the common case on a dealership connection.

### Page-quality flags

The upload reply already carries the per-page `likelyMissing`, `likelyDuplicate`, `likelyRotated`
and `likelyCropped` flags, so they are shown before extraction rather than after. "Add or replace
pages" focuses and opens the file control with a note naming the document.

### Not done here

- **Replacing pages replaces nothing.** Intake has no endpoint to remove or substitute a page or a
  document, so a corrected copy is added as another document and the flagged one stays in the case.
  The note says so rather than implying the old pages are gone. A real replacement belongs with
  verification in ticket 25, which decides which document's terms are used.
- The panel is labelled "Step 2 of 3" to match the intake form's "Step 1 of 3". Verification
  (ticket 25) is not built, so nothing yet moves the Buyer from terms-read to verifying them.
- Nothing shows aggregate progress across several documents; each document reports for itself.
