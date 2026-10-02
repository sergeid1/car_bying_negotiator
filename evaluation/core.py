"""Content-bound freezes, field scoring, and release-record generation (stdlib only)."""

import hashlib
import json
import math
import re
from collections import Counter
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path

from . import __version__

SPLITS = {"DEVELOPMENT", "HELD_OUT"}
TAGS = {"PHONE_PHOTO", "MULTI_PAGE", "FEE_TABLE", "MISSING_FIELDS", "REVISED_OFFER"}
FAILURES = ("missing", "wrong_value", "invented_field", "wrong_page", "low_confidence")
MONEY_FIELDS = {"SELLING_PRICE", "DISCOUNT_TOTAL", "REBATE_TOTAL", "SUBTOTAL", "TAX_TOTAL",
                "DEALER_CHARGES_TOTAL", "GOVERNMENT_CHARGES_TOTAL", "ADD_ON_TOTAL", "TRADE_ALLOWANCE",
                "TRADE_PAYOFF", "CASH_DOWN", "OUT_THE_DOOR_TOTAL", "AMOUNT_FINANCED", "MONTHLY_PAYMENT",
                "FINANCE_CHARGE", "TOTAL_OF_PAYMENTS"}


def expected_unit(field):
    if field in MONEY_FIELDS:
        return "USD_MINOR"
    return {"APR": "PERCENT", "TERM_MONTHS": "MONTH", "VEHICLE_VIN": "VIN",
            "OFFER_DATE": "DATE", "EXPIRATION_DATE": "DATE", "VEHICLE_YEAR": "YEAR"}.get(field, "TEXT")


def now():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def timestamp(value):
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("Freeze timestamps must include a timezone")
    return parsed


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    # Immutable artifacts: avoid silently replacing a previously reviewed run or freeze.
    with path.open("x", encoding="utf-8") as output:
        json.dump(value, output, indent=2, sort_keys=True, allow_nan=False)
        output.write("\n")


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
                                    allow_nan=False).encode("utf-8")).hexdigest()


def corpus(path):
    manifest = read(path)
    if manifest.get("kind") not in {"synthetic", "permissioned"} or not manifest.get("version"):
        raise ValueError("Corpus needs version and kind: synthetic or permissioned")
    documents = manifest.get("documents", [])
    ids = [doc.get("documentId") for doc in documents]
    if not ids or any(not isinstance(i, str) or not i for i in ids) or len(ids) != len(set(ids)):
        raise ValueError("Corpus needs unique, nonempty document IDs")
    page_hashes = {}
    for doc in documents:
        if manifest["kind"] == "permissioned":
            basis, reference = doc.get("permissionBasis"), doc.get("permissionRecord")
            if (not isinstance(basis, str) or not basis.strip()
                    or not isinstance(reference, str) or not reference.strip()
                    or basis.strip().upper() == "SERVICE_PROCESSING"):
                raise ValueError("Evaluation permission basis and record required; SERVICE_PROCESSING is insufficient")
        if not isinstance(doc.get("expected"), dict) or not doc.get("pages"):
            raise ValueError("Every document needs labels and page files")
        if any(type(value) not in (str, int, float) or
               (type(value) is float and not math.isfinite(value)) for value in doc["expected"].values()):
            raise ValueError("Labels must be finite numbers or strings; absent fields are omitted")
        if not isinstance(doc.get("conditionTags"), list) or not set(doc["conditionTags"]) <= TAGS:
            raise ValueError("Unknown or missing condition tags")
        for field, page in doc.get("expectedPages", {}).items():
            if field not in doc["expected"] or type(page) is not int or not 1 <= page <= len(doc["pages"]):
                raise ValueError("Evidence-page labels must refer to a labeled field and valid page")
        page_hashes[doc["documentId"]] = [
            hashlib.sha256((Path(path).parent / page).read_bytes()).hexdigest()
            for page in doc["pages"]]
    return manifest, digest({"manifest": manifest, "pageHashes": page_hashes})


def freeze_split(manifest_path, held_out_fraction=0.2, seed="extraction.v1"):
    if not 0 <= held_out_fraction < 1:
        raise ValueError("Held-out fraction must be at least zero and below one")
    manifest, content_hash = corpus(manifest_path)
    ids = sorted((doc["documentId"] for doc in manifest["documents"]),
                 key=lambda identifier: digest([seed, identifier]))
    count = int(len(ids) * held_out_fraction)
    if held_out_fraction > 0:
        count = max(1, count)
    if count >= len(ids):
        raise ValueError("Split must retain development documents")
    assignments = {identifier: "HELD_OUT" if i < count else "DEVELOPMENT"
                   for i, identifier in enumerate(ids)}
    result = {"version": "corpus-split.v1", "corpusVersion": manifest["version"],
              "contentHash": content_hash, "assignments": assignments, "seed": seed,
              "heldOutFraction": held_out_fraction, "frozenAt": now()}
    result["splitHash"] = digest(result)
    return result


