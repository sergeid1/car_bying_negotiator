# Buying Case recovery and downloads

Email remains optional. Analysis and downloads work without configuring an email address.
An authorized Buyer can save a recovery address and request an access link. Someone who has
lost the access token can request another link using their Case ID and that address.

Recovery links carry a random secret in the URL fragment, which the browser removes on load.
Opening the page does not consume the link: the Buyer must select **Verify recovery link**.
The API accepts the secret in a POST body and reveals a new case credential only after
verification. Links expire after 15 minutes, or at the existing case access deadline if sooner.
Verification consumes the link under a database lock and rotates the case access credential;
it never extends the case lifetime. Recovery token hashes are domain-separated HMACs.
Requests for unknown cases or addresses return the same acknowledgement as matching requests.
Responses containing credentials or downloads disable caching.

Configure SES delivery using `SES_REGION`, `SES_SMTP_HOST`, `SES_SMTP_PORT` (default 587),
`SES_SMTP_USERNAME`, `SES_SMTP_PASSWORD`, and `SES_SNS_TOPIC_ARNS`, as described in the
[hardening runbook](hardening-runbook.md#amazon-ses-transactional-email-ticket-97). Inject SMTP
credentials from the deployment secret store. Set `CASE_RECOVERY_FROM` to the verified company
sender and `CASE_RECOVERY_PUBLIC_URL` to the deployed frontend's HTTPS URL, without a query or
fragment. SMTP authentication and STARTTLS (enabled and required) default to true, controlled by
`SES_SMTP_AUTH` and `SES_SMTP_STARTTLS`. Connection, read and write timeouts are five seconds.

The standard Spring environment variables `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`,
`SPRING_MAIL_USERNAME`, and `SPRING_MAIL_PASSWORD` remain supported: a directly supplied
`SPRING_MAIL_*` value overrides the corresponding `SES_SMTP_*` alias in application properties
when both are set. Direct Spring SMTP-property environment overrides likewise take precedence
over the auth/TLS settings in the files. Prefer one configuration family to avoid ambiguity.
The explicit development profile instead defaults to loopback Mailpit on port 1025 and turns
auth and both STARTTLS properties off; see the [local account flow](buyer-account-policy.md#try-it-locally).
The localhost public URL is only a development default. An unconfigured mail transport fails
the authorized opt-in request without changing case access. Suppressed recipients are silently
skipped with the same acknowledgement as other recipients.

Delivery requests and recovery tokens commit together after SMTP acceptance. A delivery
failure rolls back enrollment and issuance, allowing retry with the same idempotency key.
A lost SMTP acknowledgement can result in a duplicate email on retry; an uncommitted link
cannot verify. Delivery retries never create report versions. No recovery secret is logged
or persisted in plaintext. Requesting another link uses a new idempotency key.

## Public API

| Operation | Request |
| --- | --- |
| Opt into recovery and receive a link | `POST /api/v1/cases/{id}/recovery-email`, body `{ "email": "buyer@example.com" }`, case token and idempotency key headers |
| Request recovery | `POST /api/v1/recovery/requests`, body `{ "caseId": "…", "email": "buyer@example.com" }`, idempotency key header |
| Verify link | `POST /api/v1/recovery/verify`, body `{ "token": "…" }` |
| Create frozen export | `POST /api/v1/cases/{id}/exports`, body `{ "format": "ZIP", "reportVersion": 0 }`, case token and idempotency key headers |
| Create report download | Same endpoint with `HTML` or `PDF` and a positive saved report version |
| Download | `GET /api/v1/cases/{id}/exports/{exportId}`, case token header |

Exports use a case lock and unique `(case_id, request_key)` constraint. Reusing a key returns
the same job and bytes; changing its format or report version returns a conflict. There is
no analysis or regeneration call in the download path. Failed assembly/rendering rolls back
the export job, leaving case and report versions intact. Retry the same request key after
restoring availability. Expired jobs require a new key; the UI offers **Start a new export**.
Jobs are downloadable for at most 24 hours and never beyond case access or the earliest
included document retention deadline. Physical expiry/deletion follows the [complete case lifecycle policy](case-deletion-policy.md).

Archives contain:

- `case-data.json`: explicit named scalar projections of retained case records, consent history,
  evidence, extractions, corrections, verifications, offers, economics, market evidence, plans,
  approved baselines, financing, reviews, negotiation versions, stored report explanations (with the
  path and model versions that produced them), reports and recovery delivery metadata.
  Listing captures (ticket 115) are included: `listing_capture_readings` with each field read and its
  evidence region, and `listing_snapshots` with the confirmed ad's price, `captured_on`,
  `price_conditions`, `source_document_id` and `field_confirmations`; the capture itself is a
  `source_documents` row with `purpose = 'LISTING_CAPTURE'`.
- `reports/report-N.html`: each immutable saved report, including labels, limitations and evidence.
- `documents/`: retained active originals named by document ID and normalized PNG pages. Filenames,
  media types and the document/page relationships remain in the JSON records. Expired, deleted or
  quarantined binaries are excluded.
- `README.txt`: format and units guidance.

Credentials, token hashes, idempotency keys and internal object-storage keys are excluded.
Archive bytes are AES-GCM encrypted in the JPA export job, with a key derived for exports from
`DOCUMENT_STORAGE_ENCRYPTION_KEY` and authenticated case/job identity. This keeps document
originals encrypted at rest even inside exported snapshots. Retain the configured encryption
key while jobs remain available.

HTML and PDF share a renderer over the saved JSON payload. Both show the recorded decision,
next action, material amounts and limitations first, then every saved field and evidence path.
HTML is semantic, responsive and escaped; PDFs wrap long references, paginate and embed the
bundled Liberation Sans font. Unsupported glyphs use explicit Unicode code-point notation
rather than silently dropping evidence text. Monetary presentation changes units only; no
outcomes are recalculated.

Verification covers HTTP recovery expiry, concurrent single-use verification, restart,
delivery failure/retry, export expiry and stable bytes, missing-report retry, and the populated
revised-offer workflow including document-storage failure/retry. Frontend checks cover deliberate
verification and retry key reuse. PDF pages are rendered with Poppler and inspected visually.
