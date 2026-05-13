#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
TRACER_JAR="${PROJECT_DIR}/build/libs/dd-java-agent.jar"

INPUT_FILE="${1:-data/sample-input.txt}"
OUTPUT_FILE="${2:-data/parsed-output.csv}"

DD_SERVICE="${DD_SERVICE:-java-csv-datadog-demo}"
DD_ENV="${DD_ENV:-dev}"
DD_VERSION="${DD_VERSION:-1.0.0}"
DD_AGENT_HOST="${DD_AGENT_HOST:-127.0.0.1}"

pushd "${PROJECT_DIR}" >/dev/null

printf "Building project...\n"
./gradlew -q assemble

printf "Running app. input=%s output=%s\n" "${INPUT_FILE}" "${OUTPUT_FILE}"
java \
  -javaagent:"${TRACER_JAR}" \
  -Ddd.service="${DD_SERVICE}" \
  -Ddd.env="${DD_ENV}" \
  -Ddd.version="${DD_VERSION}" \
  -Ddd.trace.sample.rate=1 \
  -Ddd.logs.injection=true \
  -Ddd.agent.host="${DD_AGENT_HOST}" \
  -jar build/libs/java-csv-datadog-demo-1.0.0.jar \
  "${INPUT_FILE}" \
  "${OUTPUT_FILE}"

popd >/dev/null
