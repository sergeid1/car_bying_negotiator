"""Run with python3 -m evaluation. Every command except batch-submit/results is offline."""

import argparse
import json
import os
import sys
from pathlib import Path

from . import core
from .batch import (AnthropicBatchClient, DEFAULT_FRAMING, DEFAULT_PROMPT, DEFAULT_SCHEMA, REPO, build_requests)


def output(path, value):
    target = Path(path).resolve()
    if REPO / "backend" in target.parents or REPO / "frontend" in target.parents:
        raise ValueError("Evaluation never writes backend or frontend files")
    core.write(target, value)


def parser():
    root = argparse.ArgumentParser(description="Offline extraction evaluation; synthetic checks are NOT accuracy")
    commands = root.add_subparsers(dest="command", required=True)
    split = commands.add_parser("split", help="Freeze content and deterministic split before runs")
    split.add_argument("--manifest", required=True)
    split.add_argument("--held-out-fraction", type=float, default=0.2)
    split.add_argument("--seed", default="extraction.v1")
    split.add_argument("--output", required=True)
    freeze = commands.add_parser("freeze-thresholds")
    freeze.add_argument("--thresholds", required=True)
    freeze.add_argument("--output", required=True)
    run = commands.add_parser("run", help="Score a predictions file; never calls a model")
    for name in ("manifest", "split-file", "predictions", "thresholds", "threshold-freeze", "id", "output", "model"):
        run.add_argument("--" + name, required=True)
    run.add_argument("--split", choices=sorted(core.SPLITS), default="DEVELOPMENT")
    run.add_argument("--prompt-version", default="deal-terms-prompt.v1")
    run.add_argument("--schema-version", default="deal-terms.v1")
    run.add_argument("--concurrency", type=int, default=1)
    run.add_argument("--effort", default="high")
    check = commands.add_parser("check", help="Recheck a run against its original frozen thresholds")
    for name in ("report", "thresholds", "threshold-freeze", "output"):
        check.add_argument("--" + name, required=True)
    shadow = commands.add_parser("shadow", help="Export a permissioned held-out comparison for human integration")
    for name in ("manifest", "split-file", "report", "baseline-report", "comparison-report", "thresholds", "threshold-freeze", "output"):
        shadow.add_argument("--" + name, required=True)
    shadow.add_argument("--provider", choices=["ANTHROPIC", "OPENAI"], default="ANTHROPIC")
    build = commands.add_parser("batch-build", help="Build requests offline; requires backend prompt/schema")
    for name in ("manifest", "split-file", "model", "output"):
        build.add_argument("--" + name, required=True)
    build.add_argument("--split", choices=sorted(core.SPLITS), default="DEVELOPMENT")
    build.add_argument("--prompt", default=str(DEFAULT_PROMPT))
    build.add_argument("--schema", default=str(DEFAULT_SCHEMA))
    build.add_argument("--framing", default=str(DEFAULT_FRAMING))
    build.add_argument("--effort", default="high")
    build.add_argument("--max-tokens", type=int, default=16000)
    for command in ("batch-submit", "batch-results"):
        live = commands.add_parser(command, help="EXPLICIT NETWORK COMMAND; requires optional SDK and credential")
        live.add_argument("--credential-env", required=True, help="Name of explicitly selected credential environment variable")
        live.add_argument("--model", required=True)
        live.add_argument("--output", required=True)
        if command == "batch-submit":
            live.add_argument("--requests", required=True)
        else:
            live.add_argument("--batch-id", required=True)
            live.add_argument("--schema-version", default="deal-terms.v1")
            live.add_argument("--prompt-version", default="deal-terms-prompt.v1")
    return root


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        targets = [Path(args.output).resolve()]
        if args.command == "shadow":
            targets.append(Path(args.comparison_report).resolve())
        if len(set(targets)) != len(targets):
            raise ValueError("Shadow record and comparison report need distinct output paths")
        for target in targets:
            if REPO / "backend" in target.parents or REPO / "frontend" in target.parents:
                raise ValueError("Evaluation never writes backend or frontend files")
            if target.exists():
                raise ValueError("Output already exists; artifacts are immutable")
        if args.command == "split":
            result = core.freeze_split(args.manifest, args.held_out_fraction, args.seed)
        elif args.command == "freeze-thresholds":
            result = core.freeze_thresholds(args.thresholds)
        elif args.command == "run":
            result = core.evaluate(args.manifest, args.split_file, args.predictions, args.thresholds,
                                   args.threshold_freeze, args.split, args.id, args.model, args.prompt_version,
                                   args.schema_version, args.concurrency, args.effort)
        elif args.command == "check":
            result = core.recheck(core.read(args.report), args.thresholds, args.threshold_freeze)
        elif args.command == "shadow":
            manifest, split = core.verify_split(args.manifest, args.split_file)
            report, baseline = core.read(args.report), core.read(args.baseline_report)
            for run in (report, baseline):
                if (run["corpusKind"] != manifest["kind"] or run["corpusVersion"] != manifest["version"]
                        or run["splitHash"] != split["splitHash"]):
                    raise ValueError("Shadow run does not match the supplied frozen corpus")
                if timestamp_after(split["frozenAt"], run["executedAt"]):
                    raise ValueError("Split freeze must predate both runs")
                run["thresholdCheck"] = core.recheck(run, args.thresholds, args.threshold_freeze)
            result = core.shadow_record(report, baseline, args.comparison_report, args.provider)
            output(args.comparison_report, core.comparison_report(report, baseline))
        elif args.command == "batch-build":
            result = build_requests(args.manifest, args.split_file, args.split, args.model,
                                    args.effort, args.prompt, args.schema, args.max_tokens, args.framing)
        else:
            credential = os.environ.get(args.credential_env)
            if not credential:
                raise ValueError("Explicit credential environment variable is empty")
            client = AnthropicBatchClient(credential, args.model, getattr(args, "schema_version", "deal-terms.v1"),
                                          getattr(args, "prompt_version", "deal-terms-prompt.v1"))
            if args.command == "batch-submit":
                requests = core.read(args.requests)
                if any(request["params"]["model"] != args.model for request in requests):
                    raise ValueError("Request model differs from configured model")
                result = {"batchId": client.submit(requests), "submittedAt": core.now(), "model": args.model}
            else:
                records = client.results(args.batch_id)
                # Predictions are JSON Lines, unlike the other JSON artifacts.
                target = Path(args.output).resolve()
                if REPO / "backend" in target.parents or REPO / "frontend" in target.parents:
                    raise ValueError("Evaluation never writes backend or frontend files")
                target.parent.mkdir(parents=True, exist_ok=True)
                with target.open("x", encoding="utf-8") as handle:
                    for record in records:
                        handle.write(json.dumps(record, allow_nan=False) + "\n")
                print("Predictions written to " + args.output)
                return 0
        output(args.output, result)
        print(result.get("label", "Artifact written") if isinstance(result, dict) else "Offline batch requests written")
        if isinstance(result, dict) and "thresholdCheck" in result:
            print("Threshold check: " + result["thresholdCheck"]["outcome"])
        failed = isinstance(result, dict) and (result.get("outcome") == "FAIL"
                                             or result.get("thresholdCheck", {}).get("outcome") == "FAIL")
        return 1 if failed else 0
    except Exception as error:
        # Do not print vendor exceptions, document contents, or credentials.
        print("Evaluation refused: " + type(error).__name__, file=sys.stderr)
        return 2


def timestamp_after(first, second):
    return core.timestamp(first) > core.timestamp(second)


if __name__ == "__main__":
    sys.exit(main())
