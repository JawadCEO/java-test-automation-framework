#!/usr/bin/env bash
# Pushes target/qa-kpis/kpis.prom to the local Pushgateway; Prometheus scrapes it and keeps the history.
set -euo pipefail
cd "$(dirname "$0")/.."
file="${1:-target/qa-kpis/kpis.prom}"
gateway="${PUSHGATEWAY_URL:-http://localhost:9091}"
curl -fsS --data-binary @"$file" "$gateway/metrics/job/qa_kpis"
echo "Pushed $file to $gateway"
