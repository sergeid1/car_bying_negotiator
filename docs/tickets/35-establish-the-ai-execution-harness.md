# 35 — Establish the AI execution harness

**What to build:** The shared infrastructure every model call in the product depends on — untrusted-input handling, version recording, shadow comparison, cost metering, and fail-closed behavior — so that model use satisfies specification §12 before any model reaches a Buyer. Calls on a Buyer's request path are written in Java inside the existing application; offline corpus scoring and shadow comparison run in the separate Python harness built in ticket 28.

**Blocked by:** 18 — Operate the service without broad document access.

**Status:** ready-for-agent

- [ ] The Anthropic Java SDK (`com.anthropic:anthropic-java`) is added to the backend, with `claude-opus-5` configured as the model and credentials resolved from the environment, never from source or logs.
- [ ] Model calls are issued in Java inside the request path and routed through the existing `ProviderMeter`, reusing its `EXTRACTION` operation, timeout, concurrency bound, usage store, and `Failure` status taxonomy rather than introducing a parallel one.
- [ ] All uploaded and provider content is passed as untrusted data; text that appears to contain instructions cannot change the system prompt, the schema, or the requested operation, and regression tests cover embedded-instruction documents.
- [ ] Every material model output records the model identifier, prompt version, schema version, threshold set, and evaluation-set version alongside the result.
- [ ] Provider or model changes run against the frozen corpus with a recorded shadow comparison before rollout, executed by the Python evaluation harness from ticket 28; a rollout without that comparison fails the release check.
- [ ] Per-case model and provider usage and cost are metered per OPS-02 through `ProviderMeter` without writing document content, extracted values, provider text, or personal data into logs or traces.
- [ ] Timeout, rate-limit, refusal, and malformed-output conditions fail closed to a stated unavailable state; no partial or guessed value is persisted.
- [ ] Vendor retention and training controls are contractually validated and recorded before production enablement; customer documents are not used for training in the MVP.
- [ ] A configuration check prevents enabling any model-backed path in production while these obligations are unmet.
