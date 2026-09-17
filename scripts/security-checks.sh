#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v trivy >/dev/null
command -v semgrep >/dev/null
mkdir -p target/hardening
# Reports remain local/CI artifacts; never print discovered secret values into job logs.
trivy fs --scanners vuln,secret,misconfig --severity HIGH,CRITICAL --exit-code 1 \
  --skip-dirs .git --skip-dirs frontend/node_modules --skip-dirs target \
  --format json --output target/hardening/repository-security.json .
semgrep scan --config scripts/security-rules.yml --metrics=off --error \
  --json --output target/hardening/source-security.json backend/src/main frontend/src
(cd frontend && npm audit --audit-level=high)
# Require the actual release image: a filesystem scan cannot replace an image scan.
: "${RELEASE_IMAGE:?Set RELEASE_IMAGE to the built release image reference}"
trivy image --scanners vuln,secret --severity HIGH,CRITICAL --exit-code 1 \
  --format json --output target/hardening/container-security.json "$RELEASE_IMAGE"
: "${PARSER_IMAGE:?Set PARSER_IMAGE to the confined parser release image reference}"
trivy image --scanners vuln,secret --severity HIGH,CRITICAL --exit-code 1 \
  --format json --output target/hardening/parser-security.json "$PARSER_IMAGE"
