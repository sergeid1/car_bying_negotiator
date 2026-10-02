# Offline extraction evaluation

Python 3.9+ with no dependencies on the offline path. Run from the repository root:

```sh
python3 -m unittest discover -s evaluation/tests
python3 -m evaluation --help
```

This project is outside Spring Boot, is not packaged with the service, and has no Buyer request
path. Tests block socket connections and use a fake batch client. No package installation or
network call is needed for any command below except the explicitly named `batch-submit` and
`batch-results` commands. Output files are created exclusively; choose a new directory for each
experiment. The CLI refuses any output under backend/ or frontend/ and never installs packages.

## Synthetic development example

The corpus uses excerpts and deliberate variations of the existing
`supported-layout-corpus.json` and `scoring-corpus.json` extraction fixtures. Labels cover selected
fields, not the entire fixture. Its photo condition is simulated text, not a real image. The stored
predictions are hand-written for exercising mechanics. **All synthetic results are development
checks, never accuracy, permissioned evaluation evidence, or pilot readiness.**

Use a fresh output directory (the commands create it):

```sh
python3 -m evaluation split --manifest evaluation/corpus/synthetic.v1/manifest.json --held-out-fraction 0 --output evaluation/reports/demo/split.json
python3 -m evaluation freeze-thresholds --thresholds evaluation/thresholds/synthetic-checks.v1.json --output evaluation/reports/demo/threshold-freeze.json
python3 -m evaluation run --manifest evaluation/corpus/synthetic.v1/manifest.json --split-file evaluation/reports/demo/split.json --predictions evaluation/corpus/synthetic.v1/predictions.jsonl --thresholds evaluation/thresholds/synthetic-checks.v1.json --threshold-freeze evaluation/reports/demo/threshold-freeze.json --id synthetic-demo --model synthetic-no-model --concurrency 1 --effort high --output evaluation/reports/demo/report.json
python3 -m evaluation check --report evaluation/reports/demo/report.json --thresholds evaluation/thresholds/synthetic-checks.v1.json --threshold-freeze evaluation/reports/demo/threshold-freeze.json --output evaluation/reports/demo/check.json
```

Expected: four development documents, every condition tag represented, `PASS` for the synthetic
mechanics thresholds, and a report labeled `SYNTHETIC DEVELOPMENT CHECK — NOT ACCURACY`. An
exit code of 0 means the artifact was produced and its gate passed where applicable; 1 means a
threshold failed (the report and deviations are still saved); 2 means the command was refused.
`shadow` also keeps FAIL history in its exported record; the rollout gate cannot use FAIL.

## Corpus, split and predictions

See [the binding contract](../docs/extraction-evaluation-contract.md). A manifest has `version`,
`kind` (`synthetic` or `permissioned`) and `documents`. Each document has a unique `documentId`,
`conditionTags`, `pages` (paths relative to the manifest) and `expected` field labels. Money labels
are integer minor units; APR is numeric; other values are strings. An absent field is omitted.
Use only the five contract condition tags. For permissioned corpora, every document must also have
a nonempty `permissionBasis` and `permissionRecord`; `SERVICE_PROCESSING` is explicitly rejected.
The harness checks presence of those records, not their legal sufficiency; obtaining and reviewing
evaluation permissions belongs to ticket 28. Do not commit real documents or credentials here.

Optional `expectedPages` maps labeled fields to 1-based evidence page numbers. A wrong evidence
page can be scored against gold only when that mapping is provided; otherwise only out-of-range
pages are detected. Reports include `pageLabeledFieldCount` so coverage is visible. Supply page
labels for meaningful evidence-page evaluation. Region coordinates and line-item accuracy are
outside this field scorer's scope.

`split` defaults to a deterministic 20% held-out assignment, ranked by SHA-256 of seed and document
ID; positive fractions reserve at least one held-out document and retain development documents.
The synthetic example deliberately uses zero held-out documents. The freeze includes UTC time,
corpus version, hashes of the full manifest and every page's bytes, assignments, and a split hash
over that entire artifact. A run refuses missing or changed freezes. Establish splits before
predicting or reviewing outcomes. These local artifacts enforce ordering and detect changes; they
are not signatures or an external audit log. Do not regenerate a split to choose favorable results.

Predictions are JSON Lines following the contract: `documentId`, `source`, `extraction` with
`extractorVersion`, `modelVersion`, `schemaVersion` plus the model output. Each selected document
needs a record, even if `candidates` is empty. Unknown/duplicate records and mismatched model,
schema or page count are refused. Files may also include other-split records. Run options capture
the historical model, prompt/schema versions, effort, and concurrency; for imported predictions
these are operator declarations, not observations of a live execution. Concurrency means caller
execution concurrency; it is not an assertion about vendor batch scheduling. Sources and a
predictions byte hash are retained. Freeze timestamps and harness/corpus versions are also saved.

## Scoring and pre-frozen thresholds

