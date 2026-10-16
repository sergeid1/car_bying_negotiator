# 131 — Record Toto usage without a case

**What to build:** Usage recording for `GUIDE_ANSWER` that needs no Buying Case, so `TotoAnswerTask` can call the
model against the real usage store. Today `ProviderUsageStore.startModel` requires an active case, and V57's
`provider_usage_scope` check rejects a row with no case, so every Toto answer fails before the provider is called
(found in ticket 119, 2026-10-16). Split out of ticket 120 so the cost caps there build on it.

**Blocked by:** none.

**Status:** complete, as of 2026-10-16. `ModelUsageScope` carries the usage owner (a case, or for
`GUIDE_ANSWER` no case and an optional account) separately from model inputs, through `ModelHarness.runScoped`,
`ProviderMeter.callModelScoped` and `ProviderUsageStore.startModelScoped`; `TotoAnswerTask.Answerer.answer(scope, …)`
now records usage in the real store. Signed-in calls lock the active account, so pending or completed account deletion
refuses them and late completions cannot recreate erased rows. V60 admits case-free, journey-free `GUIDE_ANSWER` rows
with an optional account and refuses `GUIDE_ANSWER` with a case and every other case-free operation. The account export
lists `guideAnswerUsage`; account deletion removes it. Rows without an account hold no Buyer identifier or content and
are kept as operator cost records until a general operations-retention policy exists (coordinator decision; there was
no existing usage retention to inherit). Ticket 120's spending ceiling can use
`ProviderUsageStore.guideAnswerCostUsd(from, until)`, which sums known cost and leaves unpriced rows out.

## Scope

- A usage scope for guide answers: no case, and an optional Buyer Account, because people without an account use
  Toto too (ticket 120's per-client cap).
- The scope is passed to `ModelHarness`/`ProviderMeter` separately from the model inputs. No account, case or
  client identifier reaches the model request.
- A migration that admits `GUIDE_ANSWER` rows with no case and an optional account, and only that operation; every
  other operation keeps its current case or journey rule.
- Rows hold counts, token usage, outcome and provenance only, never question or answer text.
- Account export lists the account's guide-answer usage; account deletion removes it. Rows with no account hold no
  identifier or content; `docs/case-deletion-policy.md` says how long they are kept.

## Done when

- [x] An acceptance test answers a question through the real store, with and without an account, and the provider
      is called once per question.
- [x] Other operations without a case are still refused (test).
- [x] No question or answer text appears in the usage store or logs (test).
- [x] Ticket 120's deployment-wide spending ceiling can sum these rows (a query or method 120 uses).
