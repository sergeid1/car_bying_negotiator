#!/usr/bin/env bash
# Start the database, backend and frontend for local development.
#
# The two backend secrets are generated once and kept in backend/var/local-secrets.env
# (gitignored). They must stay the same across runs: a new pepper breaks existing case
# links and a new encryption key makes stored documents unreadable. Delete that file
# together with backend/var/document-objects and the database volume to start fresh.
set -euo pipefail
cd "$(dirname "$0")/.."

secrets_file=backend/var/local-secrets.env
if [[ ! -f $secrets_file ]]; then
  mkdir -p "$(dirname "$secrets_file")"
  (
    umask 077
    {
      echo "CASE_ACCESS_PEPPER=$(openssl rand -base64 32)"
      echo "DOCUMENT_STORAGE_ENCRYPTION_KEY=$(openssl rand -base64 32)"
    } > "$secrets_file"
  )
  echo "Generated local secrets in $secrets_file"
fi
set -a
# shellcheck source=/dev/null
source "$secrets_file"
set +a

docker compose up -d --wait database

if [[ ! -d frontend/node_modules ]]; then
  (cd frontend && npm ci)
fi

pids=()
# Maven and npm fork the real servers, so stop each whole process tree.
kill_tree() {
  local child
  for child in $(pgrep -P "$1"); do
    kill_tree "$child"
  done
  kill "$1" 2>/dev/null || true
}
cleanup() {
  trap - INT TERM EXIT
  for pid in ${pids[@]+"${pids[@]}"}; do
    kill_tree "$pid"
  done
  wait 2>/dev/null || true
}
trap cleanup INT TERM EXIT

(cd backend && exec mvn spring-boot:run) &
pids+=($!)
(cd frontend && exec npm run dev) &
pids+=($!)

echo "Backend: http://localhost:8080  Frontend: http://localhost:5173  (Ctrl+C stops both)"
# Stop everything as soon as either server exits.
while true; do
  for pid in ${pids[@]+"${pids[@]}"}; do
    kill -0 "$pid" 2>/dev/null || exit 1
  done
  sleep 2
done
