# 121 — Ask Toto from any page

**What to build:** An "Ask Toto" button, available on every page when `GET /api/v1/toto` says Toto is
available, opens a chat panel. The Buyer types a question, sees the answer with links to the guide articles
it cites, and can keep asking. The chat lives only in the page's memory; leaving or reloading the page
clears it, and the panel says so.

**Blocked by:** 120 for the endpoint shape. The panel can be built against a mocked endpoint first.

**Status:** open — filed on 2026-10-14.

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

- [ ] Component tests cover asking, answering with citations, declining, the fallback, caps and the hidden
      state when Toto is unavailable.
- [ ] The panel is registered in the `Accessibility.test.tsx` axe sweep with no violations.
- [ ] The 360 px browser gate passes with the panel open, with no horizontal overflow.
- [ ] `npm test`, `npm run build` and `npx playwright test` pass.
