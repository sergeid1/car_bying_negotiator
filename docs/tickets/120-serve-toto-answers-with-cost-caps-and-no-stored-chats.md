# 120 — Serve Toto answers with cost caps and no stored chats

**What to build:** `POST /api/v1/toto/answers` takes `{question, turns}` and returns the checked answer
from ticket 119. otdog does not store the conversation: the client keeps the chat in memory and sends the
recent turns with each question, and the server keeps only usage counts. That keeps Toto outside case
and account deletion, export and retention, because there is nothing to delete or export. The tool is
free, so every answer is paid for by the operator, and the caps below keep that cost bounded.

**Blocked by:** 119.

**Status:** open — filed on 2026-10-14. No migration unless the daily counters need a table; if so,
the coordinator reserves the number after the ticket 115–116 integration (V56 and V57 are taken).

## Scope

- A new `TotoController` (not `DealAnalysisController`, which is a shared hot file).
- Request limits: question 1–1,000 characters; at most six earlier turns, each at most 1,500 characters;
  anything longer is refused with a plain message, not truncated silently.
- Caps, each returning a friendly code-owned "Toto is resting" message:
  - per signed-in account: a daily question limit (default 30);
  - per client without an account: a lower daily limit (default 10) through the existing peer quota;
  - a deployment-wide daily spending ceiling computed from `ModelPricing` and metered usage, after which
    Toto is unavailable until the next day.
- `toto.enabled` defaults to `false`. `GET /api/v1/toto` reports whether Toto is available, so the client
  can hide it. Enabling it also requires the harness's existing obligations (executed model terms and a
  passing shadow comparison for `TotoAnswerTask`, ticket 122).
- Question and answer text never goes into logs, metrics, error reports or the usage store; only counts,
  token usage, outcome and the task's provenance.
- `docs/provider-inventory.md` and `docs/hardening-threat-model.md` record that Buyer questions go to the
  model provider and are not stored by otdog.

## Done when

- [ ] Endpoint tests cover each limit, each cap, the disabled state, and a provider failure.
- [ ] A test proves no question or answer text reaches logs or the usage store.
- [ ] The spending ceiling stops calls once reached and resets the next day.
