# AI execution harness

Every model call in the product goes through one path, `com.dealanalyzer.ai.ModelHarness`. It
exists so that model use meets specification §12 before any model output reaches a Buyer. **No
model-backed path is enabled today**: `ai.model.enabled` defaults to `false`, the deterministic
providers remain the only ones in use, and the obligations below are unmet, so enabling it fails
startup.

## The call path

1. A caller names a code-owned `ModelTask`: operation, prompt version, schema version, fixed
   instructions, a closed JSON output schema, an output-token bound, and a strict parser.
2. Uploaded and provider content is passed as `UntrustedInput` (text or PNG page images) with a
   short code-owned label.
3. `ModelHarness` builds the request from the task and configuration only, and routes it through
   `ProviderMeter.callModel` under the existing `EXTRACTION` operation, timeout, concurrency bound,
   usage store and `Failure` taxonomy.
4. `AnthropicModelGateway` sends it with the Anthropic Java SDK (`com.anthropic:anthropic-java`)
   to the configured model, `claude-opus-5` by default (`ai.model.id`), using structured output
   (`output_config.format` with the task schema).
5. The output is checked against the task schema, then the task parser. The caller receives a
   `ModelResult` — the value together with its `ModelProvenance` — or a `ProviderMeter.Failure`.

The harness persists nothing itself and never repairs output. A failed call leaves the caller
nothing to store, so no partial or guessed value can be persisted.

## Untrusted input

- The system prompt is the task's instructions plus a fixed policy stating that everything in the
  user turn is untrusted data. Request content never reaches the system prompt, the output schema,
  the model identifier, the token bound, or the operation.
- Each input is framed as `<untrusted_data label="...">…</untrusted_data>`. `&`, `<` and `>` in
  content are escaped, so a document cannot close its frame, forge another frame, or open a
  pseudo-system tag. Labels are restricted to short lowercase text chosen by code.
- No tools are offered, so the model has no action it could be talked into taking.
- Output that follows an embedded instruction (extra fields, a different shape, a different
  operation) is rejected by the closed schema check.

`EmbeddedInstructionTest` is the regression suite: a set of instruction-carrying documents must
produce byte-identical system prompts, schemas, models and token bounds to a benign document, each
must stay inside exactly one frame, and outputs that obey them must fail.

## Provenance

Every output carries the model identifier, prompt version, schema version, threshold-set version
(`ai.model.threshold-set`, default `pilot-parameters.v1`) and evaluation-set version
(`ai.model.evaluation-set`, default `none`). The same tuple is written on the call's
`provider_usage` row (migration V24), so every model output is recorded with its versions even
before a feature persists the value. A feature that stores a model value must store the
`ModelResult` provenance next to it; ticket 36 does this for extraction.

## Metering and cost

`provider_usage` rows for model calls record provider `ANTHROPIC`, the provenance above, pages as
units, input and output tokens as reported by the vendor, and cost from the code-owned
`ModelPricing` list prices (`claude-opus-5`: $5 input / $25 output per million tokens). Tokens and
cost are recorded for refusals and rejected output as well, because those are billed. An unpriced
model records `costUsd: null`, never zero, and cannot be enabled. The support usage route shows
these columns. No document content, extracted value, vendor text, exception message or credential
is written to telemetry or logs: SDK logging is forced off, and gateway exceptions are reduced to
an outcome code.

## Failing closed

| Condition | Outcome | `ProviderMeter.Status` |
| --- | --- | --- |
| Model paths disabled | not called | `PROVIDER_UNAVAILABLE` |
| Meter timeout, SDK timeout, rate limit (429), overload (529), 5xx, connection failure | unavailable | `PROVIDER_UNAVAILABLE` |
| Refusal (`stop_reason: refusal`), rejected request (other 4xx) | provider failure | `PROVIDER_FAILURE` |
| `max_tokens` or context-window stop, non-JSON body, unexpected stop reason, off-schema or unparseable output, output from a model other than the one configured | contract failure | `CONTRACT_FAILURE` |

The SDK makes no retries (`maxRetries(0)`): the meter owns the time budget, and a hidden retry
would mask rate limiting. Server-side refusal fallbacks are deliberately not requested, because a
fallback answers with a model that has no recorded shadow comparison; a refusal fails closed.

## Credentials

The SDK reads `ANTHROPIC_API_KEY` (or `ANTHROPIC_AUTH_TOKEN`) from the environment. There is no
Spring property that can hold a key, and `ModelReleaseCheckTest` fails if one is added to
`application.properties`. Keys never appear in source, configuration files, logs, or error text.

## Rollout gate and release check

`ModelRolloutGate` runs at every application start. With `ai.model.enabled=true` it refuses to
start, in every profile, and names each unmet obligation by code:

- `VENDOR_TERMS_NOT_VALIDATED` — `classpath:ai/model-vendor-terms.json` is not `EXECUTED` with
  validated retention and training-exclusion clauses, an agreement reference, a validator and a date.
- `NO_FROZEN_EVALUATION_SET` — the evaluation-set version is still `none`.
- `MODEL_NOT_PRICED` — the configured model has no `ModelPricing` entry.
- `CREDENTIALS_MISSING` — no vendor credential is present in the environment.
- `ENDPOINT_OVERRIDDEN` — `ANTHROPIC_BASE_URL` is set; terms are validated for the vendor's own
  endpoint, so a redirect is a different processor.
- `NO_SHADOW_COMPARISON:<operation>:<prompt>:<schema>` — a registered task has no `PASS` record in
  `classpath:ai/shadow-comparisons.json` for the exact model, prompt, schema, threshold-set and
  evaluation-set versions.

Tasks are registered as Spring beans of type `ModelTask`; none are registered yet. Changing the
model, a prompt, a schema, the threshold set or the evaluation set therefore requires a new
comparison before the application will start with model paths on. `ModelReleaseCheckTest` states
the same rules over the shipped configuration and records in CI.

### Shadow comparison record (`shadow-comparisons.v1`)

The Python evaluation harness (ticket 28) appends one entry per comparison:

```json
{
  "id": "extraction-2026-10-01-01",
  "operation": "EXTRACTION",
  "candidate": { "modelId": "claude-opus-5", "promptVersion": "deal-terms-prompt.v1", "schemaVersion": "deal-terms.v1" },
  "baseline": "deterministic-dealer-layout.v1",
  "evaluationSetVersion": "dealer-corpus.v1",
  "thresholdSetVersion": "pilot-parameters.v1",
  "corpusSplit": "HELD_OUT",
  "executedBy": "python-evaluation-harness",
  "harnessVersion": "0.1.0",
  "executedAt": "2026-10-01T12:00:00Z",
  "outcome": "PASS",
  "report": "evaluation/reports/extraction-2026-10-01-01.json"
}
```

A record from any other producer, on any split other than `HELD_OUT`, or missing a field fails to
load, which fails startup. A `FAIL` outcome is kept as history but never satisfies the gate.

## Current state of the obligations

| Obligation | State |
| --- | --- |
| Vendor retention and training terms | **Unmet.** No terms executed; recorded in `model-vendor-terms.json`, the [provider inventory](provider-inventory.md) and the `provider-terms` readiness prerequisite. |
| Frozen evaluation set and shadow comparison | **Unmet.** Ticket 28 (corpus and Python harness) is not built, so no comparison can run. |
| Credentials | Not provisioned. |
