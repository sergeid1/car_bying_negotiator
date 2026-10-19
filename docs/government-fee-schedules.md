# Government fee schedules

The title, registration, plate and other state fees each pilot state charges, and how dealer paperwork is checked
against them (ticket 139). The data is `backend/src/main/resources/fees/government-fees.v1.json`, version
`government-fees.2026-10.v1`; the check is `com.dealanalyzer.economics.fees.GovernmentFeeCheck`
(`government-fee-check.v1`). Taxes stay with the jurisdiction tax packs ([jurisdiction-tax-packs.md](jurisdiction-tax-packs.md)).

Every amount below was read in the official text on **2026-10-18**, not from search excerpts. Each state is reviewed
again by **2027-01-01**; after that date, or before a state's `effectiveOn`, the schedule is not used and the
paperwork's fees are reported as not checked. A changed amount is a new version of the file.

## How the check works

- **Which lines.** Every `GOVERNMENT_CHARGE` line, plus `UNKNOWN` and `DEALER_FEE` lines whose label names a state
  fee (a lump "TT&L" the reader could not classify, or a "Title fee" listed with the dealer's charges). A line whose
  amount cannot be read is kept and asked about, never treated as zero.
- **Reading the label.** Labels are matched with normalised keywords (`FeeLabels`): case, accents and apostrophes
  are ignored, "&" reads as "and", short dotted or slashed abbreviations close up ("T.T.&L." is "ttl", "D.M.V." is
  "dmv", "Lic." is "lic"), a word matches by prefix or as an abbreviation ("regis", "insp", "batt"), and a
  misspelling one edit away (two for words of eight letters or more) still matches ("registeration", "liscense",
  "titel"). The term lists are in the data file: title, registration, tag, plate, temporary tag, tire and battery,
  property tax, and generic state-fee words (DMV, MVD, state fees, government, tax collector) that name all of them.
- **Each fee is a range** from the least it can be to the most, with the fact that decides it (weight, new or used,
  county, electric, new plate or transfer, financed). The product knows only whether the purchase is financed; every
  other fact is treated as unknown, so the check uses the top of each range and says which facts decide it. It never
  guesses a county or a weight.
- **Outcomes, per line:**

  | Code | Severity | When |
  | --- | --- | --- |
  | `ABOVE_SCHEDULE` | MATERIAL | The line is above the most the state's listed fees for it add up to. Asks "Please itemize the state fees on this line." |
  | `LINES_ABOVE_SCHEDULE` | MATERIAL | No single line is above, but the state fee lines together are above the most all the state's fees add up to. |
  | `DEALER_CHARGE_LABEL` | MATERIAL | A line listed as a state fee carries a dealer-fee name ("Electronic filing fee", "Doc fee", NC "Online registration fee"). |
  | `MIXED_LABEL` | MATERIAL | A label names both a dealer charge and a state fee ("Dealer title & tag fee"); it is asked about and not summed. |
  | `WITHIN_RANGE` | INFORMATIONAL | Within the top amount but the facts are unknown; names them. A line naming no fee also gets the itemize question. |
  | `NOT_IN_SCHEDULE` | INFORMATIONAL | Named as a state fee the schedule does not list (for example a tire fee in Texas). |
  | `NO_TOP_AMOUNT` | INFORMATIONAL | North Carolina's county vehicle property tax, which goes by value and local rates. |
  | `NO_AMOUNT` | INFORMATIONAL | The line's amount could not be read. |
  | `LISTED_AS_DEALER_CHARGE` | INFORMATIONAL | A state fee name is listed with the dealer's charges; it is checked against the state's fees too. |
  | `TAX_COVERED` | INFORMATIONAL | A tax (sales tax, North Carolina highway use tax) listed as a fee; the tax pack covers it, so it is not counted again. |

- **Copy.** It states the state's number and the paperwork's, and always adds that a dealer can pass through a fee
  the schedule does not list. It never says a dealer broke a rule. `GovernmentFeeCheck` and the data file are in
  `PilotCopyClaimsTest`'s scanned set.
- **Where it appears.** `analysis.governmentFeeCheck` in the report (the Deal Review), `governmentFees` in the Desk
  Check view and in the "I'm at the dealer" result, whose material findings are also added to its findings list as
  `GOVERNMENT_FEE_<code>`. The client renders all three with `GovernmentFeeNote`, which lists what each of the
  state's fees is for in plain words. A report or view with no state fee line has no check, so older shapes are kept.
- **No schedule.** A case whose state has no schedule (or no state) gets status `NOT_CHECKED`, no findings, and
  "otdog has no fee schedule for … so the $X in state fees on this paperwork was not checked." No amount is treated
  as verified.

## Florida

Scope: a Florida resident buying a private-use automobile, or a truck of 5,000 pounds or less, from a Florida dealer.

| Fee | Amount | Decided by | Source |
| --- | --- | --- | --- |
| Certificate of title | $75.25 transfer, $77.25 new, $85.25 used from another state | the car's title history | FLHSMV fee schedule; F.S. 319.32(1), (2)(a), (3) |
| Lien recording | $2 | financed | F.S. 319.32(1) |
| Paper title mailing | $2.50 | paper title printed | F.S. 319.32(1) |
| Fast title | $10 | requested | FLHSMV fee schedule; F.S. 319.32(7) |
| Branch office charge | up to $0.50 | where processed | F.S. 320.04(1)(c) |
| Registration | $26.10 to $44.10 for 12 months; up to $88.20 for 24 | weight; 12 or 24 months | F.S. 320.08(2)-(3) license tax $14.50/$22.50/$32.50, plus 320.0801(1) $0.10, 320.0802 $1, 320.0804 $2, 320.08046 $1, 320.03(5) $0.50, 320.03(6) $1, 320.03(9) $1.50, 320.06(3)(b) $0.50, 320.04(1)(a) $2.50, (1)(b) $1, (1)(c) $0.50; 320.07(2)(b) |
| Initial registration fee | $225 | new plate, not a transfer | F.S. 320.072 |
| License plate | $28 | new plate | F.S. 320.0607(5) |
| Plate transfer | $4.50 plus up to $2 | plate moved | F.S. 320.0609 |
| Temporary tag | $2 | issued | F.S. 320.131(2) |
| New tire fee | $1 a tire, up to five | new tires | F.S. 403.718(1) |
| Lead-acid battery fee | $1.50 | new battery | F.S. 403.7185(1) |

Statutes are the 2026 Florida Statutes at <https://www.flsenate.gov/Laws/Statutes/2026/>; the agency schedule is
<https://www.flhsmv.gov/motor-vehicles-tags-titles/fees/>. Autos and light trucks pay the full license tax whatever
the month (F.S. 320.14(1)-(2)). A tax collector or private tag agency may add its own itemized service charges
(F.S. 319.32(2)(c), 320.04(3)); they are not in the schedule.

**Worked example.** A Florida cash purchase shows "Tag, title and registration $895". The label names the title,
registration and plate groups. Their tops add up to $98.25 (title, no lien because the purchase is cash) +
$313.20 (24-month registration plus the $225 initial registration fee) + $34.50 (plate and transfer) = **$445.95**.
$895 is above it, so the Buyer sees: "“Tag, title and registration” on this paperwork is $895. Florida's listed
title, registration and plate fees add up to at most $445.95. Which apply depends on …. A dealer can also pass
through a fee this schedule does not list." and the question "Please itemize the state fees on this line."

## Texas

Scope: a Texas resident buying a passenger car or light truck of 10,000 pounds or less from a Texas dealer.

| Fee | Amount | Decided by | Source |
| --- | --- | --- | --- |
| Title application | $28, or $33 in nonattainment or affected counties | county | Transportation Code 501.138(a) |
| Registration | $50.75 (6,000 lb or less) or $54 (6,001-10,000 lb) a year; 24 months for a new car | weight; new | 502.252, 502.253, 502.044(a-1); TxDMV fee chart effective 2026-01-01; Registration Manual (Aug 2026) 17.3, 22.9 |
| Insurance verification | $1 + $1 on a dealer sale | — | 502.357(a); Manual 8.4 |
| Processing and handling | $4.75 | — | 502.1911; Manual 1.1 |
| County fees | $0 to $21.50 a year in 2026 | county | TxDMV fee chart; 502.401-.403 (statute allows up to $31.50) |
| Inspection program replacement | $7.50, or $16.75 once for a new car | new | 548.510(a)-(b) |
| Emissions inspection | $2.75 | county; used | TxDMV Register Your Vehicle; Manual 22.11 |
| Electric vehicle fee | $200, or $400 for a new car's two years | electric; new | 502.360(b) |
| License plate | $10 on every retail sale | — | Manual 17.2; 503.063(g) |
| Plate database charge | up to $20, charged by the dealer | dealer | 503.0631(f) |

Statutes were read at <https://tcss.legis.texas.gov/resources/TN/htm/TN.501.htm> (and .502, .503, .548), the text
served behind <https://statutes.capitol.texas.gov>. TxDMV sources: the 2026 fee chart
<https://www.txdmv.gov/sites/default/files/body-files/FeeChart_1C.pdf>, the Register Your Vehicle page
<https://www.txdmv.gov/motorists/register-your-vehicle> and the Motor Vehicle Registration Manual, August 2026
<https://www.txdmv.gov/sites/default/files/body-files/Motor_Vehicle_Registration_Manual_Book_298.pdf>. The $2.50
registration transfer fee (502.192) does not apply to dealer sales (Manual 1.1), so it is left out.

**Open point.** A new car sold by a Texas dealer gets 24 months of registration (502.044(a-1); Manual 22.9), but
none of the pages read states the 24-month dollar amount. The schedule allows two years of the base and county
fees ($108 and $43), which errs toward no finding. Confirm with TxDMV before narrowing it.

**Worked example.** "Registration fee $650" on Texas paperwork names the registration group: $108 + $2 + $4.75 +
$43 + $16.75 + $2.75 + $400 = **$577.25** at most, so it is asked about. The facts that would narrow it (weight,
new or used, county, electric) are listed in the finding.

## North Carolina

Scope: a North Carolina resident buying a private passenger vehicle, or a pickup registered at up to 9,000 pounds,
from a North Carolina dealer.

| Fee | Amount | Decided by | Source |
| --- | --- | --- | --- |
| Certificate of title | $66.75 | — | G.S. 20-85(a)(1); NCDOT 2024 fee adjustment |
| Lien recording | $25.50 | financed | G.S. 20-85(a)(7); NCDOT 2024 fee adjustment |
| One-day title | $126 | requested | G.S. 20-85(a)(12) |
| Registration | $46.25 passenger; pickups by declared weight, up to $122 at 9,000 lb | vehicle type and weight | G.S. 20-87(5); 20-88(b), (i) |
| Electric vehicle fee | $214.50 electric, $107.25 plug-in hybrid | fuel | G.S. 20-87(13), (13a) |
| Local vehicle taxes | city up to $30, county up to $7, regional transit up to $10 | where you live | G.S. 20-97(b1); 105-561(a); 105-570(a) |
| Plate transfer | $25.50 | plate moved | G.S. 20-85(a)(4); NCDOT 2024 fee adjustment |
| Temporary tag | $1 | issued | G.S. 20-79.1(a) |
| County vehicle property tax | no top amount: value × local rates, prorated | value and rates | G.S. 105-330.4(a), 105-330.5(a)-(a1); NCDMV Vehicle Property Taxes |

Statutes are at <https://www.ncleg.gov/EnactedLegislation/Statutes/HTML/BySection/Chapter_20/> (and Chapter 105,
Articles 22A, 51, 52); the fees are adjusted every four years under G.S. 20-4.02, last on 2024-07-01
(<https://www.ncdot.gov/news/press-releases/Documents/2024-04-02-dmv-fees-increasing.pdf>). The NCDMV fee page's
tables load by script and could not be read; the 2024 adjustment table is the agency source used. The property tax
can be deferred 60 days with a limited registration
(<https://www.ncdot.gov/dmv/title-registration/Pages/vehicle-property-tax.aspx>). Inspection fees are paid at the
inspection station, not with registration, so they are not in the schedule.

**Conflict recorded.** G.S. 20-85 as published prints $25.00 for a plate transfer and a lien; NCDOT's 2024
adjustment table lists $25.50. The schedule uses $25.50, the higher.

**Highway use tax.** It stays with the `US-NC.cash.2026.v1` tax pack (ticket 137). A line labelled "Highway use
tax", "Hwy use tax" or "HUT" is reported as `TAX_COVERED` and is not counted with the state fees.

**Worked example.** A North Carolina cash purchase shows "Highway use tax $750" and "Tag, title and taxes $900".
The first is left to the tax check. The second names the title, registration, plate and property tax groups. With
no lien on a cash purchase, their listed tops add up to $192.75 (title and one-day title) + $383.50 (pickup
registration, electric vehicle fee and local taxes) + $25.50 (plate transfer) = **$601.75**, not counting the county
vehicle property tax, which has no top amount. $900 is above it, so the Buyer sees that number, that the property
tax is not counted, and "Please itemize the state fees on this line."

## Out of scope

- Non-resident and out-of-state registration, including new-resident fees (ticket 141).
- Lease fees, specialty and personalized plates, heavy trucks, and commercial or for-hire vehicles.
- Dealer doc fees, which ticket 140 checks against each state's doc fee rule.
- The Texas vehicle inventory tax, which is the dealer's own property tax; on a fee line it reads as a dealer charge.
