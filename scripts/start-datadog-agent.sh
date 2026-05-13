#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${DD_API_KEY:-}" ]]; then
  printf "DD_API_KEY is required.\n"
  printf "Example: export DD_API_KEY='your-datadog-api-key'\n"
  exit 1
fi

DD_SITE="${DD_SITE:-datadoghq.com}"
AGENT_CONTAINER_NAME="java-csv-datadog-agent"

if docker ps -a --format '{{.Names}}' | grep -q "^${AGENT_CONTAINER_NAME}$"; then
  docker rm -f "${AGENT_CONTAINER_NAME}" >/dev/null
fi

docker run -d \
  --name "${AGENT_CONTAINER_NAME}" \
  -e DD_API_KEY="${DD_API_KEY}" \
  -e DD_SITE="${DD_SITE}" \
  -e DD_APM_ENABLED=true \
  -e DD_DOGSTATSD_NON_LOCAL_TRAFFIC=true \
  -e DD_HOSTNAME="java-csv-datadog-demo" \
  -p 8126:8126 \
  -p 8125:8125/udp \
  -p 5002:5002 \
  datadog/agent:7 >/dev/null

printf "Datadog Agent started in Docker as '%s'.\n" "${AGENT_CONTAINER_NAME}"
printf "APM traces endpoint: http://127.0.0.1:8126\n"
