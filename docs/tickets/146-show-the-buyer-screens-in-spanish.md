# 146 — Show the Buyer screens in Spanish

**What to build:** A Spanish version of every Buyer screen, with a language switch in the header. It builds on the
English catalogue from ticket 145 and follows the decisions in ticket 144.

**Blocked by:** 145 (catalogues and per-language claim rules). Ticket 144's decisions and working defaults are
enough to start.

**Status:** open — filed on 2026-10-19.

## Scope

- **`es.ts` catalogue** covering every id in `en.ts`, written by the implementing agent (decision 1 of ticket 144).
  The type checker from ticket 145 enforces parity, so a missing string fails the build instead of falling back to
  English silently. Write for understanding: plain sentences a first-time car buyer follows, not a word-for-word
  copy of the English.
- **The glossary** from decision 3 of ticket 144, as code-owned data in `frontend/src/i18n/glossary.ts`, used so the
  same term is translated the same way everywhere. The first use on a screen shows the English term the dealer uses,
  where the decision says to.
- **One menu for theme and language** (decision 8 of ticket 144). The header's existing appearance button
  (`ThemeMenu.tsx`) opens a menu with two labelled groups: Theme (System, Light, Dark) and Language (English,
  Español). Each language name is written in its own language and carries its own `lang` attribute. The button keeps
  its theme icon; its accessible name becomes something like "Theme and language: Dark, English". No header button
  is added, so the phone header keeps its current rows. Keep the menu's keyboard behaviour (arrow keys, Home, End,
  Escape), with each group as its own `menuitemradio` set. Rename the component and update
  `theme-menu.spec.ts` accordingly.
- **Remembering the choice.** The language is remembered in the browser, wrapped in `try`/`catch` like the theme
  choice in `theme.ts`. A signed-in Buyer's choice is also saved on the Buyer Account (a small API change and
  migration), so it follows them to another device.
- **First visit** follows `navigator.languages` when Spanish comes first, otherwise English.
- **Spanish claim rules.** Add Spanish patterns to `ClaimVocabulary` for each English claim the spec forbids: safe
  to sign, guaranteed savings, the best or cheapest price or rate, negotiating or acting for the Buyer, contacting
  dealers. Add any reviewed Spanish phrases that negate them to `reviewed-claim-phrases.json` with justifications.
- **Layout.** Spanish runs about 20–30% longer than English. Check every screen at 360 px in both themes for
  overflow, wrapped buttons and truncated tab labels, especially the site tabs ("Check my deal", "Example report",
  "How it works", "Guides"), whose phone labels are already short forms.
- **Legal links** follow decision 6 of ticket 144.
- **The example report and the "How it works" screens** show Spanish copy around the synthetic figures. Report
  findings themselves come from the backend and are ticket 147's.

## Done when

- [ ] With Spanish chosen, every screen a Buyer can reach shows Spanish copy and `<html lang="es">`. A test walks
      the Buyer journeys in Spanish and fails on any English string outside the allow-list (brand name, the English
      dealer terms from the glossary, amounts).
- [ ] The theme and language menu works by keyboard and screen reader, and the language choice is remembered
      across reloads and, when signed in, across devices.
- [ ] `PilotCopyClaimsTest` checks the Spanish catalogue against the Spanish patterns and passes.
- [ ] The 360 px Playwright gate and the axe sweep run in Spanish as well as English, in both themes, and pass.