def verify_split(manifest_path, split_path):
    manifest, content_hash = corpus(manifest_path)
    split = read(split_path)
    saved_hash = split.pop("splitHash", None)
    if saved_hash != digest(split) or content_hash != split.get("contentHash"):
        raise ValueError("Frozen split hash no longer matches corpus or assignments")
    if (split.get("version") != "corpus-split.v1" or split.get("corpusVersion") != manifest["version"]
            or set(split.get("assignments", {})) != {d["documentId"] for d in manifest["documents"]}
            or not set(split["assignments"].values()) <= SPLITS):
        raise ValueError("Split must assign every corpus document exactly once")
    if timestamp(split["frozenAt"]) > timestamp(now()):
        raise ValueError("Split freeze is in the future")
    split["splitHash"] = saved_hash
    return manifest, split


def validate_thresholds(value):
    if not value.get("version") or not value.get("fields"):
        raise ValueError("Threshold file needs a version and field rules")
    confidence = value.get("minimumConfidence")
    if type(confidence) not in (int, float) or not math.isfinite(confidence) or not 0 <= confidence <= 1:
        raise ValueError("minimumConfidence must be between zero and one")
    for category, limit in value.get("maximumFailures", {}).items():
        if category not in FAILURES or type(limit) is not int or limit < 0:
            raise ValueError("Global failure thresholds must use taxonomy names and nonnegative integers")
    for rules in value["fields"].values():
        if not rules or not set(rules) <= {"minimumPrecision", "minimumRecall", "maximumFailures"}:
            raise ValueError("Unknown or empty threshold rule")
        for metric in ("minimumPrecision", "minimumRecall"):
            if metric in rules and (type(rules[metric]) not in (int, float)
                                    or not math.isfinite(rules[metric]) or not 0 <= rules[metric] <= 1):
                raise ValueError("Precision and recall thresholds must be between zero and one")
        for category, limit in rules.get("maximumFailures", {}).items():
            if category not in FAILURES or type(limit) is not int or limit < 0:
                raise ValueError("Failure thresholds must use taxonomy names and nonnegative integers")


def freeze_thresholds(path):
    thresholds = read(path)
    validate_thresholds(thresholds)
    return {"version": "threshold-freeze.v1", "thresholdHash": digest(thresholds),
            "thresholdSetVersion": thresholds["version"], "frozenAt": now()}


def verify_thresholds(path, freeze_path, executed_at):
    thresholds, freeze = read(path), read(freeze_path)
    validate_thresholds(thresholds)
    if (freeze.get("version") != "threshold-freeze.v1"
            or freeze.get("thresholdHash") != digest(thresholds)
            or freeze.get("thresholdSetVersion") != thresholds["version"]):
        raise ValueError("Thresholds are unfrozen or changed after freeze")
    if timestamp(freeze["frozenAt"]) > timestamp(executed_at):
        raise ValueError("Thresholds must be frozen before the run, before outcome review")
    return thresholds, freeze


def predictions(path, document_ids):
    records = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        record = json.loads(line)
        identifier = record["documentId"]
        if identifier in records or identifier not in document_ids or not record.get("source"):
            raise ValueError("Duplicate/unknown prediction document or missing source")
        extraction = record["extraction"]
        for key in ("extractorVersion", "modelVersion", "schemaVersion"):
            if not extraction.get(key):
                raise ValueError("Predictions require all three provider version fields")
        if not isinstance(extraction.get("candidates"), list):
            raise ValueError("Predictions require candidates")
        records[identifier] = record
    return records


def equal_value(expected, observed):
    if observed is None or isinstance(expected, bool) or isinstance(observed, bool):
        return False
    if isinstance(expected, (int, float)):
        try:
            number = Decimal(str(observed))
            return number.is_finite() and number == Decimal(str(expected))
        except InvalidOperation:
            return False
    return isinstance(observed, str) and expected == observed


