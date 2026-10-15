# 121 — Ask Toto from any page

**What to build:** An "Ask Toto" button, available on every page when `GET /api/v1/toto` says Toto is
available, opens a chat panel. The Buyer types a question, sees the answer with links to the guide articles
it cites, and can keep asking. The chat lives only in the page's memory; leaving or reloading the page
clears it, and the panel says so.

**Blocked by:** 120 for the endpoint shape. The panel can be built against a mocked endpoint first.

**Status:** open — the panel is built against the agreed interface in ticket 120, as of 2026-10-15. Remaining:
the live endpoint (tickets 119 and 120) and the operator's trademark check on the name "Toto". `TotoChat.tsx`
is mounted in `App.tsx` and renders only when `GET /api/v1/toto` reports `available: true`, so Buyers see
nothing until then. It is a full-screen modal dialog at 720 px and below, and a non-modal 420 px side panel
above that. It sends the last six answered turns, with Toto's earlier text cut to the 1,500-character turn
limit. A citation is a link only to a guide `ExplainerGuides.tsx` knows; otherwise its title shows as text.
Guide links open without a reload, so the chat survives. Tests: `TotoChat.test.tsx`, the Toto sweep in
`Accessibility.test.tsx`, and `e2e/ask-toto-narrow-width.spec.ts`.

## Scope

- A `TotoChat.tsx` panel: a dialog on narrow screens, a side panel on wide ones. Keep changes to the shared
  hot files (`App.tsx`, `styles.css`) to mounting the button and panel.
- A short notice at the top, always visible: Toto is an AI guide that answers from otdog's guides, can't see
  your case or documents, and can't give legal or tax advice; don't type personal details; questions are
  sent to an AI provider and not saved by otdog. Copy must pass `PilotCopyClaimsTest`: no "negotiates for
  you" or "on your behalf".
- Suggested starter questions (static, code-owned), for example "What is a doc fee?" and "What does the
  four-square do?".
- The answer shows its cited article titles as links into the existing guides. Decline and fallback
  answers show their fixed wording.
- Loading, cap ("Toto is resting") and error states with plain wording.
- Accessibility: focus moves into the panel and returns on close, Escape closes it, new answers are
  announced through a polite live region, and everything works by keyboard.
- Before launch, the operator confirms the name "Toto" (a quick trademark search; it is also a large
  plumbing-fixture brand).

## Done when

- [x] Component tests cover asking, answering with citations, declining, the fallback, caps and the hidden
      state when Toto is unavailable.
- [x] The panel is registered in the `Accessibility.test.tsx` axe sweep with no violations.
- [x] The 360 px browser gate passes with the panel open, with no horizontal overflow.
- [x] `npm test`, `npm run build` and `npx playwright test` pass.
