# 36 — Implement the Claude document extraction provider

**What to build:** A model-backed implementation of `DealTermsExtractionProvider` that reads real dealer paperwork into schema-constrained candidate fields with confidence and page provenance, replacing the deterministic provider that only recognizes fixture layouts.

**Blocked by:** 28 — Build the permissioned document corpus and publish measured extraction accuracy; 35 — Establish the AI execution harness.

**Status:** blocked on tickets 28 and 39 and on vendor terms — narrowed on 2026-10-02: ticket 40
implements the provider, schema validation, page evidence, configuration selection and fail-closed
behavior behind the rollout gate. This ticket keeps the held-out accuracy measurement, the
regression tests drawn from the permissioned corpus, and enabling the provider.

- [ ] The new provider implements the existing `DealTermsExtractionProvider` interface and populates `extractorVersion`, `modelVersion`, and `schemaVersion` on every `ProviderExtraction`.
- [ ] Extraction uses schema-constrained structured output; a response that does not validate against the schema is rejected rather than repaired by hand.
- [ ] Normalized page images are the model input; every `Candidate` and `LineItem` carries an `EvidenceRegion` identifying the page and location it came from.
- [ ] An unreadable or ambiguous value is returned as missing or low-confidence; the prompt and the validator both forbid inferring digits that are not legible.
- [ ] `DealTermsValidator` remains the authority on acceptance; model output never bypasses deterministic validation, and no model output can set a Verified Term, approval, limit, or state.
- [ ] The deterministic provider is retained as the test and offline implementation, selected by configuration, and the choice of provider is recorded on the extraction.
- [ ] Accuracy on the held-out corpus split is measured per field by the Python evaluation harness from ticket 28 and recorded; the provider is not enabled by default until it meets the frozen thresholds from that ticket.
- [ ] Multi-page buyer's orders, fee tables, phone photographs, and revised offers are covered by regression tests drawn from the permissioned corpus.
