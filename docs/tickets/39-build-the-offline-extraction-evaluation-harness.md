# 39 — Build the offline extraction evaluation harness without a permissioned corpus

**What to build:** The parts of ticket 28 that need neither real dealer paperwork nor a live model
call. A standalone Python harness fixes and verifies a held-out split before any run, scores
extractions per field with denominators and a failure taxonomy, records each run's conditions, holds
thresholds that are frozen before outcome review, and produces the shadow comparison record that
ticket 35's rollout gate reads. It is proven end to end against a synthetic development corpus.
Ticket 28 keeps the permissioned corpus, its permission records, live batch runs, published
accuracy, and the pilot-readiness entry.

**Blocked by:** 06 — Extract deal terms with field-level evidence; 35 — Establish the AI execution harness.

**Status:** ready-for-agent

**Interface:** [Extraction evaluation contract](../extraction-evaluation-contract.md).

- [ ] The harness is a Python project under `evaluation/`. It is outside the Spring Boot
      application, is never packaged or deployed with the service, and no Buyer request path can
      reach it. Its tests run offline with the standard library alone.
- [ ] A corpus manifest records the corpus version, whether it is synthetic or permissioned, and
      each document's condition tags. For a permissioned corpus, it also records each document's
      permission basis and permission-record reference, and the harness rejects a permissioned
      corpus that lacks them. The permission basis is kept separate from the `SERVICE_PROCESSING`
      consent.
- [ ] A split command assigns documents to `DEVELOPMENT` and `HELD_OUT` and records a content hash
      and freeze time. An evaluation refuses to start if the split is missing or its hash no
      longer matches.
- [ ] The scorer reports per-field precision and recall with numerators and denominators, by split
      and by condition tag, together with a failure taxonomy (missing, wrong value, invented field,
      wrong page, low confidence).
- [ ] Each run records its concurrency, effort level, model, prompt version, schema version,
      corpus version, split hash, and harness version.
- [ ] Acceptance thresholds live in a versioned file whose freeze is recorded before outcome
      review. Comparing a run to unfrozen thresholds is refused, and deviations are recorded with
      the result.
- [ ] A batch client builds Message Batches requests from the backend's code-owned prompt and
      schema files, marking the stable system prompt and schema for prompt caching. It sits behind
      an interface whose fake is used in every test. No test or default command sends a network
      request, and the real client needs an explicitly supplied credential.
- [ ] The harness writes `shadow-comparisons.v2` records as the AI execution harness document
      specifies. It refuses to write one for a synthetic corpus or a non-`HELD_OUT` split, and it
      never edits the backend's `shadow-comparisons.json` itself.
- [ ] A synthetic development corpus derived from the existing extraction fixtures runs end to end:
      split, a predictions-file run, scoring, the threshold check, and a report. Its results are
      labeled synthetic and are never cited as accuracy.
