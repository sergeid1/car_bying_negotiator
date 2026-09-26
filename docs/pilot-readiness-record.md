# Public-pilot readiness record

This is the reviewable record required by ticket 21. It states, for each public-pilot
prerequisite, whether it is **approved** for the bounded pilot or **explicitly blocks release**.

The record is enforced, not advisory. Its machine-readable source is
`backend/src/main/resources/pilot/pilot-readiness.json`, and `PilotReadiness` refuses to start the
application under the `pilot` profile while any prerequisite is blocked. `PilotReadinessTest`
checks that this document and that file agree, so the two cannot drift apart.
It also resolves named automated checks to executable test classes. In `mvn verify`,
`PilotReadinessEvidenceIT` requires passing, unskipped Surefire reports for those checks;
CI runs `clean verify` so results cannot be inherited from an earlier build.

**Current decision: the public pilot is blocked.** Six of nine prerequisites are unmet.

## Status

| # | Prerequisite | Status | Evidence or blocker |
| --- | --- | --- | --- |
| 1 | `jurisdiction-pack` | **BLOCKED** | No pilot state selected; no counsel-reviewed pack |
| 2 | `provider-terms` | **BLOCKED** | No provider terms executed |
| 3 | `document-corpus` | **BLOCKED** | No permissioned Dealer documents acquired |
| 4 | `expert-policy-validation` | **BLOCKED** | No automotive-expert review of boundary policy |
| 5 | `frozen-parameters` | APPROVED | Parameter and media checks plus synthetic/core report contract acceptance tests |
| 6 | `infrastructure-and-privacy-inventory` | **BLOCKED** | No hosting or provider stack selected |
| 7 | `legal-and-product-copy` | APPROVED | `PilotCopyClaimsTest` |
| 8 | `preregistered-thresholds` | **BLOCKED** | No approved thresholds frozen |
| 9 | `schema-and-orm-evidence` | APPROVED | `SchemaAndOrmEvidenceTest` |

## Blocked prerequisites

### 1. Jurisdiction pack — BLOCKED

No pilot state has been selected and no jurisdiction pack has been reviewed by qualified counsel.
The economics engine already reads effective-dated, versioned packs from `jurisdiction_policies`,
so the mechanism exists; the shipped values are synthetic development fixtures, not sourced law.

Counsel review of Buyer-facing legal copy sits inside this blocked scope. Prerequisite 7 verifies
only the mechanical claim properties of that copy, never its legal adequacy.

### 2. Provider terms — BLOCKED

No automotive or processing provider terms have been executed. As
[the provider inventory](provider-inventory.md) records, every adapter is a deterministic local
implementation or operator-run component, so no display, caching, attribution, derived-value,
retention, deletion, training, regional-processing, coverage, or cost right has been contractually
established.

### 3. Document corpus — BLOCKED

No permissioned Dealer documents have been acquired. The extraction and review corpora under
`backend/src/test/resources/fixtures` are synthetic fixtures authored for contract evaluation.
Neither the 100-document minimum nor a frozen held-out split exists, so the extraction and
review-safety numbers in DEVELOPMENT_SPEC.md §14 are unproven against real documents.

### 4. Expert policy validation — BLOCKED

No consumer-side automotive expert has reviewed opening, target, and walk-away behavior.
`PriceBoundaryPolicyTest` proves the arithmetic is correct and capped by Buyer limits against
synthetic market ranges. That is not evidence that the resulting numbers are sound negotiating
positions on representative retrospective deals.

### 6. Infrastructure and privacy inventory — BLOCKED

Recovery, purposes, retention, and deletion behavior are documented and implemented. Hosting,
object storage, queue, mail, and model providers have **not** been selected, so subprocessors and
processing regions cannot be stated and the inventory is incomplete.

### 8. Preregistered thresholds — BLOCKED

The preregistration mechanism exists and is enforced: `POST /api/v1/operator/pilot/freeze` accepts
one protocol, there is no update or unfreeze endpoint, and metrics return 409 before freeze. No
**approved** thresholds have been frozen. The values in
[the pilot metrics policy](pilot-metrics-policy.md) are explicitly illustrative.

The second half of this prerequisite — that any unmet prerequisite explicitly blocks release — is
delivered by this record and the `PilotReadiness` startup gate.

## Approved prerequisites

