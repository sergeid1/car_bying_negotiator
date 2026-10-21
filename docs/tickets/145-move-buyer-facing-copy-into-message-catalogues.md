# 145 — Move Buyer-facing copy into message catalogues

**What to build:** The groundwork for more than one language, shipped with English only, so Buyers see no change.
On 2026-10-19 every component in `frontend/src` (about 20,000 lines of TSX) writes its English text inline,
`index.html` hard-codes `lang="en"`, and 30 source files format money and dates with a hard-coded `"en-US"`
(51 call sites across `toLocaleString`, `toLocaleDateString` and `Intl.NumberFormat`). There is no translation library.

**Blocked by:** none; ticket 144 recorded the language-choice decision on 2026-10-19. 143 should land first, because both edit the footer. This
ticket touches nearly every client file, including the hot files, so run it when no other client ticket is in
progress, and split it into several pull requests (for example by journey) if that keeps reviews readable.

**Status:** complete — on 2026-10-20. Every Buyer-visible string in `frontend/src` comes from the English catalogue;
English reads as before. Delivered in two pull requests: the groundwork and site shell (#87), then the six journeys
together (`feature/catalogue-integration`), each journey moved on its own branch and merged and tested as one.

What is in place, with no new dependency:

- `frontend/src/i18n/en.ts` is the English catalogue. Each journey's copy is in its own file under `i18n/en/`
  (`account`, `paperwork`, `dealer`, `messages`, `money`, `content`), one object per component. A message is a whole
  sentence with named `{placeholders}`; a count-dependent one is a `plural({ one, other, … })` chosen with
  `Intl.PluralRules`. `translation<typeof en>()` checks another language at compile time: a missing id, an id English
  lacks, or a renamed, added or dropped placeholder fails `tsc` (proved by the `@ts-expect-error` cases in
  `messages.test.tsx`).
- `useMessages()` returns `m` (the current catalogue), `fill` for text and `fillNodes` for a sentence holding a link.
  `LanguageProvider` in `main.tsx` sets `<html lang>`; without a provider, as in unit tests, English is served.
- `frontend/src/i18n/format.ts` holds the shared `money`, `number`, `date`, `dateTime` and `list` formatters. Every
  former `"en-US"` call site and every locale-less `toLocaleString()` goes through them (`en` → `en-US`). `fill`
  formats number values with grouping, so years, versions and similar values are passed as strings.
- `inlineCopy.test.ts` parses each component and flags JSX text, copy attributes (`aria-label`, `alt`, `title`,
  `placeholder`, `label`, …), string children, sentences passed to `set…` state setters or `new Error`, and
  `document.title`. Allowed: the brand name, punctuation and numbers. Every journey's pending list is empty. The guard
  cannot see copy in module-level tables; those were moved by hand and swept again at integration.
- `ProhibitedClaims.BY_LANGUAGE` holds claim patterns per language (English only). `PilotCopyClaimsTest` checks
  `i18n/<lang>.ts` and `i18n/<lang>/*.ts` with that language's patterns, fails when a catalogue has no patterns for
  its language, and reads the footer disclosures and the partner kit's terms from the catalogue.
  `reviewed-claim-phrases.json` (v2) records each entry's language. `ClaimVocabulary` is unchanged: it checks the
  backend's generated English findings, which ticket 147 makes language-aware.

Left for later tickets:

- Ten places across five files show `String(error)`, with an English "Error: " prefix: `PricePlan.tsx` (2),
  `OutsideFinancing.tsx`, `CoreDealReport.tsx`, `DealerFinancing.tsx` (3) and `NegotiationMandate.tsx` (3).
  Ticket 146 should show only the message.
- Some screens turn server codes into English-like words (`humanize()` in `App.tsx` for the synthetic report, the
  precision and pointer text in `CoreDealReport.tsx`, the unknown-term fallback in `RevisedOfferReview.tsx`, the
  confidence in `CandidateVehicles.tsx`). These belong to ticket 147.
- A few sentences are still joined from whole-message clauses in code, with the words in the catalogue: the Desk
  Check's total-effect sentence, the case flow's "Done: …" list, and short detail lists in the desk tactics, trade-in
  and test drive. Ticket 146 may rewrite them as whole sentences for Spanish.
- Some exported helpers default to the English catalogue so tests can call them directly (`EvidenceSource`,
  `InsuranceQuotes`, `LoanPreApprovals`, `OfferVerification`, `DealerDocumentUpload`), and `CaseFlow` exports
  `CASE_STEPS` built from English for its tests. Every screen passes the current language.

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

- [x] No Buyer-visible string is written inline in a component. A test or lint rule flags new inline text in JSX,
      with a narrow allow-list (brand name, punctuation).
- [x] The English site looks and reads exactly as before. The existing unit, accessibility and Playwright suites
      pass unchanged apart from how they look up text.
- [x] A test proves that a catalogue missing an id or with mismatched parameters fails the type check.
- [x] `<html lang>` follows the current language, and money and dates format through the shared formatters.
- [x] `PilotCopyClaimsTest` reads per-language claim patterns, and the English results are unchanged.
- [x] `npm test`, `npm run build`, `npx playwright test` and `mvn clean verify` pass.