Precision is accepted exact matches / non-null predicted candidates. Recall is accepted exact
matches / gold fields. Values must match exactly (numeric decimal equivalence for APR, integer
strings for money and term; literal matching for text and dates), use the required normalized unit,
identify the right labeled page, and meet `minimumConfidence`. At most one candidate per document
and field can earn credit; duplicates add to the precision denominator. A null candidate counts
as missing and does not count as a prediction. Zero denominators have `value: null`, never a
fabricated perfect score, and an applicable minimum threshold fails on null. Every metric records
its numerator and denominator, by selected split and by condition tag. Tag groups overlap and
must not be added together.

The failure taxonomy is `missing`, `wrong_value` (including wrong units), `invented_field`,
`wrong_page`, and `low_confidence`. Failures can overlap on one candidate. They have field and
corpus totals. The scorer does not repair values or estimate unreadable text.

Threshold JSON is versioned, with `minimumConfidence`, `fields` rules (`minimumPrecision`,
`minimumRecall`, `maximumFailures`) and optional corpus-wide `maximumFailures`. Freeze it with
`freeze-thresholds` **before the run and outcome review**. Runs and checks refuse modified files or
freezes later than the run. Pick new versions before subsequent experiments; never tune a frozen
set after reviewing results. Reports retain every threshold deviation. The supplied thresholds
are for synthetic mechanics only; ticket 28 owns real pilot acceptance thresholds.

## Message Batches boundary

`batch-build` is offline and reads the exact backend-owned prompt, schema and framing resources, with the contract paths as
defaults. Ticket 40 supplies those files; absence is a refusal, never a fallback to test fixtures:

```sh
python3 -m evaluation batch-build --manifest PATH_TO_IMAGE_MANIFEST --split-file PATH_TO_FROZEN_SPLIT --model claude-opus-5 --effort high --output evaluation/reports/image-run/requests.json
```

`--prompt`, `--schema` and `--framing` override paths for isolated fixtures. Framing defaults to
`backend/src/main/resources/ai/untrusted-framing.v1.json`. Requests have no tools and use
`output_config.format` with the unchanged closed JSON schema. System text is exactly the prompt
file followed by the framing file's `policy`, including its leading newline. User content contains
only PNG page images preceded by markers built from `imageMarkerTemplate` and
`pageImageLabelTemplate`, with 1-based page numbers. Recognized text is never sent, so the text-only
synthetic scoring corpus is deliberately refused by `batch-build`. Tests use isolated image bytes
and local resource fixtures. The system text block has ephemeral `cache_control`; structured-output
schema caching is vendor-managed, with no unsupported cache keyword inserted in JSON Schema or
extra schema text added to the service prompt. Effort is explicitly recorded in `output_config`.

`BatchClient` separates request construction from transport. The real adapter imports the optional
`anthropic` SDK only after a credential is explicitly selected. It uses the vendor endpoint, no
SDK retries, and a bounded timeout. It refuses partial/failed batches, refusals, truncated output,
non-text output, and a different served model. No live API was exercised for this ticket. Vendor
compatibility and the SDK must be validated in the authorized live work of ticket 28; this harness
does not install them or enable a model path. To explicitly opt into network work after that setup:

```sh
python3 -m evaluation batch-submit --requests evaluation/reports/image-run/requests.json --model claude-opus-5 --credential-env EVALUATION_ANTHROPIC_KEY --output evaluation/reports/image-run/submission.json
python3 -m evaluation batch-results --batch-id BATCH_ID_FROM_SUBMISSION --model claude-opus-5 --credential-env EVALUATION_ANTHROPIC_KEY --output evaluation/reports/image-run/live-predictions.jsonl
```

Set the selected variable securely outside these commands. Neither command uses ambient credentials
without `--credential-env`. Collection is a single attempt after the batch ends, with no polling
loop; it writes predictions for a subsequent offline `run`, with the service's
`extractorVersion: claude:deal-terms-prompt.v1` (or `claude:` plus `--prompt-version`). Do not enable SDK HTTP debug logging.
CLI errors print only the exception type, never a vendor response, document text, or credential.

## Shadow export

After ticket 28 provides a frozen permissioned corpus and pre-frozen real thresholds, score both
candidate and deterministic baseline predictions on the same held-out assignment. Then:

```sh
python3 -m evaluation shadow --manifest PATH_TO_PERMISSIONED_MANIFEST --split-file PATH_TO_FROZEN_SPLIT --report PATH_TO_CANDIDATE_REPORT --baseline-report PATH_TO_BASELINE_REPORT --thresholds PATH_TO_THRESHOLDS --threshold-freeze PATH_TO_THRESHOLD_FREEZE --comparison-report evaluation/reports/COMPARISON_ID.json --output evaluation/reports/COMPARISON_ID.shadow.json
```

This revalidates the supplied corpus freeze and both reports' threshold freezes, refuses synthetic
or development runs, and requires identical corpus/split/threshold versions and hashes. The
comparison report retains both runs and per-field candidate-minus-baseline deltas. Its outcome
uses the frozen absolute candidate thresholds; deltas are recorded for review, not an unstated
relative gate. The `shadow-comparisons.v2` wrapper contains one record with the exact provenance
tuple expected by the service and points to this comparison report. `--provider` defaults to
`ANTHROPIC` and can select `OPENAI` for imported predictions. A human integrator reviews the
comparison and appends the record to the backend resource; this tool never edits that resource.
