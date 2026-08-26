# AI Deal Analyzer walking skeleton

This slice accepts a synthetic cash Vehicle and Dealer Offer, queues deterministic analysis,
and persists a versioned Deal Analysis Report. Spring Boot owns the API and worker boundary,
PostgreSQL owns durable state, Flyway owns schema history, and React renders the Buyer workflow.

Analysis POST requests require an `Idempotency-Key` header. Reusing a key returns the original
Buying Case and operation, so client retries cannot create extra report versions. The worker also
recovers queued or interrupted operations from durable state when the application starts.

## Run locally

Requirements: Java 21, Maven, Node.js, npm, and Docker.

```bash
docker compose up -d database
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
`src/main/resources/db/migration` when the application starts.

## Verify

```bash
mvn test
cd frontend
npm test
npm run build
```

The backend acceptance suite exercises the public HTTP workflow, immutable regeneration, and
a full application restart against a file-backed test database using the production Flyway
migration.
