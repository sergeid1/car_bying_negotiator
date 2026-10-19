# 143 — Replace the footer box with a short statement

**What to build:** Replace the "What this is, and what it is not" card at the bottom of every page with two plain
lines of small print. Today the footer is a bordered card with a heading and three paragraphs, shown on every page
on top of the home page's trust line and the notes inside each section. Operator request on 2026-10-19: keep the
substance, make it tight.

**Blocked by:** none. It edits `frontend/src/App.tsx` and `frontend/src/styles.css`, both hot files, so do not run it
in parallel with another client ticket. Ticket 145 moves the same copy into a message catalogue; land this first.

**Status:** open — filed on 2026-10-19.

## Scope

- **The new footer copy**, as two short lines with no card, border or heading:

  > otdog is on your side. No dealer pays us, and no company can buy a better position.
  > [How otdog makes money]
  >
  > It checks the numbers on the dealer's paperwork. It isn't legal, tax or mechanical advice, and it can't promise
  > savings. You send every email and make every decision. · [Referral partner kit]

  Small adjustments to the wording are fine. The four points must remain, because Sections 1.2, 1.3 and 13 of the
  specification require them: dealers pay nothing and commercial relationships do not change what is shown; the
  product is not legal, tax or mechanical advice; it does not guarantee savings or an outcome; the Buyer sends every
  message and makes every decision.
- **"Safe to sign."** The current footer says the product "does not establish that a document is safe to sign".
  On 2026-10-19 only the case page's Desk Check summary (`CaseFlow.tsx`) says "This checks the numbers on the
  paperwork. It is not a legal review. Read every page before you sign." Add the same note to the Deal Analysis
  Report (unless its stored limitations already say it), the full Desk Check view and the "I'm at the dealer"
  result. The footer can then drop the phrase, because the note sits next to the results it applies to.
- **"otdog never contacts a dealer itself."** The footer drops this sentence; "You send every email" covers it in the
  footer. The partner kit (`ReferralPartnerKit.tsx`) keeps its own copy of it.
- **Accessibility:** keep the footer as a `contentinfo` landmark. The section keeps an accessible name, for
  example "About otdog". Links stay underlined, and the text meets AA contrast in both themes.
- **Styles:** remove the footer card styles (`.scope-columns` and the footer's use of `.limitations`). The report's
  `<aside className="limitations">` (`App.tsx`, "Report limitations") uses the same class, so leave it as it looks
  today.
- **Claim rules:** update `backend/src/test/resources/pilot/reviewed-claim-phrases.json`. Remove the two `App.tsx`
  entries the old wording needed ("…or mechanical advice, it does not establish that a document is safe to sign" and
  "otdog never contacts a dealer itself."). Add an entry for each new phrase that `PilotCopyClaimsTest` flags, with
  its justification.
- The `#scope` anchor: confirm nothing links to it (none did on 2026-10-19), then drop or keep the id.

## Done when

- [ ] Every page's footer shows the two lines and both links, and no card.
- [ ] The report, the Desk Check summary and full view, and the "I'm at the dealer" result each show a "not a legal
      review, read every page before you sign" note, and a test covers each.
- [ ] `PilotCopyClaimsTest` passes with the updated reviewed phrases, and no unused entries remain.
- [ ] The report's limitations aside looks unchanged.
- [ ] `npm test`, `npm run build` and `npx playwright test` pass, including the 360 px and dark-theme runs and the
      referral partner kit link test, which finds the link in `contentinfo`.
