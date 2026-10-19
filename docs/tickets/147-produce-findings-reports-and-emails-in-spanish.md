# 147 — Produce findings, reports and emails in Spanish

**What to build:** Spanish for the Buyer-facing text the backend writes: report findings and limitations, Desk
Check and "I'm at the dealer" results, desk tactics, fee, doc-fee and add-on findings, claim-checked explanations
(ticket 37), reply and counteroffer drafts, notification and account emails, and the PDF report.

Today this text is English sentences built on the server and stored in the immutable report payload. For example,
`DealAnalysis.topReasons` is a list of English strings, and `CoreDealReport.tsx` matches the out-the-door total by its
English label "Out-the-door total". The client cannot translate stored sentences.

**Blocked by:** 144 (decisions 4 and 8) and 145. It can run in parallel with 146, since it is mostly backend work.

**Status:** open — filed on 2026-10-19.

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
- **Dealer drafts** follow decision 4: English by default, Spanish when the Buyer picks it for that dealer. When the
  draft is in English and the Buyer reads Spanish, show a Spanish reading copy marked "for you to read — the dealer
  receives the English text", and make the approval name the exact text that is sent. The outbound gate (ticket 59)
  checks the text that is sent.
- **Emails** (sign-in, recovery, notifications, test-drive reminders) and **the PDF** use the case's or account's
  language. Subject lines are translated too.
- Spanish claim patterns from ticket 146 run over backend templates as well, through `ClaimVocabulary`.

## Done when

- [ ] A Spanish Buyer sees every finding, limitation, Desk Check result, desk tactic and explanation in Spanish, and
      paperwork labels as printed.
- [ ] Reports stored before this ticket still render, in English, and a test proves it.
- [ ] No client code finds a report amount by English label.
- [ ] Dealer drafts follow decision 4, and a test proves the approved text is the text sent.
- [ ] Each email type and the PDF have a Spanish version, with tests on a Spanish case.
- [ ] Backend claim checks run in both languages, and `mvn clean verify` passes.
