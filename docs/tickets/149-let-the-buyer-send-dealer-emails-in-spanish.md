# 149 — Let the Buyer send dealer emails in Spanish

**What to build:** A Buyer can choose Spanish for a dealer's emails. The draft is then written in Spanish, checked by
the same outbound rules as English (`docs/outbound-gate.md`), and the Buyer approves and sends the exact Spanish
text from their own email. English stays the default for every dealer.

**Blocked by:** none. Ticket 144 recorded the decision on 2026-10-21 (decision 4, replacing the working default).
Builds on ticket 59 (outbound gate), ticket 146 (Spanish claim patterns) and ticket 147 (Spanish reading copies).

**Status:** complete — on 2026-10-21. `dealers.email_language` and `outbound_drafts.language` (V66). The Buyer picks
the language on a dealer's quote-request card
(`PUT /api/v1/cases/{caseId}/quote-requests/dealers/{dealerId}/email-language`). A switch re-prepares only that dealer's unapproved quote requests; the old one is kept as `REPLACED`. Every other draft
keeps the language it was written in, including on later rechecks. `outbound-gate.v5` matches a Spanish draft only
against Spanish counterparts of the English sentences (grounded questions, the price question, disclosures, quote
request, confirmation, acceptance and test-drive messages). It adds Spanish keyword checks and the Spanish claim list
(`PROHIBITED_CLAIM`), and recorded v4 decisions prove English results are unchanged. The gate's reasons now show in
the site's language.

Left for later:

- The Spanish dealer sentences need a native-speaker review before the pilot.
- Spanish accepts only one-for-one translations of the English sentences.
- Spanish quote requests and acceptances write "precio total final (out-the-door)", the glossary style, so the
  dealer also sees the English term.
- The report's negotiation draft and the reply assistant are not per dealer and do not pass the gate; they stay
  English with a Spanish reading copy.
- A switch re-prepares only unapproved quote requests, not unapproved counteroffers, acceptances or test-drive
  drafts. Those follow the dealer's language when they are next prepared.
- Test-drive replies that use "opción N" are parsed, but no dedicated test covers it.

## Scope

- **The choice.** Each dealer on a Buying Case has an email language, English by default. The Buyer can switch it to
  Spanish from that dealer's draft, in any site language. It is stored with the case, exported and deleted with it,
  and recorded on each draft snapshot. Switching it does not change drafts already approved.
- **Spanish templates in the outbound gate.** Every accepted English paragraph gets a Spanish counterpart, including
  the questions `GroundedLanguage.generate` produces from a finalized report. Amounts follow the same traceability
  rules; Spanish money formats are parsed and checked as strictly as English, and anything unrecognised fails
  closed. This changes the gate policy, so `OutboundGate.VERSION` moves to `outbound-gate.v5` and the corpus gains
  Spanish cases, including the attempts English already rejects (appended assertions, competing-offer claims,
  spelled-out amounts).
- **Claim rules.** A Spanish draft is checked against the Spanish and English prohibited-claim lists, as ticket 146
  does for screens.
- **Approval.** A Spanish draft needs no reading copy. The approve button names the exact Spanish text sent, as it
  does for English. The subject line follows the dealer's language.
- **Paperwork labels** stay as printed in the dealer's document, quoted in «» as on the screens.
- **A plain warning** next to the choice says the dealer may answer in English, and otdog still reads only English
  paperwork (decision 5).

## Done when

- [x] A Buyer can pick Spanish for one dealer, and that dealer's drafts and subject lines are in Spanish while other
      dealers stay English.
- [x] Every Spanish draft passes through `outbound-gate.v5`, and a corpus test shows Spanish attempts fail wherever
      the English equivalents do.
- [x] A test proves the approved Spanish text is the text sent.
- [x] Existing English drafts and their gate decisions are unchanged, and a test proves it.
- [x] `mvn clean verify`, `npm test`, `npm run build` and the Playwright gate pass in both site languages.
