# 131 — Record Toto usage without a case

**What to build:** Usage recording for `GUIDE_ANSWER` that needs no Buying Case, so `TotoAnswerTask` can call the
model against the real usage store. Today `ProviderUsageStore.startModel` requires an active case, and V57's
`provider_usage_scope` check rejects a row with no case, so every Toto answer fails before the provider is called
(found in ticket 119, 2026-10-16). Split out of ticket 120 so the cost caps there build on it.

**Blocked by:** none.

**Status:** open — filed on 2026-10-16. Needs a migration; the coordinator reserves the number (V58 is taken by
ticket 126).

## Scope

- A usage scope for guide answers: no case, and an optional Buyer Account, because people without an account use
  Toto too (ticket 120's per-client cap).
- The scope is passed to `ModelHarness`/`ProviderMeter` separately from the model inputs. No account, case or
  client identifier reaches the model request.
- A migration that admits `GUIDE_ANSWER` rows with no case and an optional account, and only that operation; every
  other operation keeps its current case or journey rule.
- Rows hold counts, token usage, outcome and provenance only, never question or answer text.
- Account export lists the account's guide-answer usage; account deletion removes it. Rows with no account are kept
  only as long as the existing usage retention allows, and say so in `docs/case-deletion-policy.md`.

## Done when

- [ ] An acceptance test answers a question through the real store, with and without an account, and the provider
      is called once per question.
- [ ] Other operations without a case are still refused (test).
- [ ] No question or answer text appears in the usage store or logs (test).
- [ ] Ticket 120's deployment-wide spending ceiling can sum these rows (a query or method 120 uses).
