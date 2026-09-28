# 28 — Build the permissioned document corpus and publish measured extraction accuracy

**What to build:** A permissioned corpus of real dealer paperwork with a held-out split, and published per-field accuracy measured against it, so extraction claims rest on evidence rather than on fixture behavior. The harness is a standalone Python project; it measures the service but is not part of it.

**Blocked by:** 06 — Extract deal terms with field-level evidence.

**Status:** ready-for-agent

- [ ] The permission basis for each corpus document is documented and is separate from the `SERVICE_PROCESSING` consent; customer documents are not retained for model training or dealer intelligence without a separately designed, voluntary opt-in.
- [ ] The corpus spans phone photographs, multi-page buyer's orders, fee tables, documents with missing fields, and revised offers.
- [ ] A held-out split is fixed and recorded before any evaluation run.
- [ ] The evaluation harness is a Python project held outside the Spring Boot application, runs offline against the corpus, and is never reachable from a Buyer request path or deployed with the service.
- [ ] Corpus runs use the Message Batches API for scheduled scoring and threshold sweeps, and cache the stable system prompt and extraction schema so repeated runs do not pay for the same prefix.
- [ ] Per-field precision and recall are reported with denominators, together with a failure taxonomy naming the layouts and conditions that fail.
- [ ] Harness concurrency and effort level are recorded with each run, since they determine run duration and cost rather than any property of the implementation language.
- [ ] Acceptance thresholds are frozen before outcome review, and deviations are recorded with the release decision.
- [ ] Results are written into the pilot-readiness record and supersede the entry noting the absence of a permissioned corpus.
- [ ] No customer-facing copy states an accuracy that exceeds the measured held-out result.
