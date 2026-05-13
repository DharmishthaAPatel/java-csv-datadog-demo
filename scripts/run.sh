#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ENV_FILE="${PROJECT_DIR}/.env"

if [[ -f "${ENV_FILE}" ]]; then
  set -a
  source "${ENV_FILE}"
  set +a
fi

if [[ -z "${DD_API_KEY:-}" ]]; then
  printf "DD_API_KEY is not set. Add it to .env or export it before running.\n"
  exit 1
fi

printf "Starting Datadog Agent...\n"
bash "${SCRIPT_DIR}/stop-datadog-agent.sh" 2>/dev/null || true
bash "${SCRIPT_DIR}/start-datadog-agent.sh"

printf "Waiting for Datadog Agent to be ready...\n"
for i in $(seq 1 15); do
  if curl -sf http://localhost:8126/info >/dev/null 2>&1; then
    printf "Datadog Agent is ready.\n"
    break
  fi
  if [[ $i -eq 15 ]]; then
    printf "Datadog Agent did not become ready in time. Proceeding anyway...\n"
  fi
  sleep 1
done

printf "Running app...\n"
bash "${SCRIPT_DIR}/run-with-datadog.sh" "$@"
