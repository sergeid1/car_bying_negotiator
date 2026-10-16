# 120 — Serve Toto answers with cost caps and no stored chats

**What to build:** `POST /api/v1/toto/answers` takes `{question, turns}` and returns the checked answer
from ticket 119. otdog does not store the conversation: the client keeps the chat in memory and sends the
recent turns with each question, and the server keeps only usage counts. That keeps Toto outside case
and account deletion, export and retention, because there is nothing to delete or export. The tool is
free, so every answer is paid for by the operator, and the caps below keep that cost bounded.

**Blocked by:** 119, and 131 for usage recording without a case.

**Status:** complete — 2026-10-16. No migration: account counts come from `provider_usage` rows and client
counts from the existing in-process peer quota. `TotoController` and `TotoService` serve the agreed interface;
`toto.enabled` stays `false`. The spending ceiling adds known cost to a worst-case `ModelPricing` price for every
guide-answer row without a known cost (in flight, failed or unpriced) and every call admitted in the process, so
unknown cost only overstates spend. Days are UTC days; the client cap is a rolling 24 hours. Defaults: 30 account
and 10 client questions a day, a $20.00 daily ceiling (`TOTO_DAILY_SPENDING_CEILING_USD`; the operator confirms it).
Known limits: behind the Cloudflare Worker and the pilot nginx proxy every visitor shares the proxy's address, so
the client cap is one deployment-wide bucket until the deployment passes a trusted client address (ticket 136); admission is
per process, so several replicas can overshoot the ceiling by a few worst-case calls.

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

## Agreed interface (coordinator, 2026-10-15)

Ticket 121 builds its panel against a mock of this shape, so 119 and 120 implement it as written. Change it
only through the coordinator.

- `GET /api/v1/toto` → `200 {"available": boolean}`. The client shows nothing when `available` is false or
  the request fails.
- `POST /api/v1/toto/answers` with `{"question": string, "turns": [{"role": "BUYER" | "TOTO", "text": string}]}`,
  oldest turn first, at most six. Same-origin JSON, no case or account token required.
  The client (ticket 121) sends only answered exchanges and cuts Toto's earlier text to 1,500 characters, so a
  long answer never trips `TOTO_TURNS_INVALID`; the server still enforces the limit.
- `200` body:
  - `kind`: `ANSWER`, `DECLINE` or `FALLBACK`.
  - `paragraphs`: for `ANSWER`, one to four `{"text": string, "citations": [Citation, ...]}` with at least one
    citation each; empty otherwise.
  - `message`: for `DECLINE` and `FALLBACK`, the code-owned wording from `docs/toto-answer-policy.md` (the server
    owns it, the client shows it as given); `null` for `ANSWER`.
  - `suggestions`: for `FALLBACK`, the keyword-matched guide articles as `Citation`s; empty otherwise.
  - `Citation` is `{"articleId": string, "title": string, "link": string | null}`. `link` is a relative client
    link into an existing guide (for example `?guide=doc-fee`) when one exists; otherwise `null` and the client
    shows the title as text.
- Refusals are `ProblemDetail` bodies with a `code` property and a plain `detail`:
  - `400` `TOTO_QUESTION_INVALID` (empty or over 1,000 characters), `TOTO_TURNS_INVALID` (more than six turns or
    a turn over 1,500 characters);
  - `429` `TOTO_RESTING` (account, client or daily spending cap); `detail` is the "Toto is resting" wording;
  - `503` `TOTO_UNAVAILABLE` (disabled since the last `GET`).
  Any other failure is a network or server error; the client shows its own plain wording and keeps the chat.

## Done when

- [x] Endpoint tests cover each limit, each cap, the disabled state, and a provider failure.
- [x] A test proves no question or answer text reaches logs or the usage store.
- [x] The spending ceiling stops calls once reached and resets the next day.
