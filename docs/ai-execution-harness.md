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
   `ProviderMeter.callModel` under the task's operation (`EXTRACTION`, `REPLY_DRAFT` for Dealer
   reply suggestions, `EXPLANATION` for report explanations, or `GUIDE_ANSWER` for Toto process
   questions), with the existing timeout, concurrency bound, usage store and `Failure` taxonomy.
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
| Default model | `claude-opus-5-5` | `gpt-6-astra` |
| List price per 1M tokens (input / output) | $4 / $20 | $10 / $50 |
| Structured output | `output_config.format` json_schema | `text.format` json_schema, `strict: true` |
| Credentials (environment or system property) | `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN` | `OPENAI_API_KEY` |
| Endpoint overrides the gate refuses | `ANTHROPIC_BASE_URL`, `anthropic.baseUrl` | `OPENAI_BASE_URL`, `openai.baseUrl` |
| Vendor-side storage | none requested | `store: false`, so the Responses API does not keep the response |

Both gateways send the same instructions and the same framed input, offer no tools, make no SDK
retries, force SDK logging off, use the meter's timeout, and reduce every SDK exception and response
to one outcome code. Images go to OpenAI as `input_image` data URLs at `detail: high`.

Anthropic requests explicitly set `output_config.effort` to `high`, preserving the depth Opus 5
used by default rather than adopting Opus 5.5's `medium` default. A deployment may still pin
`claude-opus-5`, priced at $5 / $25 per million input / output tokens. Each model needs its own
passing shadow comparison; an Opus 5 comparison does not admit Opus 5.5.

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
`ModelResult` provenance next to it. Model-backed extraction records it as described under
[Deal-terms extraction](#deal-terms-extraction-ticket-40).

## Deal-terms extraction (ticket 40)

`ClaudeDealTermsExtractionProvider` is the model-backed `DealTermsExtractionProvider`. It is built but
not in use: `extraction.provider` selects `deterministic` (the default, and the provider every test
context uses) or `claude`, and the shipped configuration keeps the deterministic one.

- **Task.** One `ModelTask` on the `EXTRACTION` operation. Its instructions and schema are the
  resource files `ai/extraction/deal-terms-prompt.v1.md` and `ai/extraction/deal-terms.v1.schema.json`,
  loaded once from the classpath, as the
  [extraction evaluation contract](extraction-evaluation-contract.md) requires. The schema is closed at
  every level and enumerates the validator's fields, line categories, units, tax and optionality
  claims. A changed prompt or schema is a new file and a new version, never an edit in place. As for
  every task, the gateway appends the fixed untrusted-data policy (`UntrustedFraming.POLICY`) to the
  instructions in the system channel.
- **Input.** Only the normalized page images, each as a `PngImage` labelled `page <n> image`. The
  recognized page text is not sent.
- **Output.** The schema check and parser reject anything off-schema; nothing is repaired. Each
  candidate and line item must carry an evidence region. The `DealTermsValidator` shape check then
  runs inside the metered attempt, so evidence citing a page the document does not have or a region
  outside the page fails the call as a `CONTRACT_FAILURE` and is metered as one. A value whose digits
  (or VIN characters, or date parts) do not appear in the wording the model quoted for it is kept at
  confidence 0 and marked material, so it always requires review; a value the model reports as
  illegible (`null`) becomes a `MISSING` candidate.
- **Authority.** The model proposes candidates only. The schema has no field for a verification
  state, approval, limit or status, and the validator assigns every state, so model output can
  never produce a Verified Term.
- **Versions.** Each extraction records `extractorVersion` `claude:deal-terms-prompt.v1`,
  `modelVersion` the configured model (the harness rejects output served by any other) and
  `schemaVersion` `deal-terms.v1`. The deterministic provider records
  `deterministic-dealer-layout.v1` and `none`. The `provider_usage` row carries the full provenance.
- **Metering.** The provider meters itself through `ModelHarness`, once per document with pages as
  units; `DocumentExtractionService` does not wrap it in a second meter call.
- **Failure.** Any harness failure propagates from `DocumentExtractionService.extract`. There is no
  fallback to the deterministic provider.
- **Startup.** With `extraction.provider=claude`, the application refuses to start until every
  obligation for the extraction task holds, whether or not `ai.model.enabled` is set. The refusal
  lists `MODEL_PATHS_DISABLED` while model paths are off, `MODEL_PROVIDER_NOT_SELECTED:ANTHROPIC`
  while another provider is configured, and the gate's codes, including
  `NO_SHADOW_COMPARISON:ANTHROPIC:EXTRACTION:deal-terms-prompt.v1:deal-terms.v1`.

Ticket 36 keeps the measured held-out accuracy, corpus regression tests and enabling the provider.

## Toto process answers (ticket 119)

`TotoAnswerTask` registers a `ModelTask<Output>` on `GUIDE_ANSWER`, even while model paths are
disabled. Its prompt version is `toto-answer-prompt.v1.<guide-version>` and its schema is
`toto-answer-schema.v1`, so a guide change needs a new shadow comparison for each provider/model.

- **Guide boundary.** A code-owned `TotoGuideLoader` bean returns one immutable `Guide(version,
  articles)`, with each article carrying `id`, `title`, `text` and optional `link`. A link is
  `?guide=<existing-client-guide-id>` or null, set only by the reviewed loader. The guide is loaded once at
  startup and shared by the registered task and `TotoAnswerTask.Answerer`. The default loader is
  ticket 118's `ReviewedTotoGuide`, which reads the Markdown articles listed in
  `toto/guide.json` and serves them as version `<manifest version>.<12 hex digits of the content
  digest>`, so any article change changes the prompt version. Until the operator has reviewed
  every article and the manifest marks the guide released, it serves no articles
  (`toto-guide.unavailable`), and no provider is called with that empty context. The synthetic
  development guide lives only in test resources. Front matter, review and release rules are in
  the [Toto answer policy](toto-answer-policy.md).
- **Request API.** Inject `TotoAnswerTask.Answerer` and call `answer(question, recentTurns)`.
  Each recent `Turn` has a `BUYER` or `TOTO` role and text. The question and the last six turns
  become separately framed `UntrustedInput.Text` values; roles select code-owned frame labels,
  and previous Toto text remains untrusted user-channel content.
  The model inputs accept no case or account data and use no case id for metering. It does not store
  question/history text. No HTTP endpoint, authentication or request limits are implemented here;
  ticket 120 owns that boundary for anonymous and signed-in clients. Ticket 131 adds
  `ModelUsageScope.guideAnswer(accountId)` (null for anonymous), passed through
  `Answerer.answer(scope, question, turns)`, `ModelHarness.runScoped` and
  `ProviderMeter.callModelScoped` separately from model inputs. The two-argument answer method
  uses anonymous usage. V60 permits case-free GUIDE_ANSWER usage with an optional active account;
  other operations retain their scope rules. Account start locks serialize with account deletion,
  and late completion only updates existing rows. Account export includes `guideAnswerUsage`.
  `ProviderUsageStore.guideAnswerCostUsd(from, until)` sums known ModelPricing costs across all
  guide callers in the half-open creation-time interval, including failed/rejected calls with
  reported tokens. Null costs (unfinished or unpriced attempts) do not contribute. Ticket 120's
  `TotoService` counts those at a worst-case price for its spending ceiling (see the
  [threat model](hardening-threat-model.md)). Persistence errors propagate unchanged.
- **Caching.** The entire fixed guide and task instructions occupy one stable system block.
  `ModelTask.cacheInstructions` opts into Anthropic's explicit ephemeral cache control; the
  harness carries that flag to `ModelRequest`. Existing task/request constructors default it to
  false. OpenAI receives the same stable `instructions` prefix for its automatic prompt cache.
  Cache hits still depend on vendor eligibility and minimum prefix sizes; the small test fixture
  does not demonstrate live cache savings. Existing token metering and list-price estimates are
  retained, so this task does not claim cache-adjusted billing accuracy.
- **Checked output.** The closed schema requires `kind`, `paragraphs` and nullable
  `decline_reason`; paragraph objects require `text` and `article_ids`. Code independently
  enforces one to four nonempty answer paragraphs of at most 600 characters, valid nonempty
  citations, cited numeric/amount/percentage tokens, no contacts or URLs, and the shared
  `ProhibitedClaims.PATTERNS` used by `PilotCopyClaimsTest`. As an additional conservative check,
  each paragraph must be a contiguous, word-bounded passage from one cited article (whitespace
  may differ). This prevents invented dealer/lender/insurer names without relying on a model or
  capitalization heuristic. Paraphrases and spliced passages fall back, even when otherwise
  reasonable; guide articles should provide short passages suitable for display.
- **Declines and failures.** A decline contains no paragraphs and a `DeclineReason`; only the
  enum's fixed Buyer wording is displayed. The six categories mirror ticket 118's list:
  `LEGAL_OR_TAX`, `WHETHER_TO_SIGN`, `OWN_CASE`, `VEHICLE_JUDGMENT`, `PROVIDER_CHOICE`, and
  `OUTSIDE_CAR_BUYING`. **Ticket 118 must confirm these categories and wording before rollout.**
  Rejected output and provider failures return exactly “I can't answer that one well. These
  guides may help,” with up to three `Citation(articleId, title, link)` suggestions ranked by
  question/title keyword overlap, then article id. With no matching title there are no links.
  Model output never supplies a URL, title or fallback wording. There is no retry or second model.
- **Result.** `Answer` follows ticket 120's agreed response fields: kind (`ANSWER`, `DECLINE`, or
  code-only `FALLBACK`), checked `AnswerParagraph(text, citations)` values, a code-owned
  decline/fallback `message` (null for answers), and fallback `suggestions` as full citations.
  Every citation has `articleId`, guide-owned `title`, and relative `link` or null. Non-answer
  paragraphs and non-fallback suggestions are empty. `Answer` additionally carries provenance
  for the backend; ticket 120 can map the agreed HTTP fields without losing citation information.
  Against a recording store, rejections are recorded as metered contract failures
  with token usage. Fallback carries no model provenance or partial model text.

