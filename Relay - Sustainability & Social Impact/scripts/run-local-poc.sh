#!/usr/bin/env bash
# One-command local proof of concept (laptop only, no phones needed):
#   1. builds the dashboard (same-origin API: VITE_API_BASE="")
#   2. starts the backend (SQLite, dev mode, dashboard served at /) in the background on 0.0.0.0:8080
#   3. runs the simulator end-to-end test (A->B->C->D mesh, gateway, backend, approve, Odia ACKs)
#   4. leaves the backend running and prints the dashboard URL
# Stop it with scripts/stop-local-poc.sh.
#
# Flags:  --no-e2e    start the backend only (used by emulator-ble-demo.sh)
#         --keep-db   keep tickets from the previous run (default: fresh database)
#         --no-build  skip the dashboard build when dashboard/dist already exists
# Env:    PORT (8080)  RELAY_DEVICE_KEY (dev-relay-key)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="$ROOT/.run"
PORT="${PORT:-8080}"
E2E=1; KEEP_DB=0; BUILD=1
for a in "$@"; do
  case "$a" in
    --no-e2e) E2E=0 ;;
    --keep-db) KEEP_DB=1 ;;
    --no-build) BUILD=0 ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    *) echo "unknown flag: $a" >&2; exit 2 ;;
  esac
done

if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
command -v uv  >/dev/null || { echo "uv is required (https://docs.astral.sh/uv/)" >&2; exit 1; }
command -v npm >/dev/null || { echo "npm is required" >&2; exit 1; }
mkdir -p "$RUN"

# Stop a previous instance we started.
"$ROOT/scripts/stop-local-poc.sh" --quiet || true
if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "port $PORT is already in use by another process; free it or set PORT=<other>" >&2
  lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >&2 || true
  exit 1
fi

echo "==> dashboard build"
cd "$ROOT/dashboard"
if [ "$BUILD" = 1 ] || [ ! -f dist/index.html ]; then
  [ -d node_modules ] || npm ci
  VITE_API_BASE="" npm run build >"$RUN/dashboard-build.log" 2>&1 || { tail -30 "$RUN/dashboard-build.log" >&2; exit 1; }
  echo "    built dashboard/dist"
else
  echo "    skipped (dashboard/dist exists)"
fi

echo "==> backend on 0.0.0.0:$PORT"
[ "$KEEP_DB" = 1 ] || rm -f "$RUN"/relay.db "$RUN"/relay.db-*
cd "$ROOT/backend"
RELAY_ENV=dev RELAY_STORE=sqlite RELAY_DB="$RUN/relay.db" RELAY_STATIC_DIR="$ROOT/dashboard/dist" \
RELAY_DEVICE_KEY="${RELAY_DEVICE_KEY:-dev-relay-key}" \
  nohup uv run uvicorn app.main:app --host 0.0.0.0 --port "$PORT" >"$RUN/backend.log" 2>&1 &
echo $! >"$RUN/backend.pid"
for i in $(seq 1 60); do
  if curl -fsS "http://localhost:$PORT/healthz" >/dev/null 2>&1; then break; fi
  if ! kill -0 "$(cat "$RUN/backend.pid")" 2>/dev/null; then echo "backend exited; see $RUN/backend.log" >&2; tail -20 "$RUN/backend.log" >&2; exit 1; fi
  sleep 0.5
  [ "$i" = 60 ] && { echo "backend did not become healthy in 30 s; see $RUN/backend.log" >&2; exit 1; }
done
echo "    healthz ok: $(curl -fsS "http://localhost:$PORT/healthz")  (pid $(cat "$RUN/backend.pid"), log $RUN/backend.log)"

if [ "$E2E" = 1 ]; then
  echo "==> simulator end-to-end (A -> B -> C -> D -> backend -> ACKs back to A)"
  cd "$ROOT/android"
  ./gradlew --console=plain -q :simulator:run --args="e2e --backend http://localhost:$PORT --approve --lang or"
  echo "==> mesh-assisted location (no-fix NEED, two relays with a fix -> 'mesh' estimate on the ticket)"
  ./gradlew --console=plain -q :simulator:run --args="locate --backend http://localhost:$PORT"
fi

LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)"
echo
echo "Backend running. Dashboard:  http://localhost:$PORT/"
[ -n "$LAN_IP" ] && echo "Phones on the same Wi-Fi:    http://$LAN_IP:$PORT   (emulators use http://10.0.2.2:$PORT)"
echo "Tickets API:                 http://localhost:$PORT/v1/tickets"
echo "Stop with:                   scripts/stop-local-poc.sh"
