# Price boundary expert review packet

This offline packet prepares evidence for public-pilot readiness prerequisite 4. A consumer-side
automotive expert is asked to judge whether the shipped opening, target, policy maximum, and
walk-away are sound negotiating positions given the recorded market range, offer terms, and Buyer
limits. Assess selling-price and OTD boundaries separately, the fixed-charge conversion assumptions,
incomplete inputs, and the warnings. Explain problems even when the arithmetic is correct. Suggested
boundaries record the expert’s judgment; they do not change the shipped policy.

## Scenario data

`backend/src/main/resources/expert-review/scenarios.v1.json` holds twelve **synthetic** retrospective
cash-deal snapshots: narrow and wide ranges, a selling-price cap below opening, an OTD cap below
converted opening, unknown charges, a quote far above market, a quote below target, a fee-heavy offer,
a trade tax credit, an unverified rebate, a fixed discount with verified rebate, and unknown tax
treatment. These authored snapshots are not actual historical transactions, a representative sample,
or representative of any jurisdiction. Their tax policy is an explicitly synthetic fixture.
They can test review mechanics; they cannot establish commercial or jurisdictional validity.

The JSON schema is represented by `ExpertReviewPacket.ScenarioSet` and `Scenario`, using the existing
economics `Policy` and `Offer` data types. A new versioned file can hold permissioned real cash deals
without code changes: set `provenance` to `PERMISSIONED_REAL` and provide a nonblank
`permissionReference` identifying the operator’s permission record. Record source/evidence references
in the offer line items and describe the retrospective snapshot. Keep identifying documents out of
the packet. Each set has one explicit economics policy; separate sets when the recorded policies
differ. Preserve every issued scenario file byte for byte. All amounts in offer money objects are
major units; market ranges, Buyer limits, outputs, and suggested boundaries are integer minor units.
The shipped set is USD (100 minor units per dollar).

## Offline commands

Run from the repository root with Java 21 and the project’s Maven dependencies already cached.
The following preparation is offline and fails if a required dependency/plugin is not cached:

```sh
mvn -o -f backend/pom.xml -DskipTests compile dependency:build-classpath -Dmdep.outputFile=target/expert-review-classpath.txt
```

Generate into a caller-chosen **new** directory whose parent already exists:

```sh
java -cp "backend/target/classes:$(cat backend/target/expert-review-classpath.txt)" com.dealanalyzer.expert.ExpertReviewPacket generate backend/src/main/resources/expert-review/scenarios.v1.json .scratch/expert-packet-v1
```

The plain Java main directly invokes `CashDealEconomicsCalculator` and `PriceBoundaryPolicy`.
It starts no Spring context, binds no port, uses no database, and calls no network. Do not invoke
`spring-boot:run`. Output directories and files are created exclusively; an existing directory is
refused. Paths under `backend/src` or `frontend` are refused, including resolved symlink parents.
There is no overwrite option. Choose a different new directory for each run.

The output contains `scenarios.json` (the exact source snapshot), `workbook.csv`, and `packet.md`.
Each workbook row records the complete offer, economics-policy and Buyer-limit inputs, provenance,
market range, both sets of boundaries, warnings, the shipped method text, and an explanation if OTD
conversion is unavailable. The Markdown rendering provides the same fixed fields in readable form.
`scenario_set_hash` is SHA-256 of the exact scenario-file bytes. `packet_hash` additionally binds the
computed fixed output and policy version; rerunning unchanged inputs and engines produces the same
hash. Formatting changes to the source intentionally change the hashes.

## Completing and importing a review

Give the expert all three generated files. In a copy of `workbook.csv`, retain every fixed column
unchanged and fill the review columns on **every** scenario:

- `verdict`: exactly `SOUND`, `ADJUST`, or `UNSOUND`.
- `suggested_opening_minor`, `suggested_target_minor`, `suggested_walk_away_minor`: optional positive
  integers in the selling-price currency’s minor units; blank means no suggestion. Discuss proposed
  OTD changes in the rationale. Decimal amounts, signs, zero, and values outside a signed 64-bit
  integer are refused. Suggestions are recorded without imposing an ordering on expert judgments.
- `rationale`: required explanation, including assumptions and reservations.
- `reviewer_name`, `qualification_statement`, `review_date`: required on every row and identical
  across the workbook. Describe consumer-side automotive qualifications; date format is `YYYY-MM-DD`.

Use a CSV editor that preserves long integers and quoted JSON as text. The importer accepts ordinary
quoted CSV, including embedded commas, doubled quotes, and multiline rationales. It refuses malformed
CSV, a changed header, unknown/duplicate/missing scenarios, changed packet hashes or any other fixed
field, missing required review fields, invalid verdicts/dates/suggestions, and inconsistent reviewer
metadata. Validation reports row-specific reasons and creates no output directory on refusal.

```sh
java -cp "backend/target/classes:$(cat backend/target/expert-review-classpath.txt)" com.dealanalyzer.expert.ExpertReviewPacket import .scratch/expert-packet-v1/scenarios.json .scratch/completed-expert-workbook.csv .scratch/expert-review-v1
```

Import recomputes the packet from its preserved scenario snapshot with the installed engines and
compares every fixed field. Archive the matching code revision as well as the inputs; if engines
change, use the original revision to import that review. Successful import writes
`review-record.json` with scenario and packet hashes, policy/set versions, reviewer answers, and counts
by verdict, plus an exact copy of `completed-workbook.csv`. The record states `UNCHANGED` for readiness.
This validates completeness and linkage, not the identity or credentials of the reviewer.

## Evidence and release decision

The operator retains the issued packet, completed workbook, import record, code revision, permission
records where relevant, and evidence of reviewer qualifications. The owner of ticket 22 then assesses
the review, representative coverage, and unresolved `ADJUST`/`UNSOUND` judgments. Any policy change
requires its own versioned implementation and validation. Import changes no policy, parameter,
database record, or readiness status. Prerequisite 4 stays **BLOCKED** until its accountable owner
records an explicit evidence-backed approval in the readiness record and machine-readable source.
