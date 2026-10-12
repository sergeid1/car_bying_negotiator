# otdog

**The Buyer's side of a used-car purchase.**

Like having your own car-buying expert, from the first search to the moment you sign. No dealer
commissions, no paid recommendations, no dealer customers: otdog is completely on your side.

otdog helps a person buying a used car from a dealership know what they are really paying, what
a fair price looks like, and what to push back on, so they don't get taken advantage of. It finds,
checks, explains and drafts. The Buyer makes every decision and is the only one who talks to the
dealer.

## How it helps

1. **Shop.** Describe the car you want and add VINs of cars you like. otdog matches cars for sale
   and explains why each one fits.
2. **Ask for a quote.** Pick a car and get a drafted email asking the dealer for an itemized
   out-the-door quote.
3. **Negotiate.** Each dealer reply is checked against market evidence and the limits you set, and a
   counteroffer is drafted for you to edit or decline.
4. **Agree.** Compare quotes across cars. When you accept a written quote, it becomes your Agreed
   Price.
5. **Test drive.** A drafted email books the test drive, so you arrive already knowing the number.
6. **At the desk.** Upload the buyer's order or four-square worksheet the dealer hands you, and
   otdog checks it against the Agreed Price. If you never negotiated first, you get the
   same market, fee and add-on check on the spot.

At the core is the **Deal Analysis Report**: a versioned, evidence-backed answer to *what am I really
paying, how does this offer compare with the market, what should I challenge, and what price limits
should guide the next conversation?*

## What we stand for

- **Buyers only.** Dealer payments, affiliate deals or lead value never influence a search result,
  rating, finding or draft. otdog is never a lead source for dealers.
- **A tool, not an agent.** Nothing reaches a dealer unless the Buyer approved it word for word, and it
  goes from the Buyer's own email address, either from the Buyer's email app or through their
  connected Gmail or Outlook account. otdog never writes to a dealer in its own name.
- **Code does the math.** AI reads documents and explains findings. Deterministic, versioned code owns
  every money calculation, classification, threshold and price limit, so the same inputs always give
  the same report.
- **Private by default.** No account is needed. Buying Cases open by a private link, documents are
  encrypted at rest, and a Buyer can export or delete their case at any time.

## Status

The journey above is built and tested end to end against synthetic data and test adapters. Florida is
the first pilot state. A public pilot stays blocked until its prerequisites are met: executed
market-data and inventory provider terms, a permissioned set of real dealer documents, expert review
of the price-limit policy, and a reviewed Florida jurisdiction pack. The application refuses to start
under the `pilot` profile while any prerequisite is unmet; see the
[pilot readiness record](docs/pilot-readiness-record.md).

AI model calls are off by default (`AI_MODEL_ENABLED=false`) and stay behind a rollout gate that
requires executed vendor terms and a passing shadow comparison for the exact model.

The full product and engineering contract is [DEVELOPMENT_SPEC.md](DEVELOPMENT_SPEC.md). Work is
tracked as numbered tickets in [docs/tickets](docs/tickets).

## Tech stack

- **Backend:** Java 21, Spring Boot, PostgreSQL 17, Flyway (`backend/`)
- **Frontend:** React, TypeScript, Vite (`frontend/`)
- **Document parsing:** confined, disposable worker JVMs for untrusted uploads
- **CI:** GitHub Actions running tests, PostgreSQL persistence tests, dependency, source, secret and
  container scans

## Run locally

Requirements: Java 21, Maven, Node.js 22, Docker and OpenSSL.

Start everything with one command from the repository root:

```bash
scripts/dev-start.sh
```

Open <http://localhost:5173>. Vite proxies `/api` to the backend. Press Ctrl+C to stop both servers.

What the script does:

1. **Generates the two backend secrets on the first run.** The backend needs `CASE_ACCESS_PEPPER`,
   which protects the private links that open a Buying Case, and `DOCUMENT_STORAGE_ENCRYPTION_KEY`,
   which encrypts uploaded dealer documents at rest. The script creates each one as 32
   cryptographically random bytes, base64-encoded (`openssl rand -base64 32`), and saves them in
   `backend/var/local-secrets.env`, a file only you can read, which Git ignores.
