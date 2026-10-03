# 67 — Extract four-square worksheets

**What to build:** Four-square worksheets, the desk sheet showing price, trade, down payment, and monthly payment, are a supported document type. Extraction captures price, trade allowance, down payment, payment, term, and APR with evidence, and the analysis flags payment-led figures that hide the total. Spec DESK-02.

**Blocked by:** 06 — Extract deal terms with field-level evidence; 40 — Implement the Claude extraction provider behind the rollout gate.

**Status:** complete for synthetic fixtures — four-square worksheets are a recognized document type in the
deterministic and Claude providers (`deal-terms-prompt.v2`), as of 2026-10-03. Handwritten or crossed-out
figures require Buyer confirmation, payment-only worksheets report the missing total and term without
inferring them, and payments reconcile through `FinancingCalculator`. Accuracy evidence remains blocked on
ticket 28's permissioned corpus; Claude extraction needs a new shadow comparison for prompt v2.

- [x] The document type, extraction schema, and deterministic validation support four-square layouts, with labelled synthetic fixtures in the extraction and evaluation corpora.
- [x] Handwritten or crossed-out figures are low confidence and require Buyer confirmation.
- [x] When only a monthly payment is shown, the report states the missing total and term rather than inferring them.
- [x] Payment, term, and APR reconcile through the existing financing engine where complete.
- [ ] Accuracy is reported only from ticket 28's permissioned corpus; synthetic results are labelled development checks.