`TotoAnswerTaskTest` exercises the schema through both real SDKs against local stub servers,
each rejection rule, code-owned declines, fallback ranking, metering, default absence of a guide,
rollout discovery and injected instructions in questions/history. These are synthetic development
checks, not a reviewed guide, a live provider evaluation, or shadow-comparison evidence.

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

Tasks are registered as Spring beans of type `ModelTask`: the reply-draft, explanation and Toto answer tasks
always, and the deal-terms extraction task only while `extraction.provider=claude`. Changing the
provider, the model, a prompt, a schema, the threshold set or the evaluation set therefore requires
a new comparison before the application will start with model paths on. `ModelReleaseCheckTest`
states the same rules over the shipped configuration and records in CI.

### Shadow comparison record (`shadow-comparisons.v2`)

The standalone [Python evaluation harness](../evaluation/README.md) (ticket 39) exports one entry
per comparison for reviewed integration into this resource. It never edits the backend resource:

```json
{
  "id": "extraction-2026-10-01-01",
  "operation": "EXTRACTION",
  "candidate": { "provider": "ANTHROPIC", "modelId": "claude-opus-5-5", "promptVersion": "deal-terms-prompt.v1", "schemaVersion": "deal-terms.v1" },
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
| Frozen evaluation set and shadow comparison | **Unmet.** Ticket 39 provides the offline harness and synthetic development checks. Ticket 28 still owns the permissioned corpus, live runs, published accuracy and pilot readiness. Synthetic runs cannot produce a shadow comparison or satisfy this gate. |
| Credentials | Not provisioned. |