2. **Reuses the same secrets on every later run.** They must not change: a new pepper breaks the
   links of existing cases, and a new key makes documents already uploaded unreadable. To start over
   from nothing, delete `backend/var/local-secrets.env`, `backend/var/document-objects` and the
   database volume (`docker compose down -v`) together.
3. **Loads the secrets into the environment** for the backend.
4. **Starts PostgreSQL** with `docker compose up -d --wait database` and waits until it is healthy.
5. **Installs the frontend dependencies** with `npm ci` if `frontend/node_modules` is missing.
6. **Starts the backend on port 8080** (`mvn spring-boot:run`) and **the frontend on port 5173**
   (`npm run dev`), with both logs in the same terminal.
7. **Stops both servers** on Ctrl+C, or as soon as either one exits. The database container keeps
   running; stop it with `docker compose stop database`.

To run the pieces by hand instead, start the database with `docker compose up -d database`, run
`mvn spring-boot:run` in `backend` with both secrets set in the environment, and run `npm ci` and
`npm run dev` in `frontend` in another terminal.

Configuration notes:

- `CASE_ACCESS_PEPPER` and `DOCUMENT_STORAGE_ENCRYPTION_KEY` must be independent secrets of at least
  32 bytes.
- PostgreSQL is published on host port `55432` to avoid local collisions; change it with
  `DATABASE_PORT`. Point the backend elsewhere with `DATABASE_URL`, `DATABASE_USERNAME` and
  `DATABASE_PASSWORD`. The default credentials in `compose.yaml` are for local use only.
- Flyway applies the migrations in `backend/src/main/resources/db/migration` at startup.
- Encrypted dealer documents are stored outside the database under `DOCUMENT_STORAGE_PATH` (default
  `backend/var/document-objects`).
- Everything else in `backend/src/main/resources/application.properties` defaults to a safe local
  setting, with email, inventory, market data, mailbox and model providers using fakes or turned off.

## Test

```bash
mvn -f backend/pom.xml clean verify   # unit, acceptance and packaging tests (about five minutes)

cd frontend
npm test                              # component and accessibility tests
npm run build                         # type check and production build
npx playwright test                   # browser tests, including the 360 px layout gate
```

Backend acceptance tests drive the public HTTP API against the production Flyway schema. Frozen
fixtures in `backend/src/test/resources/fixtures` hold the quality contracts for reports, financing,
providers, extraction and review safety. Later slices reuse their evaluators (`ReportReplayEvaluator`,
`ExtractionEvaluator`, `ProviderContractEvaluator`, `ReviewSafetyEvaluator`) as release gates.
`PilotCopyClaimsTest` keeps Buyer-facing language within the approved claims.

## Documentation

**Product and analysis**

- [Core report](docs/core-report-policy.md), [price limits](docs/price-boundary-policy.md),
  [financing](docs/financing-policy.md) and [revised offers](docs/revised-offer-policy.md)
- [Desk check](docs/desk-check.md) and [offer and purchase](docs/offer-and-purchase-policy.md)
- [Grounded explanations and drafts](docs/grounded-language-policy.md) and the
  [outbound message gate](docs/outbound-gate.md)
- [AI execution harness](docs/ai-execution-harness.md) and the
  [extraction evaluation contract](docs/extraction-evaluation-contract.md)

**Buyer data**

- [Buyer Accounts](docs/buyer-account-policy.md), [case recovery and downloads](docs/case-portability-policy.md)
  and [retention and deletion](docs/case-deletion-policy.md)
- [Feedback and pilot metrics](docs/pilot-metrics-policy.md) and [case studies](docs/case-study-policy.md)

**Security and operations**

- [Threat model](docs/hardening-threat-model.md) and [incident and restore runbook](docs/hardening-runbook.md)
- [Document parser confinement](docs/document-parser-policy.md)
- [Service operations](docs/service-operations-policy.md) and
  [provider and subprocessor inventory](docs/provider-inventory.md)
- [Pilot readiness record](docs/pilot-readiness-record.md) and the
  [expert review packet](docs/expert-review-packet.md)
