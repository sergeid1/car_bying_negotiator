# 37 — Generate claim-checked explanations from finalized facts

**What to build:** Model-generated explanations of the Deal Analysis Report that consume only finalized structured facts and findings, with every displayed claim checked against those inputs, replacing the fixed template wording.

**Blocked by:** 30 — Render field-to-document evidence instead of raw JSON; 35 — Establish the AI execution harness.

**Status:** complete — the report shows a "What the findings mean" explanation built only from the
finalized findings, with every sentence checked in code against its one cited finding and labeled with
the path that produced it, as of 2026-10-02. The model path (`EXPLANATION`,
`report-explanation-prompt.v1` / `report-explanation-schema.v1`) is implemented behind
`ModelRolloutGate` and stays off, so the ticket-15 template wording produces every explanation until
ticket 35's vendor and shadow-comparison obligations are met for `EXPLANATION`.

- [x] The explanation request receives only the finalized report payload — structured facts, findings, and their sources — and never raw document text or unverified extraction candidates.
- [x] Every claim in generated text is checked against the supplied facts before display; an unsupported claim blocks the text rather than being shown with a caveat.
- [x] Generated text cannot alter any amount, rating, threshold, boundary, approval, or state; the Deal Engine continues to make no model calls.
- [x] Amounts referenced in explanations are rendered from the report payload, not restated by the model, so the text cannot disagree with the numbers beside it.
- [x] Explanations state unknowns as unknown and do not describe estimated values as exact.
- [x] The existing template wording remains available as the fallback when generation is unavailable or a claim check fails, and the report records which path produced the text.
- [x] Prohibited framings — "AI negotiator", "safe to sign", legal, tax, mechanical, title, or vehicle-history conclusions, and savings causation — are blocked by test, not by prompt instruction alone.

## Implementation notes — 2026-10-02

`GET /api/v1/cases/{caseId}/reports/{version}/explanation` produces the explanation on first read,
outside any transaction, stores it once in `report_explanations` (V25) under the case lock with the
path, fallback reason and model provenance, and serves that row afterwards, so the model is called at
most once per report version. A fallback caused by a transient provider failure is stored like any
other result. Case deletion and expiry remove the row, and case exports include it.

- `ExplanationFacts` gives the model finding states and amount identifiers only — no figures, labels,
  document text or extraction candidates.
- Each sentence cites exactly one finding through a single `finding_id` string, so the schema needs no
  array bounds on either vendor.
- `ExplanationCheck` blocks the whole model text if any sentence restates an amount, uses a prohibited
  framing, asserts a state or subject its finding does not support, calls an estimate exact, or leaves
  an unknown unstated. Amounts are rendered from `/analysis/materialAmounts` as placeholders.
- The template (`negotiation/GroundedLanguage`, `grounded-language.v1`) passes the same check.
  `report/DealExplanation` is the synthetic walking-skeleton producer and is unchanged.
- `ProviderMeter.Operation` gains `EXPLANATION`. `ExplanationBoundaryTest` proves the Deal Engine
  packages reference neither `ai` nor `explanation`, and the explanation path writes only its own row.
- See `docs/grounded-language-policy.md` § Report explanations.