### 5. Frozen parameters — APPROVED

`backend/src/main/resources/pilot/pilot-parameters.v1.json` freezes upload limits, the material
extraction confidence threshold, materiality tolerances, market freshness and sample rules, and
the report schema and policy versions.

`FrozenPilotParametersTest` fails the build when the checked defaults or policy behavior
drift from the frozen values. Configuration values are compared with the shipped application
defaults; rules that exist only as behavior — the 45-day freshness rule, the three-comparable
minimum, the price plausibility bounds, the USD 1.00 and APR materiality thresholds, and zero
core-review term tolerance — are exercised at their policy seams. `FrozenDocumentMediaTest`
compares the complete supported-media set with the parser's accepted set. Deployment environment
overrides are not checked by these build-time tests and must be reviewed before release.

The core-report fixture records schema and structural expectations and is exercised against the
document-workflow report in `DocumentExtractionAcceptanceTest`. Synthetic report fixtures are
listed separately with their own schema version and exercised by `GoldenCashDealContractTest`.
The parameter tests verify that each fixture declares the matching frozen schema.

Freezing pins the current engineering defaults under a version. It does not assert that they are
the commercially correct pilot settings. Changing one requires a new parameter version.

### 7. Legal and product copy — APPROVED

`PilotCopyClaimsTest` scans frontend TypeScript recursively and the listed policy, negotiation,
report-generator, and renderer sources for law, advice, safety, fraud, guarantee, and savings
patterns. The backend source list must be extended when new copy-producing modules are added.
Every risky phrase must be recorded in `backend/src/test/resources/pilot/reviewed-claim-phrases.json`
with a justification. Whitespace is normalized, and an exception covers only matches inside its
reviewed phrase, not other claims on the same line. All seven recorded phrases are
negated disclaimers that withdraw a claim rather than make one.

The standing commercial-relationship disclosure in `frontend/src/App.tsx` states that the product
represents the Buyer, that no dealer, lender, or other commercial party pays for placement, leads,
or referrals, and that no commercial relationship affects any market range, rating, finding, or
suggested boundary. The test fails if that disclosure is removed.

This is a mechanical property, not counsel approval of the copy, which remains inside
prerequisite 1.

### 9. Schema and ORM evidence — APPROVED

`SchemaAndOrmEvidenceTest` boots the application against the real migration set and writes
`backend/target/readiness/schema-evidence.json`. Measured on 2026-09-26 against PostgreSQL 17.11
in a disposable local container:

- **Flyway migrations:** 21 migrations, `V1`–`V21`, sequentially versioned with no gaps or
  duplicates; every versioned migration recorded as successfully applied.
- **Hibernate schema validation:** passed. The context starts with `ddl-auto=validate`, so a
  mismatch between the migrated schema and the mappings fails the test by failing startup.
- **No auto-generation:** no configuration file in main resources, test sources, test resources,
  or `compose.yaml` sets a generating `ddl-auto` or `hbm2ddl.auto` value.
  The guard covers property/YAML assignments, environment-variable spelling, Java annotation
  strings, and environment-placeholder defaults; it does not inspect external deployment overrides.
- **Entity ownership:** 48 entities, each recorded with the table it owns.
- **Fetch rules:** 4 mapped associations, all declaring `FetchType.LAZY`; 0 eager associations;
  `open-in-view` disabled.
  The generated artifact derives both counts from JPA metamodel attributes and records each
  association, including inherited mappings and getter-based access.
- **Database constraints:** 49 primary keys, 66 foreign keys, 41 unique constraints, and 398 check
  constraints. PostgreSQL reports each `NOT NULL` column as a check constraint, which is why the
  same run against H2 in PostgreSQL mode reports 19 checks while the other three counts match.

The ordinary `mvn verify` run exercises this test against H2 in PostgreSQL mode; CI additionally
runs it against PostgreSQL 17 through `HARDENING_DATABASE_URL`, alongside `PersistenceHardeningTest`.

## Scope of this record

This record closes the assembly and enforcement work. It does not approve a pilot, and the
approved entries above are engineering evidence rather than commercial or legal sign-off.
Ticket 22 retains the pilot release gate; the production rollout checks it lists — Flyway ahead of
application traffic, ORM query and latency monitoring, and migration rollback criteria — are
release work, not implementation work.
