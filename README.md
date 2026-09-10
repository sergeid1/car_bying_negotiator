# AI Deal Analyzer walking skeleton

This slice accepts a synthetic cash Vehicle and Dealer Offer, queues deterministic analysis,
and persists a versioned Deal Analysis Report. Spring Boot owns the API and worker boundary,
PostgreSQL owns durable state, Flyway owns schema history, and React renders the Buyer workflow.

Analysis POST requests require an `Idempotency-Key` header. Reusing a key with the original
`Case-Access-Token` returns the original Buying Case and operation without returning the token
again, so client retries cannot create extra report versions or disclose case access. The worker
also recovers queued or interrupted operations from durable state when the application starts.

## Run locally

Requirements: Java 21, Maven, Node.js, npm, and Docker.

```bash
docker compose up -d database
cd backend
CASE_ACCESS_PEPPER="replace-with-a-long-random-production-secret" \
DOCUMENT_STORAGE_ENCRYPTION_KEY="replace-with-an-independent-32-byte-secret" \
mvn spring-boot:run
```

In another terminal:

```bash
cd frontend
npm install
npm run dev
```

Open <http://localhost:5173>. Vite proxies `/api` requests to Spring Boot on port 8080.

The default development database credentials are intentionally local-only and match
`compose.yaml`. PostgreSQL is published on host port `55432` to avoid common local database
collisions; override it with `DATABASE_PORT`. Override the application connection with
`DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD`. Flyway applies migrations from
`backend/src/main/resources/db/migration` when the application starts. `CASE_ACCESS_PEPPER` and
`DOCUMENT_STORAGE_ENCRYPTION_KEY` must be independent secret values containing at least 32 bytes.
Encrypted Dealer document originals are stored outside PostgreSQL under
`DOCUMENT_STORAGE_PATH` (default `backend/var/document-objects`).

## Verify

```bash
cd backend
mvn test
cd ..
cd frontend
npm test
npm run build
```

The backend acceptance suite exercises the public HTTP workflow, immutable regeneration, and
a full application restart against a file-backed test database using the production Flyway
migration.

## Contract evaluation fixtures

Frozen JSON fixtures under `backend/src/test/resources/fixtures` establish reusable quality contracts:

- `golden` drives cash-deal reports through the public API and asserts monetary results,
  calculation steps, declared rounding, and policy/schema versions.
- `financing` covers fixed-rate loans, zero APR, payment reconciliation, full-term add-on
  effects, and seeded loan properties. See [the financing policy](docs/financing-policy.md)
  for the verification API, timing, rounding and supported scope.
- `providers` exercises success, timeout, rate limits, missing VINs, stale and partial data,
  duplicate filtering, and schema drift with recorded synthetic responses; it never calls a paid
  endpoint.
- `extraction` reports per-field and weighted presence/exact-match scores, false-positive precision,
  numeric exact-match rate, and a configurable weighted material-field presence gate.
- `review` is a fail-closed release corpus: any unsafe `MATCHES_APPROVED_BASELINE` result fails,
  including unresolved questions, limit violations, and missing check scope or time metadata.

`ReportReplayEvaluator` compares regenerated reports after removing only differences explicitly
declared in the report payload. `ExtractionEvaluator`, `ProviderContractEvaluator`, and
`ReviewSafetyEvaluator` are public harness APIs so later product slices can reuse the same gates.

Core reports also provide [grounded explanations and editable negotiation drafts](docs/grounded-language-policy.md), with versioned source findings and local copy/download controls.

Buying Cases also support [optional email recovery and retained-data downloads](docs/case-portability-policy.md).
Configure SMTP and the public recovery URL to enable email delivery. ZIP, HTML and PDF downloads
use saved snapshots and require `DOCUMENT_STORAGE_ENCRYPTION_KEY` for encrypted export storage.

Buying Cases support [shorter retention and verified deletion](docs/case-deletion-policy.md).
Deletion revokes case and recovery access immediately; a retryable worker removes the complete
case graph and stored artifacts. Backup expiry remains unverified.
