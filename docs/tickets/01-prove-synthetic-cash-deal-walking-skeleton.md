# 01 — Prove the synthetic cash-deal walking skeleton

**What to build:** A thin but running product path that accepts synthetic Vehicle and Dealer Offer facts and produces a versioned Deal Analysis Report through the intended UI, API, persistence, background-worker, and report boundaries.

**Blocked by:** None — can start immediately.

**Status:** complete

- [x] A Buyer can start a synthetic cash analysis and reach a rendered Deal Analysis Report through the public workflow.
- [x] The flow persists its Buying Case, inputs, operation state, and immutable report payload across process restarts.
- [x] Long-running work uses an operation identifier and exposes explicit workflow stage state.
- [x] Provider interactions use deterministic fakes whose response types do not leak into domain interfaces.
- [x] The initial report can be regenerated from recorded structured inputs with no unexplained payload differences.

## Completed review findings

These findings were identified after the first walking-skeleton implementation and completed in
the continuation of this ticket:

- [x] Recover persisted `QUEUED` and in-progress operations after a process stops mid-analysis;
  startup recovery now dispatches unfinished durable operations.
- [x] Derive top-reason wording and displayed amounts from finalized structured analysis instead of
  the single fixture's hard-coded values.
- [x] Make every regeneration payload difference explicit, including `coverage.asOf`, or keep that
  field stable when the underlying evidence has not changed.
- [x] Require and persist an idempotency key for analysis requests so duplicate requests return one
  operation and report version.
- [x] Deepen module boundaries so market assessment, deterministic cash calculation, rating policy,
  explanation, and report assembly do not change together in one service.
- [x] Replace unrestricted strings for closed workflow/report concepts with domain types, and carry
  currency alongside request money values.
