# 140 — Check the dealer doc fee against state rules

**What to build:** A check of the dealer's documentary or administrative fee against each pilot state's own rule.
Nothing in the product checks doc fees today (`docs/jurisdiction-tax-packs.md`, ticket 137's out-of-scope notes).
The three pilot states regulate the fee differently, according to
`docs/research/state-vehicle-tax-and-fee-rules.md`; each value must be confirmed against the source before it ships:

- **Texas:** a $225 safe-harbor amount set by the OCCC. A dealer may charge more only after filing cost data with the
  OCCC, so a higher fee is a question to ask, not an automatic violation.
- **Florida:** no dollar cap. The research and F.S. 501.976 point to disclosure rules for the dealer fee; confirm
  exactly what the statute requires (wording on the paperwork, in advertised prices) before writing any finding.
- **North Carolina:** no cap found. Confirm before shipping.

Filed on 2026-10-18 after the pilot widened to three states (specification 2.4).

**Blocked by:** none in the dependency graph. It reads the same paperwork lines as ticket 139 (government fees),
so the two must keep their data and checks in separate files (see Scope).

**Status:** implemented on the branch, pending coordinator integration — 2026-10-18.

**Sources to read in full, not from search excerpts:** the
[Texas OCCC doc fee rule](https://occc.texas.gov/sites/default/files/2024-06/mvsf-docfee-adoption-fc-071124.pdf) and
7 TAC chapter 84 (the current amount, the electronic-filing condition if any, and how a dealer qualifies for more);
F.S. 501.976 (Florida dealer fee disclosure); and North Carolina's motor vehicle dealer statutes (G.S. 20-285 onward)
and NCDOJ consumer guidance, to confirm there is no cap. Every rule ships with its citation and the date it was
checked.

## Scope

- **A code-owned doc fee rule per state**, versioned (for example `doc-fee-rules.2026-10.v1`), effective-dated and
  with a review date, in its own resource file (for example `backend/src/main/resources/fees/doc-fee-rules.v1.json`).
  Rule kinds: a cap, a safe harbor (above it the dealer must have qualified), a disclosure requirement, or no rule.
  No migration unless clearly needed; if one is, agree the number with the coordinator first.
- **Recognising the fee:** dealer fee lines whose label means a documentary, administrative, processing or dealer
  service fee. Keep the label list in the rule file, next to the existing `nonTaxableCharges` approach in ticket
  137, and test it against labelled synthetic lines.
- **The check, per rule kind:**
  - safe harbor (Texas): above the amount, a finding that states the safe-harbor amount, says a dealer can charge
    more only after an OCCC filing, and gives the Buyer a question to ask ("Has your dealership filed for a doc fee
    above $225 with the OCCC?"). At or under it, report the identified fee informationally without the filing
    question. Possible aliases are always reported with clarification questions and separate totals; only clear
    documentary fee lines are compared with the safe harbor (operator correction after coordinator review).
  - disclosure (Florida, if confirmed): an informational line saying what the state requires and that the fee is
    negotiable, never that the dealer broke the rule unless the paperwork shows it, which this ticket does not try
    to detect.
  - no rule (North Carolina, if confirmed): an informational line saying the state sets no cap and the fee is
    negotiable.
- The finding appears where dealer-charge findings already appear: the Deal Review, the Desk Check and the "I'm at
  the dealer" result. Reuse the existing finding and report structures.
- Comparing the fee with what other dealers charge needs market data (ticket 27) and is out of scope.
- Put the check in its own class and package (for example `economics.fees.DocFeeCheck`). Ticket 139 adds a
  government fee check next to it, so do not create a shared fee abstraction both tickets edit.
- Buyer-facing copy passes `ClaimVocabulary` and `PilotCopyClaimsTest`. Copy never calls a fee illegal; it states
  the state's rule and the paperwork's number.

## Done when

- [x] The three states' rules ship with citations, checked dates and review dates, documented with sources and a
      worked example in `docs/jurisdiction-tax-packs.md` (or a new `docs/doc-fee-rules.md`).
- [x] Tests cover, with labelled synthetic cases: a Texas fee at the safe harbor, one above it, and one under it; a
      Florida and a North Carolina fee; a fee under an unusual label that is recognised and one that is not; two doc
      fee lines on one paperwork; and a state with no rule file entry, which makes no finding.
- [x] An acceptance test shows the Texas finding in the Deal Review and the Desk Check.
- [x] Buyer-facing copy passes `ClaimVocabulary` and `PilotCopyClaimsTest`.
- [x] Any value the sources contradict, or that the research doc got wrong, is corrected there and noted here.

## Implementation notes

Implemented on `feature/doc-fee-check`, pending coordinator integration. The independent
`economics.fees.DocFeeCheck` reads `fees/doc-fee-rules.v1.json`; no migration or shared
government-fee abstraction. [Rule documentation](../doc-fee-rules.md) records sources,
dates, matching behavior and worked examples. All rules are checked 2026-10-18 and due
for review 2027-01-18; Texas's $225 threshold is effective 2024-07-11.

Texas findings ask about notification and cost support, without asserting that a dealer
lacks a filing or needs prior approval. Florida and North Carolina use disclosure rules
without a cap. The research note's statement that uncapped states could only be checked
against market data was corrected: both have disclosure duties, and North Carolina's
G.S. 20-101.1 also explicitly permits reducing or waiving the fee.

The dealer's state controls this check, independently of the Buyer's tax jurisdiction.
Recognition version `doc-fee-rules.2026-10.v2` uses explicit labels and keyword patterns
in `DOC_FEE` and `POSSIBLE_DOC_FEE` tiers. Every recognised line is reported, including
clear fees at or below the safe harbor, possible aliases and unreadable amounts.
Only the identified `DOC_FEE` total triggers the Texas filing question above $225.
Possible aliases get questions about what they cover and whether they are the doc fee;
their subtotal and the combined total are shown separately without treating the
combined amount as an established doc fee. The $199 doc fee plus $300 origination
example reports $199 identified, $300 possible and $499 combined, with no filing
question. Restored aliases include origination, procurement, handling and clerical;
variants such as `DOCUMENTARY SVC FEE` and `Dealer Paperwork Charge` are recognised.
All original lines, their recognition tiers and evidence are retained.
Saved core reports and Deal Reviews contain `docFeeFindings` in the existing
discrepancy shape. Desk Check presents the rule under paperwork notes, without counting
it as an Agreed Price difference; the at-dealer first result uses its existing findings.
Existing report views and HTML/PDF exports display the rule and question. Browser checks
cover the populated report at 360 px in both themes; the new inline renderer is included
in an axe check and adds no separate panel.

Source-access limitation for coordinator review: the original OCCC adoption PDF returns
404, and the official Texas Register archive returns 403. They could not be read in full.
The currently linked official OCCC Bulletin B16-5 was read in full and independently
confirms the shipped $225 threshold, effective date, notice/cost analysis and ALECS filing
requirements. The full reproduced 7 TAC 84.205 text was also reviewed. No amount was
shipped from a search excerpt. Details and working source links are in the rule document.
