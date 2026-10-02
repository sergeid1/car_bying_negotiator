# Extraction evaluation contract

The shared interface between the Claude document extraction provider (ticket 40, Java, inside the
service) and the offline evaluation harness (ticket 39, Python, outside the service). Both sides
build against this page; changing it needs a new version on both sides.

## Code-owned prompt and schema

The model task's instructions and output schema live as versioned resource files, owned by the
backend and read-only to the harness:

| File | Version id |
| --- | --- |
| `backend/src/main/resources/ai/extraction/deal-terms-prompt.v1.md` | `deal-terms-prompt.v1` |
| `backend/src/main/resources/ai/extraction/deal-terms.v1.schema.json` | `deal-terms.v1` |

The Java `ModelTask` loads both at build time. The harness reads the same two files by path, so a
batch run sends exactly the prompt and schema the service would send. Neither side copies or edits
the other's text. A changed prompt or schema is a new file with a new version id, never an edit in
place.

## Model output (`deal-terms.v1`)

The schema is a closed JSON object (`additionalProperties: false` at every level):

```json
{
  "expectedPageCount": 2,
  "candidates": [
    {
      "field": "SELLING_PRICE",
      "originalWording": "Selling Price: $25,000.00",
      "normalizedValue": "2500000",
      "normalizedUnit": "USD_MINOR",
      "evidence": { "pageNumber": 1, "x": 0.08, "y": 0.31, "width": 0.52, "height": 0.04 },
      "confidence": 0.93,
      "material": true
    }
  ],
  "lineItems": [
    {
      "originalLabel": "Doc Fee",
      "category": "DEALER_FEE",
      "amountMinor": 89900,
      "currency": "USD",
      "taxClaim": "UNKNOWN",
      "optionalityClaim": "REQUIRED",
      "evidence": { "pageNumber": 1, "x": 0.08, "y": 0.36, "width": 0.52, "height": 0.04 },
      "confidence": 0.9
    }
  ],
  "documentWarnings": []
}
```

- Field names, line categories, units, tax claims and optionality claims are the vocabularies
  `DealTermsValidator` already accepts. The schema enumerates them.
- `normalizedValue` is a string, or `null` when the value is missing or illegible. Money is in
  integer minor units (`USD_MINOR`), APR is a decimal (`PERCENT`), dates are ISO `YYYY-MM-DD`
  (`DATE`), the term is an integer (`MONTH`), and a VIN is the 17 characters (`VIN`).
- Evidence coordinates are fractions of the page, from 0 to 1, origin top-left. Pages are numbered
  from 1.
- The model never emits `extractorVersion`, `modelVersion` or `schemaVersion`. The Java provider
  sets them on the `ProviderExtraction`.

## Predictions file (harness input)

A run that does not call a model, such as a deterministic baseline or a stored run, supplies JSON
Lines. There is one object per document:

```json
{"documentId": "doc-0007", "source": "deterministic-dealer-layout.v1", "extraction": { "...": "a deal-terms.v1 object, plus the three version fields" }}
```

## Labels and corpus manifest (harness only)

- Labels for each document: `expected` maps a field name to its true value, using
  `backend/src/test/resources/fixtures/extraction/supported-layout-corpus.json` as the model. Money
  is an integer in minor units, APR a number, everything else a string. A field the document does
  not contain is absent from the label.
- The corpus manifest records the corpus version, whether the corpus is `synthetic` or
  `permissioned`, and for each document: its permission basis and the reference to that permission
  record (required when permissioned), its condition tags (`PHONE_PHOTO`, `MULTI_PAGE`,
  `FEE_TABLE`, `MISSING_FIELDS`, `REVISED_OFFER`), its page files and its labels.
- The split file assigns every document to `DEVELOPMENT` or `HELD_OUT`. It records a content hash
  and a freeze timestamp, and it is written before any evaluation run. A run refuses to start
  against a split whose hash no longer matches.

## Shadow comparison record

The harness writes `shadow-comparisons.v2` entries in the format given in
[the AI execution harness](ai-execution-harness.md#shadow-comparison-record-shadow-comparisonsv2).
It refuses to write one for a synthetic corpus or for any split other than `HELD_OUT`. The
synthetic development corpus therefore can never satisfy the rollout gate.
