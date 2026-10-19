# Dealer documentary fee rules

Ticket 140 uses `backend/src/main/resources/fees/doc-fee-rules.v1.json`, version
`doc-fee-rules.2026-10.v1`. All entries were checked on **2026-10-18** and are due for
review on **2027-01-18**. No database migration is needed.

## Sources and scope

- **Texas:** [OCCC Bulletin B16-5, revised July 2, 2024](https://occc.texas.gov/wp-content/uploads/2025/11/b16-5-mvsf-doc-fee-filing-instructions-7-2-24_0.pdf),
  read in full (eight pages), confirms the $225 threshold effective July 11, 2024.
  Before exceeding it, a seller files notice and cost support with OCCC; ALECS is the
  required filing channel. This is an electronic **OCCC notification** requirement,
  not a condition that the dealer participate in electronic vehicle titling.
  [7 TAC 84.205](https://www.law.cornell.edu/regulations/texas/7-Tex-Admin-Code-SS-84-205),
  full reproduced rule text reviewed, describes reasonableness, per-location notice,
  cost support and possible restitution. Prior OCCC approval is not required.
  The [adoption PDF named in the ticket](https://occc.texas.gov/sites/default/files/2024-06/mvsf-docfee-adoption-fc-071124.pdf)
  returns 404; the official Texas Register archive returns 403. Those two documents
  could not be read in full in this environment. The shipped amount and filing rule
  are independently confirmed in the current official bulletin linked from
  [OCCC's disclosures page](https://occc.texas.gov/industry/motor-vehicle-sales-finance-mvsf/bulletins-disclosures/).
  The bulletin is the shipped citation, rather than an inaccessible adoption URL.
- **Florida:** [F.S. 501.976](https://www.leg.state.fl.us/Statutes/index.cfm?App_mode=Display_Statute&URL=0500-0599/0501/Sections/0501.976.html),
  read in full. Subsections (16) and (18) impose advertising and predelivery disclosure
  duties, without a dollar ceiling. The predelivery disclosure must identify dealer
  costs and profit for preparation work; the rule file paraphrases it rather than
  treating every administrative label as proof that a disclosure is missing.
  Joint advertising has an exception for charges varying among participating dealers,
  whose nature still must be disclosed. Buyers may ask to negotiate the fee or vehicle
  price; the check promises no reduction.
- **North Carolina:** [G.S. 20-101.1](https://www.ncleg.gov/EnactedLegislation/Statutes/HTML/BySection/Chapter_20/GS_20-101.1.html),
  read in full, requires dealer signage, price-advertising disclosure and a separately
  identified amount on the order or bill of sale. It permits reductions and waivers.
  No dollar ceiling was found in this section or the
  [dealer licensing article, G.S. 20-285 onward](https://www.ncleg.gov/EnactedLegislation/Statutes/HTML/ByArticle/Chapter_20/Article_12.html).
  [NCDOJ Car Shopping Terms](https://ncdoj.gov/protecting-consumers/automobiles/car-shopping-terms/),
  read in full, explicitly describes documentary fees as negotiable. The online
  registration fee exception in 20-101.1(d) is excluded by the explicit label list.

Florida and North Carolina use `DISCLOSURE`, with no threshold. “No cap” does not
mean “no rule.” `effectiveFrom` for these two entries records the date of this
verified coverage snapshot, not a claim about the original statute enactment date.
Texas's `effectiveFrom` is the confirmed threshold change date. The check suppresses
entries before their coverage date or after their review date. Saved reports retain
the rule snapshot, citation, checked date and review date.

## Recognition and worked examples

The explicit `labels` array is code owned. Matching ignores punctuation, case and
extra spaces, and only accepts dealer fee lines in USD with readable nonnegative
minor-unit amounts. It does not interpret generic protection packages, title fees,
government fees or electronic registration fees as documentary fees. Unrecognised
labels remain in the existing charge findings and do not receive a state-rule claim.

For a Texas dealer's paperwork, `Doc fee $150` plus `Clerical fee $150` becomes one
question about a **$300** combined documentary charge. Both source lines and evidence
regions survive in `observedEvidence.lines`. A single $225 fee or a $224.99 fee produces
no finding. At $225.01 the Buyer sees the safe harbor, OCCC filing requirement and
a question asking whether the dealer filed the notification and cost analysis.
The check does not look up the filing or decide that the dealer breached a rule.

A Florida `Dealer service fee $999` or North Carolina `Administrative fee $899`
produces information about disclosure and negotiability. The check does not inspect
the advertisement, dealership sign or contractual disclosure. Unsupported dealer
states, ambiguous dealer ZIP codes, expired rules and unreadable fee amounts produce
no state-rule finding. Buyer tax jurisdiction never substitutes for dealer state.

## Integration

`DocFeeCheck` resolves a dealer ZIP using the existing postal reference, or uses the
state from city/state intake. It returns the existing `RevisedOfferPolicy.Discrepancy`
shape, with `comparison=STATE_RULE`, `kind=DOC_FEE_CHECK`, no change delta, and frozen
rule evidence. It is exposed as additive `docFeeFindings` arrays in saved core reports
and revised Deal Reviews. The report UI and saved HTML/PDF present the question.
Desk Check uses its existing `otherDifferences` list so an unchanged fee is not counted
as a change from the Agreed Price. The at-dealer first result uses its existing findings.
Unverified paperwork remains explicitly unverified. Market comparisons are out of scope.
