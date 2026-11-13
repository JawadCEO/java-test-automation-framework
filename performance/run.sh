#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p target
maven="${MAVEN:-mvn}"
log="$(mktemp target/booking-fixture.XXXXXX.log)"
"$maven" -B -DskipTests test-compile exec:java -Dexec.classpathScope=test \
  -Dexec.mainClass=com.jawad.qa.FixtureMain >"$log" 2>&1 &
fixture_pid=$!
trap 'kill "$fixture_pid" 2>/dev/null || true; wait "$fixture_pid" 2>/dev/null || true' EXIT

booking_url=
for ((attempt=0; attempt<180; attempt++)); do
  booking_url="$(sed -n 's/^BOOKING_URL=//p' "$log" | head -1)"
  if [[ -n "$booking_url" ]]; then
    break
  fi
  if ! kill -0 "$fixture_pid" 2>/dev/null; then
    cat "$log" >&2
    exit 1
  fi
  sleep 1
done
if [[ -z "$booking_url" ]]; then
  cat "$log" >&2
  exit 1
fi
BOOKING_URL="$booking_url" k6 run --summary-export=target/k6-summary.json performance/booking.js
