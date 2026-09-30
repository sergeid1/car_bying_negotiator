# 35 — Establish the AI execution harness

**What to build:** The shared infrastructure every model call in the product depends on — untrusted-input handling, version recording, shadow comparison, cost metering, and fail-closed behavior — so that model use satisfies specification §12 before any model reaches a Buyer. Calls on a Buyer's request path are written in Java inside the existing application; offline corpus scoring and shadow comparison run in the separate Python harness built in ticket 28.

**Blocked by:** 18 — Operate the service without broad document access.

**Status:** harness complete; model use stays blocked — every model call goes through one metered,
fail-closed path with recorded provenance as of 2026-09-30, and a rollout gate keeps model paths off
until vendor terms are executed and a shadow comparison is recorded. The comparison itself needs
ticket 28's corpus and Python harness.

- [x] The Anthropic Java SDK (`com.anthropic:anthropic-java`) is added to the backend, with `claude-opus-5` configured as the model and credentials resolved from the environment, never from source or logs.
- [x] Model calls are issued in Java inside the request path and routed through the existing `ProviderMeter`, reusing its `EXTRACTION` operation, timeout, concurrency bound, usage store, and `Failure` status taxonomy rather than introducing a parallel one.
- [x] All uploaded and provider content is passed as untrusted data; text that appears to contain instructions cannot change the system prompt, the schema, or the requested operation, and regression tests cover embedded-instruction documents.
- [x] Every material model output records the model identifier, prompt version, schema version, threshold set, and evaluation-set version alongside the result.
- [ ] Provider or model changes run against the frozen corpus with a recorded shadow comparison before rollout, executed by the Python evaluation harness from ticket 28; a rollout without that comparison fails the release check.
- [x] Per-case model and provider usage and cost are metered per OPS-02 through `ProviderMeter` without writing document content, extracted values, provider text, or personal data into logs or traces.
- [x] Timeout, rate-limit, refusal, and malformed-output conditions fail closed to a stated unavailable state; no partial or guessed value is persisted.
- [x] Vendor retention and training controls are contractually validated and recorded before production enablement; customer documents are not used for training in the MVP.
- [x] A configuration check prevents enabling any model-backed path in production while these obligations are unmet.

## Implementation notes — 2026-09-30

### One path for every model call

`ModelHarness` builds each request only from a code-owned `ModelTask` and configuration, routes it
through `ProviderMeter.callModel` (the `EXTRACTION` operation, timeout, concurrency bound and `Failure`
taxonomy), and validates the output against the task's closed JSON schema before parsing. Uploaded and
provider content is framed as escaped `<untrusted_data>`; no tools are offered, and embedded-instruction
regression tests run against every provider. Timeouts, rate limits, refusals, truncation and off-schema
output all end in a stated unavailable state and nothing is persisted. Each result carries its model,
prompt, schema, threshold-set and evaluation-set versions; tokens and cost are metered on the usage row
(`V24__record_model_call_provenance_and_tokens.sql`). Details: `docs/ai-execution-harness.md`.

### The rollout gate

With `ai.model.enabled=true`, startup is refused while any obligation is unmet: executed vendor terms,
pricing, credentials, the vendor's own endpoint, and a PASS shadow comparison for the exact
model/prompt/schema/threshold/evaluation versions of every registered task. It applies in every
environment, not only production, so nobody can try a real model locally until the obligations are met.
Server-side refusal fallbacks are deliberately not requested: a fallback model has no recorded
comparison, so a refusal fails closed.

### Not yet met

The shadow comparison can be recorded and checked (`shadow-comparisons.v2`) but cannot be run until
ticket 28's corpus and Python harness exist, so that criterion stays open. Vendor retention and training
terms are recorded as unexecuted; the gate enforces them.

### Addition beyond the ticket: OpenAI as a second provider

At the user's request, `OpenAiModelGateway` (`com.openai:openai-java`, Responses API, `store(false)`)
sits beside `AnthropicModelGateway`. `ai.model.provider` selects exactly one provider per deployment
(default `anthropic`); there is no fallback between providers. The gate and the vendor-terms record
(`model-vendor-terms.v2`) are per provider, and OpenAI's terms are recorded as unexecuted. Default
models are `claude-opus-5` and `gpt-6-astra`. Not verifiable without a real call: whether OpenAI's
strict mode accepts the task schemas and echoes the model name exactly; if not, calls fail closed.
