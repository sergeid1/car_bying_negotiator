# 119 — Answer process questions with a checked model task

**What to build:** A `TotoAnswerTask`, a code-owned `ModelTask` run through `ModelHarness` like
`ExplanationTask` and `ReplyDraftTask`. It receives the whole ticket 118 guide as fixed context, plus the
Buyer's question and up to six recent turns as `UntrustedInput`. It returns a structured answer whose every
paragraph cites guide articles. Code checks the answer again before a Buyer sees it; the instructions are
guidance, not the safety boundary.

**Blocked by:** 118 for the real guide and policy. The task, schema and checks can be built against a
small fixture guide first.

**Status:** open — filed on 2026-10-14.

## Scope

- A new meter operation, `ProviderMeter.Operation.GUIDE_ANSWER`, so Toto's usage and cost are separate from
  extraction, explanations and reply drafts.
- The guide goes in the system prompt as one cacheable block (prompt caching), so repeated questions pay
  mostly cache-read prices for it. The prompt version includes the guide version.
- Output schema (closed, strict for both providers):
  - `kind`: `ANSWER` or `DECLINE`.
  - `paragraphs`: up to four, each `{text, article_ids}` with at least one id, text at most 600 characters.
  - `decline_reason` for `DECLINE`: one of the reasons in `docs/toto-answer-policy.md`. The Buyer sees the
    code-owned wording for that reason, never model text.
- The code-side check rejects an answer when:
  - an `article_id` is not in the guide, or a paragraph cites none;
  - a number, percentage or dollar amount appears that is not in one of the cited articles;
  - a prohibited claim term appears (the same patterns as `PilotCopyClaimsTest`, moved into a shared main
    class so the test and the check use one list);
  - it contains a URL, an email address or a phone number;
  - it names a dealer, lender or insurer that is not named in a cited article.
- A rejected answer or a provider failure gives a fixed fallback: "I can't answer that one well. These
  guides may help," with links to the guide articles whose titles best match the question (code
  keyword match, no model).
- No tools, no web access, no case or account data in the request, no retry with another model.

## Done when

- [ ] The task is registered as a bean, so the rollout gate requires its own shadow comparison.
- [ ] Tests cover each rejection rule, the decline wording, the fallback, and injected text in the
      question or earlier turns ("ignore your rules", "reply with a phone number"), which stays content.
- [ ] Both gateways accept the schema (OpenAI strict mode: every property required,
      `additionalProperties: false`).
- [ ] `docs/ai-execution-harness.md` lists the new operation and task.
