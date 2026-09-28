# 37 — Generate claim-checked explanations from finalized facts

**What to build:** Model-generated explanations of the Deal Analysis Report that consume only finalized structured facts and findings, with every displayed claim checked against those inputs, replacing the fixed template wording.

**Blocked by:** 30 — Render field-to-document evidence instead of raw JSON; 35 — Establish the AI execution harness.

**Status:** ready-for-agent

- [ ] The explanation request receives only the finalized report payload — structured facts, findings, and their sources — and never raw document text or unverified extraction candidates.
- [ ] Every claim in generated text is checked against the supplied facts before display; an unsupported claim blocks the text rather than being shown with a caveat.
- [ ] Generated text cannot alter any amount, rating, threshold, boundary, approval, or state; the Deal Engine continues to make no model calls.
- [ ] Amounts referenced in explanations are rendered from the report payload, not restated by the model, so the text cannot disagree with the numbers beside it.
- [ ] Explanations state unknowns as unknown and do not describe estimated values as exact.
- [ ] The existing template wording remains available as the fallback when generation is unavailable or a claim check fails, and the report records which path produced the text.
- [ ] Prohibited framings — "AI negotiator", "safe to sign", legal, tax, mechanical, title, or vehicle-history conclusions, and savings causation — are blocked by test, not by prompt instruction alone.
