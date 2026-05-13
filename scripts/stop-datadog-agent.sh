#!/usr/bin/env bash
set -euo pipefail

AGENT_CONTAINER_NAME="java-csv-datadog-agent"

if docker ps -a --format '{{.Names}}' | grep -q "^${AGENT_CONTAINER_NAME}$"; then
  docker rm -f "${AGENT_CONTAINER_NAME}" >/dev/null
  printf "Datadog Agent container '%s' stopped and removed.\n" "${AGENT_CONTAINER_NAME}"
else
  printf "Datadog Agent container '%s' not found.\n" "${AGENT_CONTAINER_NAME}"
fi
