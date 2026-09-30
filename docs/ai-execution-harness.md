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
   `ProviderMeter.callModel` under the task's operation (`EXTRACTION`, or `REPLY_DRAFT` for Dealer
   reply suggestions), with the existing timeout, concurrency bound, usage store and `Failure` taxonomy.
4. The gateway for the configured provider sends it (see [Providers](#providers)) with
   structured output constrained to the task schema.
5. The output is checked against the task schema, then the task parser. The caller receives a
   `ModelResult` — the value together with its `ModelProvenance` — or a `ProviderMeter.Failure`.

The harness persists nothing itself and never repairs output. A failed call leaves the caller
nothing to store, so no partial or guessed value can be persisted.

## Providers

One provider per deployment, chosen by `ai.model.provider` (`anthropic`, the default, or `openai`).
An empty `ai.model.id` selects that provider's default model. There is **no fallback between
providers or models**: a refusal, outage or rate limit from the configured provider fails the call
closed, because any other model has no recorded shadow comparison and must never answer.

| | Anthropic | OpenAI |
| --- | --- | --- |
| Gateway | `AnthropicModelGateway` | `OpenAiModelGateway` |
| SDK | `com.anthropic:anthropic-java` 2.66.0 | `com.openai:openai-java` 4.72.0 |
| API | Messages (`anthropic-messages-api`) | Responses (`openai-responses-api`) |
| Default model | `claude-opus-5` | `gpt-6-astra` |
| List price per 1M tokens (input / output) | $5 / $25 | $10 / $50 |
| Structured output | `output_config.format` json_schema | `text.format` json_schema, `strict: true` |
| Credentials (environment or system property) | `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN` | `OPENAI_API_KEY` |
| Endpoint overrides the gate refuses | `ANTHROPIC_BASE_URL`, `anthropic.baseUrl` | `OPENAI_BASE_URL`, `openai.baseUrl` |
| Vendor-side storage | none requested | `store: false`, so the Responses API does not keep the response |

Both gateways send the same instructions and the same framed input, offer no tools, make no SDK
retries, force SDK logging off, use the meter's timeout, and reduce every SDK exception and response
to one outcome code. Images go to OpenAI as `input_image` data URLs at `detail: high`.

OpenAI's strict mode requires every object in a schema to list all its properties as `required`
and to set `additionalProperties: false`. A task schema that does not is rejected by the vendor
with a 400, which fails closed as `PROVIDER_FAILURE`.

## Untrusted input

- The system prompt (OpenAI: `instructions`) is the task's instructions plus a fixed policy
  stating that everything in the user turn is untrusted data. Request content never reaches the
  system prompt, the output schema, the model identifier, the token bound, or the operation.
- Each input is framed as `<untrusted_data label="...">…</untrusted_data>`. `&`, `<` and `>` in
  content are escaped, so a document cannot close its frame, forge another frame, or open a
  pseudo-system tag. Labels are restricted to short lowercase text chosen by code.
- No tools are offered, so the model has no action it could be talked into taking.
- Output that follows an embedded instruction (extra fields, a different shape, a different
  operation) is rejected by the closed schema check.

`EmbeddedInstructionTest` is the regression suite, run against both providers: a set of
instruction-carrying documents must produce byte-identical instructions, schemas and models to a
benign document, each must stay inside exactly one frame, outputs that obey them must fail, and the
two providers must receive identical instructions, framing and schema.

## Provenance

Every output carries the provider, model identifier, prompt version, schema version,
threshold-set version (`ai.model.threshold-set`, default `pilot-parameters.v1`) and evaluation-set
version
(`ai.model.evaluation-set`, default `none`). The same tuple is written on the call's
`provider_usage` row (migration V24), so every model output is recorded with its versions even
before a feature persists the value. A feature that stores a model value must store the
`ModelResult` provenance next to it; ticket 36 does this for extraction.

## Metering and cost

`provider_usage` rows for model calls record the provider (`ANTHROPIC` or `OPENAI`) and its API, the
provenance above, pages as units, input and output tokens as reported by the vendor, and cost from
the code-owned `ModelPricing` list prices, keyed by provider and model (table above). Tokens and
cost are recorded for refusals and rejected output as well, because those are billed. An unpriced
model records `costUsd: null`, never zero, and cannot be enabled. The support usage route shows
these columns. No document content, extracted value, vendor text, exception message or credential is
written to telemetry or logs: SDK logging is forced off, and gateway exceptions are reduced to an
outcome code.

## Failing closed

| Condition | Outcome | `ProviderMeter.Status` |
| --- | --- | --- |
| Model paths disabled | not called | `PROVIDER_UNAVAILABLE` |
| Meter timeout, SDK timeout, rate limit (429), overload (529), 5xx, connection failure; OpenAI `status: failed` | unavailable | `PROVIDER_UNAVAILABLE` |
| Refusal (Anthropic `stop_reason: refusal`; OpenAI refusal content or `incomplete` for `content_filter`), rejected request (other 4xx) | provider failure | `PROVIDER_FAILURE` |
| `max_tokens` or context-window stop, OpenAI `incomplete` for any other reason, non-JSON body, unexpected stop reason or status, a tool call, off-schema or unparseable output, output from a model other than the one configured | contract failure | `CONTRACT_FAILURE` |

The SDK makes no retries (`maxRetries(0)`): the meter owns the time budget, and a hidden retry
would mask rate limiting. Server-side refusal fallbacks are deliberately not requested, because a
fallback answers with a model that has no recorded shadow comparison; a refusal fails closed. For
the same reason the harness never falls back from one provider to the other.

## Credentials

Each SDK reads its own credential from the environment (`ANTHROPIC_API_KEY` or
`ANTHROPIC_AUTH_TOKEN`; `OPENAI_API_KEY`). There is no Spring property that can hold a key, and
`ModelReleaseCheckTest` fails if one is added to `application.properties`. Keys never appear in
source, configuration files, logs, or error text.

## Rollout gate and release check

`ModelRolloutGate` runs at every application start. With `ai.model.enabled=true` it refuses to
start, in every profile, and names each unmet obligation by code. Every check applies to the
configured provider; another provider's terms, credentials or comparisons never count:

- `VENDOR_TERMS_NOT_VALIDATED:<provider>` — the provider's record in
  `classpath:ai/model-vendor-terms.json` (`model-vendor-terms.v2`, one record per provider) is not
  `EXECUTED` with validated retention and training-exclusion clauses, an agreement reference of at
  most 100 characters, a validator and a date. A provider with no record is unvalidated.
- `NO_FROZEN_EVALUATION_SET` — the evaluation-set version is still `none`.
- `MODEL_NOT_PRICED:<provider>` — the model has no `ModelPricing` entry under that provider.
- `CREDENTIALS_MISSING:<provider>` — none of the provider's credential variables is set.
- `ENDPOINT_OVERRIDDEN:<provider>` — one of the provider's endpoint overrides is set; terms are
  validated for the vendor's own endpoint, so a redirect is a different processor.
- `NO_SHADOW_COMPARISON:<provider>:<operation>:<prompt>:<schema>` — a registered task has no `PASS`
  record in `classpath:ai/shadow-comparisons.json` for the exact provider, model, prompt, schema,
  threshold-set and evaluation-set versions.

Tasks are registered as Spring beans of type `ModelTask`; none are registered yet. Changing the
provider, the model, a prompt, a schema, the threshold set or the evaluation set therefore requires
a new comparison before the application will start with model paths on. `ModelReleaseCheckTest`
states the same rules over the shipped configuration and records in CI.

### Shadow comparison record (`shadow-comparisons.v2`)

The Python evaluation harness (ticket 28) appends one entry per comparison:

```json
{
  "id": "extraction-2026-10-01-01",
  "operation": "EXTRACTION",
  "candidate": { "provider": "ANTHROPIC", "modelId": "claude-opus-5", "promptVersion": "deal-terms-prompt.v1", "schemaVersion": "deal-terms.v1" },
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
| Vendor retention and training terms | **Unmet for both Anthropic and OpenAI.** No terms executed; recorded in `model-vendor-terms.json`, the [provider inventory](provider-inventory.md) and the `provider-terms` readiness prerequisite. |
| Frozen evaluation set and shadow comparison | **Unmet.** Ticket 28 (corpus and Python harness) is not built, so no comparison can run. |
| Credentials | Not provisioned. |
