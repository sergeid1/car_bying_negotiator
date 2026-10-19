# Dealer documentary fee rules

Ticket 140 uses `backend/src/main/resources/fees/doc-fee-rules.v1.json`, version
`doc-fee-rules.2026-10.v3` (v3, ticket 142, moved recognition onto the shared fee label matcher). All entries were checked on **2026-10-18** and are due for
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
  registration fee exception in 20-101.1(d) is why electronic filing aliases receive
  a clarification question rather than an assertion that documentary fee rules apply.

Florida and North Carolina use `DISCLOSURE`, with no threshold. “No cap” does not
mean “no rule.” `effectiveFrom` for these two entries records the date of this
verified coverage snapshot, not a claim about the original statute enactment date.
Texas's `effectiveFrom` is the confirmed threshold change date. The check suppresses
entries before their coverage date or after their review date. Saved reports retain
the rule snapshot, citation, checked date and review date.

## Recognition and worked examples

The code-owned `recognition` object keeps keyword terms, and `unless` terms that cancel a
tier, in two tiers. Since ticket 142 the terms are read with `economics.fees.FeeLabels`, the
matcher the government fee check and the dealer add-on check use: it folds case, punctuation
and dotted abbreviations, reads a word that starts with a term as that term, and tolerates a
misspelling with the same first letter (`Docuemntary fee`, `Adminstrative fee`). `DOC_FEE`
takes precedence when both tiers match a dealer fee line, and a line the Buyer confirmed as
`doc-fee` is a doc fee whatever its label:

- `DOC_FEE`: doc, docs, document, documentary, documentation, admin, administrative,
  administration and processing. This recognises variants such as `Doc. Fee`,
  `DOCUMENTARY SVC FEE` and `Admin-Fee` without requiring an exact label.
- `POSSIBLE_DOC_FEE`: handling, clerical, procurement, origination, paperwork,
  conveyance, e-filing/electronic filing, dealer service and service-and-handling
  wording, unless the label also says contract, agreement, warranty or plan (a dealer
  service contract is a finance-office product). For example, `Dealer Paperwork Charge` may be a documentary fee under
  another name. Each such line gets a question about what it covers and whether it
  is the documentary fee. These are identification heuristics, not legal findings
  that loan origination, lease procurement or electronic filing charges are doc fees.

Every recognised dealer fee line is retained and described, including zero charges
and unreadable amounts. Only readable nonnegative USD amounts support totals; a
missing, unsupported or overflowing amount makes its tier total unavailable without
discarding that line. Generic protection packages, `window tint`, `nitrogen tires`
and `GAP` do not match. Unsupported states and expired rules retain the existing
no-state-finding behavior.

For a Texas dealer's paperwork, `Doc fee $150` plus `Document processing fee $150` becomes one
question about a **$300** combined documentary charge. Both source lines and evidence
regions survive in `observedEvidence.lines`, with each line's `recognitionTier`.
A single $225 fee or a $224.99 fee is reported informationally without a filing
question. At $225.01 the Buyer sees the safe harbor, OCCC filing requirement and
a question asking whether the dealer filed the notification and cost analysis.
The check does not look up the filing or decide that the dealer breached a rule.

A $199 doc fee plus a $300 origination fee shows an identified documentary fee total
of **$199**, a possible-alias subtotal of **$300**, and a combined total including
possible aliases of **$499**. The combined total is not presented as an established
doc fee. The Buyer is asked what the origination fee covers and whether it is a doc
fee under another name. Texas's filing question is triggered only by the `DOC_FEE`
total above $225. Possible aliases alone never trigger it, even if their combined
amount exceeds $225. If the clear doc fees themselves exceed $225, the filing question
appears alongside any questions about possible aliases.

A Florida `Dealer service fee $999` or North Carolina `Administrative fee $899`
produces information about disclosure and negotiability. The check does not inspect
the advertisement, dealership sign or contractual disclosure. Unsupported dealer
states, ambiguous dealer ZIP codes and expired rules produce no state-rule finding.
Buyer tax jurisdiction never substitutes for dealer state.

## Integration

`DocFeeCheck` resolves a dealer ZIP using the existing postal reference, or uses the
state from city/state intake. It returns the existing `RevisedOfferPolicy.Discrepancy`
shape, with `comparison=STATE_RULE`, `kind=DOC_FEE_CHECK`, no change delta, and frozen
rule evidence. `observed.amountMinor` is the identified `DOC_FEE` total;
`possibleDocFeeAmountMinor` and `combinedAmountMinor` are separate totals, each nullable
when it cannot be calculated. It is exposed as additive `docFeeFindings` arrays in saved core reports
and revised Deal Reviews. The report UI and saved HTML/PDF present the question.
Desk Check uses its existing `otherDifferences` list so an unchanged fee is not counted
as a change from the Agreed Price. The at-dealer first result uses its existing findings.
Unverified paperwork remains explicitly unverified. Market comparisons are out of scope.
