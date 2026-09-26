# 21 — Assemble and close public-pilot readiness evidence

**What to build:** A reviewable readiness record shows that the product's jurisdiction, provider, corpus, policy, privacy, legal-copy, and measurement prerequisites are either approved for the bounded pilot or explicitly block release.

**Blocked by:** 02 — Establish golden fixtures and contract evaluation; 06 — Extract Deal Terms with field-level evidence; 09 — Produce a transparent Market Assessment; 19 — Capture privacy-minimized feedback and pilot metrics.

**Status:** complete — the readiness record is assembled and enforced as of 2026-09-26. Its verdict
is that **the public pilot is blocked**: six of nine prerequisites are unmet and cannot be met by
implementation work. Ticket 22 retains the pilot release gate.

- [ ] One pilot jurisdiction is selected and represented by an effective-dated, sourced, versioned pack reviewed by qualified counsel. — **BLOCKED**, recorded
- [ ] Automotive and processing provider terms document display, caching, attribution, derived-value, retention, deletion, training, regional-processing, coverage, and cost rights. — **BLOCKED**, recorded
- [ ] At least 100 permissioned representative Dealer documents are acquired, de-identified as appropriate, and split before evaluation into development and frozen held-out subsets. — **BLOCKED**, recorded
- [ ] A consumer-side automotive expert validates opening, target, and walk-away policy behavior against representative retrospective cases. — **BLOCKED**, recorded
- [x] Upload limits, materiality tolerances, confidence thresholds, market freshness/sample rules, and report-schema fixtures are frozen and versioned.
- [ ] Recovery mechanism, infrastructure/provider choices, provider inventory, purposes, subprocessors, regions, retention, and deletion behavior are documented. — **BLOCKED**, recorded
- [x] Legal and product copy avoids unsupported law, advice, safety, fraud, guarantee, and savings claims and discloses relevant commercial relationships.
- [ ] Pilot success and safety thresholds are preregistered before pilot outcomes are reviewed; any unmet prerequisite explicitly blocks release. — **BLOCKED** for the thresholds; the blocking mechanism is delivered
- [x] The readiness record includes reviewed Flyway migrations, Hibernate schema-validation results, entity ownership and fetch rules, database constraints, and evidence that no production schema depends on Hibernate auto-generation.

An unchecked box above is a recorded, enforced block, not unfinished work. Four prerequisites need
external parties — counsel, provider counterparties, document owners, an automotive expert — and
two need operator decisions that only the product owner can make. No amount of implementation
closes them, which is why the ticket asked for a record that can say so explicitly.

## Implementation — 2026-09-26

Branch: `feature/public-pilot-readiness-evidence`.

### What was built

- **An enforced readiness record.** `backend/src/main/resources/pilot/pilot-readiness.json` is the
  machine-readable source; `PilotReadiness` refuses application startup under the `pilot` profile
  while any prerequisite is unapproved, naming the blockers. Approval requires either a named
  automated check or a named sign-off with a date, and a blocked entry must state its blocker.
  [The reviewable record](../pilot-readiness-record.md) carries the human-readable reasoning, and a
  test asserts the document and the enforced statuses cannot drift apart.
- **Frozen pilot parameters.** `pilot-parameters.v1.json` freezes upload limits, the material
  confidence threshold, materiality tolerances, market freshness and sample rules, and report
  schema and policy versions. `FrozenPilotParametersTest` compares configuration against the
  shipped application defaults and exercises behavior-only rules — the 45-day freshness rule, the
  three-comparable minimum, price plausibility bounds, and the USD 1.00 materiality threshold — at
  their policy seams rather than reading constants back.
- **A claim-surface ratchet.** `PilotCopyClaimsTest` scans the web client and the language the
  product generates for law, advice, safety, fraud, guarantee, and savings claims. Each risky
  phrase must be recorded with a justification in `reviewed-claim-phrases.json`; all seven recorded
  phrases are negated disclaimers. The scan caught that the repository had **no**
  commercial-relationship disclosure, so a standing disclosure was added to `frontend/src/App.tsx`
  and the test fails if it is removed.
- **Schema and ORM evidence.** `SchemaAndOrmEvidenceTest` writes
  `backend/target/readiness/schema-evidence.json` and is wired into the CI evidence artifact.

### Verified evidence

- Full `mvn -q clean verify` after the review fixes exited 0: **158 Surefire tests, zero failures/errors/skips**, plus
  `DocumentParserPackagingIT` and `PilotReadinessEvidenceIT`. The three opt-in `ContainerParserIT` tests were skipped
  in this ordinary run, as before. Frontend: 17 tests and the production build passed.
- `SchemaAndOrmEvidenceTest` and `PersistenceHardeningTest` both passed against **PostgreSQL 17.11**
  in a disposable local container, which was stopped afterwards. Recorded: 21 sequential migrations
  all successfully applied, Hibernate `validate` against the migrated schema, 48 entities with their
  owned tables, 4 mapped associations all `FetchType.LAZY` with zero eager and `open-in-view`
  disabled, and 49 primary keys / 66 foreign keys / 41 unique / 398 check constraints. PostgreSQL
  counts `NOT NULL` as a check constraint, which is why the H2 run reports 19 checks.
- No configuration in main resources, test sources, test resources, or `compose.yaml` selects a
  generating `ddl-auto` or `hbm2ddl.auto` value.

### Code review follow-up — 2026-09-26

The initial review identified evidence gaps despite passing tests. The implementation now:

- Parses DDL settings in properties, YAML, environment-variable names, Java annotation strings,
  and environment defaults, with regression examples for allowed and generating settings.
- Verifies that named automated checks resolve to executable test classes. During `mvn verify`,
  `PilotReadinessEvidenceIT` also requires passing, unskipped Surefire results for every approved
  automated check. CI starts with `clean verify` to exclude stale results.
- Preserves the intentional `pilot` startup gate. The parser policy explains container-mode staging
  without the release profile, and requires the approved record plus `pilot` for public release.
- Scans frontend TypeScript recursively and includes stored and synthetic report generators and the
  report renderer. Matching normalizes whitespace; reviewed exceptions apply only to the matching
  occurrence, so another claim on the same line is not exempted.
- Checks all recorded materiality thresholds and supported media types. Core report expectations
  now live in a dedicated versioned fixture exercised by the existing document-workflow acceptance
  test; synthetic fixtures are separately identified under their own schema version.
- Uses Java time support in readiness-record tests and round-trips a dated approval through JSON.
- Measures eager associations from JPA metamodel attributes, including inherited mappings and
  getter-based access, and writes the association inventory alongside its counts.

The public pilot remains blocked by the same six external or operator prerequisites. These fixes
strengthen engineering evidence; they do not supply external approvals.

### Limits on what this closes

The three approved prerequisites rest on automated engineering evidence, not commercial or legal
sign-off. Freezing parameters pins current defaults under a version; it does not assert they are
the commercially correct pilot settings. The copy scan checks mechanical claim properties; counsel
review of Buyer-facing copy remains inside the blocked jurisdiction-pack scope. No deployment,
pilot approval, or operator drill was performed by this ticket.
