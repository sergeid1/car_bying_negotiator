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

**Status:** open — filed on 2026-10-18.

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
    above $225 with the OCCC?"). At or under it, no finding.
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

- [ ] The three states' rules ship with citations, checked dates and review dates, documented with sources and a
      worked example in `docs/jurisdiction-tax-packs.md` (or a new `docs/doc-fee-rules.md`).
- [ ] Tests cover, with labelled synthetic cases: a Texas fee at the safe harbor, one above it, and one under it; a
      Florida and a North Carolina fee; a fee under an unusual label that is recognised and one that is not; two doc
      fee lines on one paperwork; and a state with no rule file entry, which makes no finding.
- [ ] An acceptance test shows the Texas finding in the Deal Review and the Desk Check.
- [ ] Buyer-facing copy passes `ClaimVocabulary` and `PilotCopyClaimsTest`.
- [ ] Any value the sources contradict, or that the research doc got wrong, is corrected there and noted here.
