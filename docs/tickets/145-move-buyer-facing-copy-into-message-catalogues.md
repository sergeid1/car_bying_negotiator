# 145 — Move Buyer-facing copy into message catalogues

**What to build:** The groundwork for more than one language, shipped with English only, so Buyers see no change.
On 2026-10-19 every component in `frontend/src` (about 20,000 lines of TSX) writes its English text inline,
`index.html` hard-codes `lang="en"`, and 30 source files format money and dates with a hard-coded `"en-US"`
(51 call sites across `toLocaleString`, `toLocaleDateString` and `Intl.NumberFormat`). There is no translation library.

**Blocked by:** 144 for the language-choice decision. 143 should land first, because both edit the footer. This
ticket touches nearly every client file, including the hot files, so run it when no other client ticket is in
progress, and split it into several pull requests (for example by journey) if that keeps reviews readable.

**Status:** open — filed on 2026-10-19.

## Scope

- **Message catalogues without a new runtime dependency.** One typed catalogue per language under
  `frontend/src/i18n/`, for example `en.ts`, with message ids grouped by feature. The type checker must fail when a
  language is missing an id or a message's parameters differ from English. Plurals and lists use `Intl.PluralRules`
  and `Intl.ListFormat`, not hand-built English rules ("1 difference" / "2 differences"). Adding a library such as
  FormatJS or i18next needs a reason recorded in this ticket: bundle size and the client CSP (ticket 79) count
  against it.
- **A `useMessages()` hook** (or equivalent) that returns the current language's messages, and a language provider
  at the app root. English is the only shipped language and the fallback for anything missing.
- **Move every Buyer-visible string** into the English catalogue: headings, labels, buttons, help text, errors,
  `aria-label`s, alt text, `title`s and the `document.title`. Sentences are kept whole with named parameters, never
  assembled from fragments, because Spanish word order differs.
- **Locale-aware formatting.** Replace the hard-coded `"en-US"` call sites with shared formatters that take the current
  language. Amounts stay in US dollars in every language.
- **The page language.** Set `<html lang>` from the current language, and give each catalogue's language name its
  own `lang` attribute where it appears in the switch (WCAG 3.1.1 and 3.1.2).
- **Claim rules read the catalogues.** `PilotCopyClaimsTest` scans `frontend/src`, so it already sees the English
  catalogue. Give `ClaimVocabulary` and `reviewed-claim-phrases.json` a language field, so ticket 146 can add Spanish
  patterns ("garantizado", "seguro para firmar", "el mejor precio", …) that are checked against the Spanish catalogue
  only.
- **Tests** look up English copy through the catalogue, or keep their literal strings. Either is fine, as long as a
  test still fails when the visible copy changes in an unreviewed way.

## Done when

- [ ] No Buyer-visible string is written inline in a component. A test or lint rule flags new inline text in JSX,
      with a narrow allow-list (brand name, punctuation).
- [ ] The English site looks and reads exactly as before. The existing unit, accessibility and Playwright suites
      pass unchanged apart from how they look up text.
- [ ] A test proves that a catalogue missing an id or with mismatched parameters fails the type check.
- [ ] `<html lang>` follows the current language, and money and dates format through the shared formatters.
- [ ] `PilotCopyClaimsTest` reads per-language claim patterns, and the English results are unchanged.
- [ ] `npm test`, `npm run build`, `npx playwright test` and `mvn clean verify` pass.
