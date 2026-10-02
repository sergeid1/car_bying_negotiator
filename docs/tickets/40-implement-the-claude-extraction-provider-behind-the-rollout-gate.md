# 40 — Implement the Claude extraction provider behind the rollout gate

**What to build:** The parts of ticket 36 that need no live model call and no corpus. A model-backed
`DealTermsExtractionProvider` sends normalized page images under a code-owned prompt and schema,
rejects output that does not validate, and hands every candidate to `DealTermsValidator`. It is
selected by configuration, recorded on each extraction, and kept off by the existing rollout gate.
Ticket 36 keeps measured held-out accuracy, regression tests drawn from the permissioned corpus, and
enabling the provider.

**Blocked by:** 06 — Extract deal terms with field-level evidence; 35 — Establish the AI execution harness.

**Status:** ready-for-agent

**Interface:** [Extraction evaluation contract](../extraction-evaluation-contract.md).

- [ ] The provider implements `DealTermsExtractionProvider` through a registered `ModelTask` on the
      `EXTRACTION` operation and the existing `ModelHarness`, and populates `extractorVersion`,
      `modelVersion` and `schemaVersion` on every `ProviderExtraction`.
- [ ] The instructions and output schema are the versioned resource files named in the contract
      (`deal-terms-prompt.v1`, `deal-terms.v1`). The schema is closed and enumerates the
      validator's vocabularies. A response that fails the schema is rejected, never repaired.
- [ ] Normalized page images are the model input, passed as untrusted content. Every `Candidate`
      and `LineItem` carries an `EvidenceRegion` with a page that exists in the document.
- [ ] The prompt forbids inferring digits that are not legible. In code, an illegible or ambiguous
      value comes back as missing or low confidence. A test shows that a candidate citing a
      nonexistent page, an out-of-range region, or a value with no evidence is rejected.
- [ ] `DealTermsValidator` remains the authority on acceptance. Model output cannot set a Verified
      Term, an approval, a limit or a state, and a test proves it.
- [ ] Configuration selects the deterministic provider or the Claude provider. The deterministic
      provider stays the default and the test implementation, the chosen provider is recorded on
      each extraction, and a failure under the Claude provider fails closed without silently
      falling back to the deterministic one.
- [ ] With the rollout gate's obligations unmet, the application refuses to start with the Claude
      provider selected, and the gate lists its missing shadow comparison. Every test uses a fake
      gateway, and none sends a network request.
