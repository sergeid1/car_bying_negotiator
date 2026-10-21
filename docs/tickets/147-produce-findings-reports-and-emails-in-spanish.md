# 147 — Produce findings, reports and emails in Spanish

**What to build:** Spanish for the Buyer-facing text the backend writes: report findings and limitations, Desk
Check and "I'm at the dealer" results, desk tactics, fee, doc-fee and add-on findings, claim-checked explanations
(ticket 37), reply and counteroffer drafts, notification and account emails, and the PDF report.

Today this text is English sentences built on the server and stored in the immutable report payload. For example,
`DealAnalysis.topReasons` is a list of English strings, and `CoreDealReport.tsx` matches the out-the-door total by its
English label "Out-the-door total". The client cannot translate stored sentences.

**Blocked by:** 145. Ticket 144 recorded decisions 5 and 8; decision 4 is a working default. It can run in
parallel with 146, since it is mostly backend work.

**Status:** complete — on 2026-10-21, delivered with ticket 146 on `feature/spanish-screens-and-findings`. A Spanish
Buyer sees findings, limitations, Desk Check and "I'm at the dealer" results, desk tactics, explanations, the quote
comparison, emails and the PDF in Spanish, with paperwork labels as printed.

What is in place:

- Reports use `core-deal-analysis-report.v2`, which adds a `textFindings` sidecar keyed by JSON pointer: each entry
  has a stable `code`, typed `parameters` and the stored English `text`. Stored reports are never rewritten; v1
  reports and unknown codes render their stored English. Codes and both languages live in `report/FindingText.java`;
  `frontend/scripts/generate-finding-catalogues.mjs` regenerates `i18n/{en,es}/findingTemplates.ts` from it.
- `i18n/findings.ts`: `findingText(language, finding)`, `localizeReport` (a presentation copy) and
  `dealerReadingCopy`. The report total is found by pointer, never by label. The journey quote comparison sends a
  coded twin beside each English name and reason and sorts by category code.
- Each Buying Case records its language (`V64`, set at intake, changed from "This case" via
  `PUT /api/v1/cases/{caseId}/language`). Case emails, mandate and appointment reminders, and the PDF use it.
  Sign-in requests carry the viewer's language.
- Spanish explanations are generated with their own prompt (`report-explanation-es-prompt.v1`), checked by Spanish
  grounding terms in `ClaimVocabulary` and stored separately (`V65`); a failed check falls back to the templated
  Spanish findings.
- Dealer drafts stay in English. A Spanish viewer gets a reading copy marked "Para que usted lo lea — el
  concesionario recibe el texto en inglés", and the approve button names the English text of the revision sent.

Left for later:

- `serverText` and `dealerReadingCopy` translate English text served without a code by matching it against the
  templates; text that matches none stays English, and Buyer-edited drafts show "no reading copy available".
- The Spanish explanation prompt must pass its own shadow comparison before model rollout.
- The PDF's raw saved-payload appendix stays as recorded.

## Scope

- **Findings carry codes and parameters.** Each finding, limitation and material amount gains a stable code and
  typed parameters (amounts, labels as printed on the paperwork, rule versions) next to its English text. The client
  renders the code through the catalogue for the current language and falls back to the stored English text for
  codes it does not know. Reports already stored keep their English text and still render. Payloads stay
  immutable; add a `schemaVersion`, never rewrite old reports. The client finds amounts by `pointer`, never by
  English label.
- **Text from the paperwork stays as printed.** A fee label such as "DOC FEE" is a quote from the dealer's document
  and is never translated. The Spanish finding names it and explains it, for example "El cargo «DOC FEE» es la tarifa
  de documentación…".
- **The case's language.** Each Buying Case records its Buyer's language (decision 8), set at intake and changeable
  from "This case". Emails and the PDF use it. The on-screen report follows the viewer's current choice.
- **Explanations** (ticket 37) are generated with the Spanish claim rules from ticket 146 and checked by the same
  grounding rules. A Spanish explanation that fails the check falls back to the templated Spanish findings, never
  to unchecked text.
- **Dealer drafts** follow working default 4: drafts to dealers stay in English. When the Buyer reads Spanish, show
  a Spanish reading copy marked "for you to read — the dealer receives the English text", and make the approval
  name the exact English text that is sent. The outbound gate (ticket 59) checks the text that is sent.
- **Emails** (sign-in, recovery, notifications, test-drive reminders) and **the PDF** use the case's or account's
  language. Subject lines are translated too.
- Spanish claim patterns from ticket 146 run over backend templates as well, through `ClaimVocabulary`.

## Done when

- [x] A Spanish Buyer sees every finding, limitation, Desk Check result, desk tactic and explanation in Spanish, and
      paperwork labels as printed.
- [x] Reports stored before this ticket still render, in English, and a test proves it.
- [x] No client code finds a report amount by English label.
- [x] Dealer drafts stay in English with a Spanish reading copy, and a test proves the approved text is the text
      sent.
- [x] Each email type and the PDF have a Spanish version, with tests on a Spanish case.
- [x] Backend claim checks run in both languages, and `mvn clean verify` passes.