def score(documents, records, minimum_confidence):
    counts = {}
    for doc in documents:
        expected = doc["expected"]
        candidates = records[doc["documentId"]]["extraction"]["candidates"]
        by_field = {}
        for candidate in candidates:
            if not isinstance(candidate.get("field"), str) or not candidate["field"]:
                raise ValueError("Candidate needs a field")
            confidence = candidate.get("confidence")
            if type(confidence) not in (float, int) or not math.isfinite(confidence) or not 0 <= confidence <= 1:
                raise ValueError("Candidate confidence must be between zero and one")
            by_field.setdefault(candidate["field"], []).append(candidate)
        for field in set(expected) | set(by_field):
            counter = counts.setdefault(field, Counter())
            counter["expected"] += int(field in expected)
            observed = [c for c in by_field.get(field, []) if c.get("normalizedValue") is not None]
            counter["predicted"] += len(observed)
            if field in expected and not observed:
                counter["missing"] += 1
            credited = False
            for candidate in observed:
                unit = expected_unit(field)
                correct = (field in expected and equal_value(expected[field], candidate["normalizedValue"])
                           and (unit is None or candidate.get("normalizedUnit") == unit))
                if field in MONEY_FIELDS or field == "TERM_MONTHS":
                    correct = correct and isinstance(candidate["normalizedValue"], str) and bool(
                        re.fullmatch(r"-?[0-9]+", candidate["normalizedValue"]))
                if field not in expected:
                    counter["invented_field"] += 1
                elif not correct:
                    counter["wrong_value"] += 1
                page = candidate.get("evidence", {}).get("pageNumber")
                valid_page = type(page) is int and 1 <= page <= len(doc["pages"])
                page_correct = valid_page and (field not in doc.get("expectedPages", {})
                                              or page == doc["expectedPages"][field])
                if not page_correct:
                    counter["wrong_page"] += 1
                confident = candidate["confidence"] >= minimum_confidence
                if not confident:
                    counter["low_confidence"] += 1
                if correct and page_correct and confident and not credited:
                    counter["truePositive"] += 1
                    credited = True
    result = {}
    for field, counter in sorted(counts.items()):
        tp = counter["truePositive"]
        result[field] = {
            "precision": {"numerator": tp, "denominator": counter["predicted"],
                          "value": tp / counter["predicted"] if counter["predicted"] else None},
            "recall": {"numerator": tp, "denominator": counter["expected"],
                       "value": tp / counter["expected"] if counter["expected"] else None},
            "failures": {category: counter[category] for category in FAILURES}}
    return {"documentCount": len(documents), "fields": result,
            "failures": {category: sum(c[category] for c in counts.values()) for category in FAILURES},
            "pageLabeledFieldCount": sum(len(d.get("expectedPages", {})) for d in documents)}


def threshold_check(scored, thresholds):
    deviations = []
    for category, limit in thresholds.get("maximumFailures", {}).items():
        actual = scored.get("failures", {}).get(category, 0)
        if actual > limit:
            deviations.append({"field": "ALL_FIELDS", "rule": category, "expected": limit, "actual": actual})
    for field, rules in thresholds["fields"].items():
        metrics = scored["fields"].get(field)
        for rule, metric in (("minimumPrecision", "precision"), ("minimumRecall", "recall")):
            if rule in rules:
                actual = metrics[metric]["value"] if metrics else None
                if actual is None or actual < rules[rule]:
                    deviations.append({"field": field, "rule": rule, "expected": rules[rule], "actual": actual})
        for category, limit in rules.get("maximumFailures", {}).items():
            actual = metrics["failures"][category] if metrics else 0
            if actual > limit:
                deviations.append({"field": field, "rule": category, "expected": limit, "actual": actual})
    return {"outcome": "FAIL" if deviations else "PASS", "deviations": deviations}


