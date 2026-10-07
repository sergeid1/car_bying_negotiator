# 87 — Switch the Claude default model to Opus 5.5

**What to build:** The Anthropic provider defaults to `claude-opus-5-5` instead of `claude-opus-5`. Opus 5.5
is the current Opus, with the same context window, output limit and tokenizer, at a lower list price: $4 input
and $20 output per million tokens, against $5 and $25. That cuts the per-case extraction cost of the Florida
pilot by about 20% before any other tuning. The installed `anthropic-java` 2.66.0 already knows the model and
the `effort` setting, so no dependency upgrade is needed. Filed on 2026-10-06 from the pilot cost estimate.

Opus 5.5 differs from Opus 5 in ways that matter here:

- Its default `effort` is `medium`, one level below Opus 5's `high`. `AnthropicModelGateway.params` sets no
  effort today, so switching the model id alone would quietly lower extraction depth.
- Thinking cannot be disabled (`{type: "disabled"}` returns 400). The gateway already leaves adaptive thinking
  on, so nothing changes.
- Forced `tool_choice` `any`/`tool` returns 400. The gateway offers no tools, so nothing changes.
- Thinking blocks are bound to the model and conversation. Every call is a single turn with no replayed
  history, so nothing changes.

**Blocked by:** none for the code change. Enabling model calls still needs the rollout gate's existing
obligations: executed Anthropic terms (`model-vendor-terms.json`) and a passing shadow comparison for this exact
model (tickets 28 and 39).

**Status:** complete — `ModelProvider.ANTHROPIC` defaults to `claude-opus-5-5`, priced in `ModelPricing` at
$4 / $20 per million tokens, with `claude-opus-5` still priced at $5 / $25 for a deployment that pins it, as of
2026-10-07. `AnthropicModelGateway` sets `output_config.effort` to `high` through the SDK's typed builder, and
`AnthropicModelGatewayTest` asserts it in the request body. `ModelRolloutGateTest` shows that a passing comparison
for `claude-opus-5` does not admit `claude-opus-5-5`. Open: model calls stay disabled until the Anthropic terms are
executed and `claude-opus-5-5` has its own passing shadow comparison (tickets 28 and 39).

- [x] `ModelProvider.ANTHROPIC` defaults to `claude-opus-5-5`, and `ModelPricing` lists it at $4.00 / $20.00 per
      million tokens. `claude-opus-5` stays priced so a deployment that pins it explicitly keeps working.
- [x] `AnthropicModelGateway.params` sets `output_config.effort` explicitly to `high`, matching the depth Opus 5
      used by default, and a gateway test asserts that the request body carries it. Lowering effort to save cost
      is a separate decision for the evaluation harness to measure.
- [x] The gateway and explanation tests, the `application.properties` comment, `docs/ai-execution-harness.md`
      (default model, list price, shadow comparison example) and `docs/provider-inventory.md` name
      `claude-opus-5-5`. Ticket history that records what was true at the time stays unchanged.
- [x] The rollout gate is unchanged: no shadow comparison recorded for `claude-opus-5` counts for
      `claude-opus-5-5`, and a test confirms that the gate refuses the new model without its own comparison.
- [x] `mvn clean verify` and `npm test` pass.
