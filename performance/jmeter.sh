#!/usr/bin/env bash
# Starts the booking service on a fixed port, runs the JMeter plan through Maven, then stops the service.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p target
maven="${MAVEN:-mvn}"
port="${BOOKING_PORT:-8085}"
log="target/booking-server-jmeter.log"
BOOKING_PORT="$port" "$maven" -B -q compile exec:java -Dexec.mainClass=com.jawad.booking.BookingServer >"$log" 2>&1 &
server_pid=$!
trap 'kill "$server_pid" 2>/dev/null || true; wait "$server_pid" 2>/dev/null || true' EXIT

for ((attempt=0; attempt<120; attempt++)); do
  grep -q '^BOOKING_URL=' "$log" && break
  if ! kill -0 "$server_pid" 2>/dev/null; then cat "$log" >&2; exit 1; fi
  sleep 1
done
grep -q '^BOOKING_URL=' "$log" || { cat "$log" >&2; exit 1; }

"$maven" -B -Pjmeter -Djacoco.skip=true -Dbooking.port="$port" \
  -Djmeter.threads="${JMETER_THREADS:-20}" -Djmeter.duration="${JMETER_DURATION:-30}" verify
