# 146 — Show the Buyer screens in Spanish

**What to build:** A Spanish version of every Buyer screen, with a language switch in the header. It builds on the
English catalogue from ticket 145 and follows the decisions in ticket 144.

**Blocked by:** 145 (catalogues and per-language claim rules). Ticket 144's decisions and working defaults are
enough to start.

**Status:** complete — on 2026-10-21, delivered with ticket 147 on `feature/spanish-screens-and-findings` (each ticket
on its own branch, merged and tested together). With Spanish chosen, every Buyer screen reads in Spanish, apart from
Toto (English-only for now, decision 7) and the dealer's own wording quoted from the paperwork.

What is in place:

- `i18n/es.ts` and `i18n/es/*.ts` cover every English id, checked by `translation<typeof en>()`. The register is
  neutral US Latin American Spanish with "usted". Kept in English: brand and product names, the synthetic example
  data, and the email subject sent to dealers (working default 4).
- `i18n/glossary.ts` holds 36 terms with their Spanish form and whether the first use on a screen adds the English
  term, as in "precio total final (out-the-door)". `es.test.ts` checks every screen against it.
- `ThemeMenu` became `ThemeLanguageMenu`: one header button, two `menuitemradio` groups (Theme, Language), each
  language name in its own language and `lang`. Its name reads "Theme and language: Dark, English".
- The choice is stored in the browser (`otdog-language`) and, when signed in, on the Buyer Account
  (`V63`, `PUT /api/v1/account/language`). A first visit follows `navigator.languages`. `theme-init.js` sets
  `<html lang>` before first paint. Account emails not tied to a case use the saved language.
- `ProhibitedClaims.SPANISH` lists the forbidden claims in Spanish; Spanish text is also checked against the English
  list. "Garantía" alone is allowed, since it also means a vehicle warranty.
- The ten `String(error)` sites from ticket 145 show only the message (`i18n/errors.ts`).
- Playwright runs every spec in four projects (narrow and narrow-dark, English and Spanish); in Spanish it fails on
  any English catalogue copy on screen. `Accessibility.es.test.tsx` re-runs the axe sweep in Spanish.

Left for later:

- The client has no Terms of Service or Privacy Policy links yet, so decision 6 had nothing to apply to.
- The Desk Check's total-effect sentence is still joined from whole-message clauses; the Spanish reads correctly.
- The Spanish copy and the glossary are the implementing agent's own (decision 1) and have had no human review.

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

- [x] With Spanish chosen, every screen a Buyer can reach shows Spanish copy and `<html lang="es">`. A test walks
      the Buyer journeys in Spanish and fails on any English string outside the allow-list (brand name, the English
      dealer terms from the glossary, amounts).
- [x] The theme and language menu works by keyboard and screen reader, and the language choice is remembered
      across reloads and, when signed in, across devices.
- [x] `PilotCopyClaimsTest` checks the Spanish catalogue against the Spanish patterns and passes.
- [x] The 360 px Playwright gate and the axe sweep run in Spanish as well as English, in both themes, and pass.