def evaluate(manifest_path, split_path, prediction_path, threshold_path, freeze_path,
             selected_split, run_id, model, prompt_version, schema_version, concurrency=1, effort="high"):
    executed_at = now()
    manifest, split = verify_split(manifest_path, split_path)
    thresholds, freeze = verify_thresholds(threshold_path, freeze_path, executed_at)
    if selected_split not in SPLITS or type(concurrency) is not int or concurrency < 1:
        raise ValueError("Valid split and positive concurrency required")
    if not all((run_id, model, prompt_version, schema_version, effort)):
        raise ValueError("Run conditions cannot be empty")
    if timestamp(split["frozenAt"]) > timestamp(executed_at):
        raise ValueError("Split must be frozen before the run")
    docs = [d for d in manifest["documents"] if split["assignments"][d["documentId"]] == selected_split]
    if not docs:
        raise ValueError("Selected split has no documents")
    records = predictions(prediction_path, {d["documentId"] for d in manifest["documents"]})
    if any(d["documentId"] not in records for d in docs):
        raise ValueError("Every selected document requires a prediction (empty candidates score as missing)")
    for doc in docs:
        extraction = records[doc["documentId"]]["extraction"]
        if extraction["modelVersion"] != model or extraction["schemaVersion"] != schema_version:
            raise ValueError("Prediction provenance does not match declared run conditions")
        if extraction.get("expectedPageCount") != len(doc["pages"]):
            raise ValueError("Prediction page count does not match corpus")
    scored = score(docs, records, thresholds["minimumConfidence"])
    return {"version": "extraction-evaluation.v1", "id": run_id, "executedAt": executed_at,
            "harnessVersion": __version__, "corpusKind": manifest["kind"], "corpusVersion": manifest["version"],
            "label": "SYNTHETIC DEVELOPMENT CHECK — NOT ACCURACY" if manifest["kind"] == "synthetic"
                     else "Permissioned corpus evaluation",
            "corpusSplit": selected_split, "splitHash": split["splitHash"], "splitFrozenAt": split["frozenAt"],
            "thresholdSetVersion": thresholds["version"], "thresholdHash": freeze["thresholdHash"],
            "thresholdFrozenAt": freeze["frozenAt"], "minimumConfidence": thresholds["minimumConfidence"],
            "conditions": {"concurrency": concurrency, "effort": effort, "model": model,
                           "promptVersion": prompt_version, "schemaVersion": schema_version},
            "predictionHash": hashlib.sha256(Path(prediction_path).read_bytes()).hexdigest(),
            "sources": sorted({records[d["documentId"]]["source"] for d in docs}),
            "scores": {"bySplit": {selected_split: scored},
                       "byConditionTag": {tag: score([d for d in docs if tag in d["conditionTags"]], records,
                                                     thresholds["minimumConfidence"])
                                          for tag in sorted({t for d in docs for t in d["conditionTags"]})}},
            "thresholdCheck": threshold_check(scored, thresholds)}


def recheck(report, threshold_path, freeze_path):
    thresholds, freeze = verify_thresholds(threshold_path, freeze_path, report["executedAt"])
    if report["thresholdHash"] != freeze["thresholdHash"]:
        raise ValueError("Report was scored with different thresholds")
    return threshold_check(report["scores"]["bySplit"][report["corpusSplit"]], thresholds)


def shadow_record(report, baseline, report_reference, provider="ANTHROPIC"):
    for run in (report, baseline):
        if run["corpusKind"] != "permissioned" or run["corpusSplit"] != "HELD_OUT":
            raise ValueError("Shadow records require permissioned HELD_OUT runs")
    for key in ("corpusVersion", "splitHash", "thresholdSetVersion", "thresholdHash"):
        if report[key] != baseline[key]:
            raise ValueError("Candidate and baseline must use the same frozen corpus and thresholds")
    if len(baseline["sources"]) != 1 or not report_reference or provider not in {"ANTHROPIC", "OPENAI"}:
        raise ValueError("Single baseline source, report reference and supported provider required")
    conditions = report["conditions"]
    return {"schemaVersion": "shadow-comparisons.v2", "comparisons": [{
        "id": report["id"], "operation": "EXTRACTION",
        "candidate": {"provider": provider, "modelId": conditions["model"],
                      "promptVersion": conditions["promptVersion"], "schemaVersion": conditions["schemaVersion"]},
        "baseline": baseline["sources"][0], "evaluationSetVersion": report["corpusVersion"],
        "thresholdSetVersion": report["thresholdSetVersion"], "corpusSplit": "HELD_OUT",
        "executedBy": "python-evaluation-harness", "harnessVersion": report["harnessVersion"],
        "executedAt": report["executedAt"], "outcome": report["thresholdCheck"]["outcome"],
        "report": report_reference}]}


def comparison_report(candidate, baseline):
    """Both complete runs and deltas, with the pre-frozen candidate gate as the outcome."""
    split = candidate["corpusSplit"]
    candidate_fields = candidate["scores"]["bySplit"][split]["fields"]
    baseline_fields = baseline["scores"]["bySplit"][split]["fields"]
    deltas = {}
    for field in sorted(set(candidate_fields) | set(baseline_fields)):
        deltas[field] = {}
        for metric in ("precision", "recall"):
            c = candidate_fields.get(field, {}).get(metric, {}).get("value")
            b = baseline_fields.get(field, {}).get(metric, {}).get("value")
            deltas[field][metric] = c - b if c is not None and b is not None else None
    return {"version": "extraction-comparison.v1", "candidate": candidate, "baseline": baseline,
            "candidateMinusBaseline": deltas, "thresholdCheck": candidate["thresholdCheck"]}
