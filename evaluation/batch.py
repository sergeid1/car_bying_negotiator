"""Offline request construction plus an explicitly credentialed, lazy SDK adapter."""

import base64
import json
from pathlib import Path
from typing import Protocol

from .core import verify_split

REPO = Path(__file__).resolve().parent.parent
DEFAULT_PROMPT = REPO / "backend/src/main/resources/ai/extraction/deal-terms-prompt.v2.md"
DEFAULT_SCHEMA = REPO / "backend/src/main/resources/ai/extraction/deal-terms.v1.schema.json"
DEFAULT_FRAMING = REPO / "backend/src/main/resources/ai/untrusted-framing.v1.json"


class BatchClient(Protocol):
    def submit(self, requests):
        """Submit requests and return a batch identifier."""
        ...

    def results(self, batch_id):
        """Return completed batch results in predictions-file format."""
        ...


def closed_schema(schema):
    if schema.get("type") == "object" and schema.get("additionalProperties") is not False:
        raise ValueError("Code-owned output schema must be closed at every object level")
    for child in schema.get("properties", {}).values():
        closed_schema(child)
    if isinstance(schema.get("items"), dict):
        closed_schema(schema["items"])
    for key in ("anyOf", "oneOf", "allOf"):
        for child in schema.get(key, []):
            closed_schema(child)
    for child in schema.get("$defs", {}).values():
        closed_schema(child)


def build_requests(manifest_path, split_path, selected_split, model, effort="high",
                   prompt_path=DEFAULT_PROMPT, schema_path=DEFAULT_SCHEMA, max_tokens=16000,
                   framing_path=DEFAULT_FRAMING):
    manifest, split = verify_split(manifest_path, split_path)
    if selected_split not in {"DEVELOPMENT", "HELD_OUT"} or not model or max_tokens < 1:
        raise ValueError("Valid split, model and positive token limit required")
    prompt = Path(prompt_path).read_text(encoding="utf-8")
    schema = json.loads(Path(schema_path).read_text(encoding="utf-8"))
    framing = json.loads(Path(framing_path).read_text(encoding="utf-8"))
    if (framing.get("version") != "untrusted-framing.v1" or not isinstance(framing.get("policy"), str)
            or "{label}" not in framing.get("imageMarkerTemplate", "")
            or "{pageNumber}" not in framing.get("pageImageLabelTemplate", "")):
        raise ValueError("Unsupported code-owned framing contract")
    if schema.get("type") != "object":
        raise ValueError("Output schema must be an object")
    closed_schema(schema)
    # Preserve exactly the service system text. The schema stays in output_config;
    # vendor structured-output schema caching needs no JSON Schema cache keyword.
    system = [{"type": "text", "text": prompt + framing["policy"],
               "cache_control": {"type": "ephemeral"}}]
    requests = []
    for doc in manifest["documents"]:
        if split["assignments"][doc["documentId"]] != selected_split:
            continue
        blocks = []
        for number, filename in enumerate(doc["pages"], 1):
            path = Path(manifest_path).parent / filename
            if path.suffix.lower() != ".png":
                raise ValueError("Service-compatible batch input requires PNG page images; recognized text is never sent")
            label = framing["pageImageLabelTemplate"].format(pageNumber=number)
            marker = framing["imageMarkerTemplate"].format(label=label)
            blocks.extend([{"type": "text", "text": marker},
                           {"type": "image", "source": {"type": "base64", "media_type": "image/png",
                            "data": base64.b64encode(path.read_bytes()).decode("ascii")}}])
        output_config = {"format": {"type": "json_schema", "schema": schema}}
        if effort != "vendor-default":
            output_config["effort"] = effort
        requests.append({"custom_id": doc["documentId"], "params": {
            "model": model, "max_tokens": max_tokens, "system": system,
            "output_config": output_config,
            "messages": [{"role": "user", "content": blocks}]}})
    if not requests:
        raise ValueError("Selected split has no documents")
    return requests


class AnthropicBatchClient:
    def __init__(self, credential, model, schema_version="deal-terms.v1", prompt_version="deal-terms-prompt.v2"):
        if not credential or not credential.strip():
            raise ValueError("An explicitly supplied credential is required")
        # No SDK import, installation or environment credential lookup on the offline path.
        import anthropic
        self.client = anthropic.Anthropic(api_key=credential, base_url="https://api.anthropic.com",
                                          max_retries=0, timeout=60.0)
        self.model = model
        self.schema_version = schema_version
        self.prompt_version = prompt_version

    def submit(self, requests):
        return self.client.messages.batches.create(requests=requests).id

    def results(self, batch_id):
        batch = self.client.messages.batches.retrieve(batch_id)
        if batch.processing_status != "ended":
            raise ValueError("Batch has not ended; collect results later")
        records = []
        for item in self.client.messages.batches.results(batch_id):
            if item.result.type != "succeeded":
                raise ValueError("Batch contains a failed result; no partial predictions written")
            message = item.result.message
            if message.model != self.model or message.stop_reason != "end_turn":
                raise ValueError("Batch returned a different model or incomplete/refused output")
            if any(block.type != "text" for block in message.content):
                raise ValueError("Batch output contains non-text content")
            extraction = json.loads("".join(block.text for block in message.content))
            if set(extraction) != {"expectedPageCount", "candidates", "lineItems", "documentWarnings"}:
                raise ValueError("Batch output does not match extraction contract")
            extraction.update(extractorVersion="claude:" + self.prompt_version, modelVersion=message.model,
                              schemaVersion=self.schema_version)
            records.append({"documentId": item.custom_id, "source": "anthropic-message-batches.v1",
                            "extraction": extraction})
        return records
