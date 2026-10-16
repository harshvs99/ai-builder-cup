#!/usr/bin/env bash
# Run the API test suite against the Firestore emulator (the `firestore`-marked variants).
#
# Starts the emulator (Docker image, or `gcloud emulators firestore start` when the
# cloud-firestore-emulator component and a JDK are installed), waits for it, runs pytest with
# FIRESTORE_EMULATOR_HOST set, and always stops the emulator again.
#
#   scripts/test-firestore.sh                 # all firestore-marked tests
#   scripts/test-firestore.sh -k approve -x   # extra args go to pytest
#   RELAY_EMULATOR=gcloud scripts/test-firestore.sh   # force the gcloud emulator
#   RELAY_EMULATOR_PORT=8086 scripts/test-firestore.sh
#
# Nothing here talks to real Google Cloud: the client only runs when FIRESTORE_EMULATOR_HOST
# points at a reachable local emulator, with a throwaway project id.
set -euo pipefail

cd "$(dirname "$0")/.."

PORT="${RELAY_EMULATOR_PORT:-8085}"
IMAGE="${RELAY_EMULATOR_IMAGE:-gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators}"
NAME="relay-firestore-emulator-$$"
MODE="${RELAY_EMULATOR:-auto}"
JDK_FALLBACK="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

PID=""
cleanup() {
  if [ -n "$PID" ]; then
    kill "$PID" 2>/dev/null || true
    pkill -P "$PID" 2>/dev/null || true
    # the gcloud wrapper forks a java child that is not always reaped with the wrapper
    pkill -f "cloud-firestore-emulator.*--port=${PORT}" 2>/dev/null || true
  fi
  docker rm -f "$NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

if [ "$MODE" = auto ]; then
  if command -v gcloud >/dev/null 2>&1 && gcloud components list --filter="id=cloud-firestore-emulator AND state.name=Installed" --format="value(id)" 2>/dev/null | grep -q firestore; then
    MODE=gcloud
  elif command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    MODE=docker
  else
    echo "need either Docker or the gcloud cloud-firestore-emulator component (+ a JDK)" >&2
    exit 2
  fi
fi

if port_in_use=$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null) && [ -n "$port_in_use" ]; then
  echo "port $PORT is already in use; set RELAY_EMULATOR_PORT" >&2
  exit 2
fi

echo ">> starting Firestore emulator ($MODE) on localhost:$PORT"
case "$MODE" in
  docker)
    docker run -d --rm --name "$NAME" -p "127.0.0.1:${PORT}:8085" "$IMAGE" \
      gcloud emulators firestore start --host-port=0.0.0.0:8085 >/dev/null
    ;;
  gcloud)
    if ! command -v java >/dev/null 2>&1 || ! java -version >/dev/null 2>&1; then
      [ -d "$JDK_FALLBACK" ] && export JAVA_HOME="$JDK_FALLBACK" PATH="$JDK_FALLBACK/bin:$PATH"
    fi
    gcloud emulators firestore start --host-port="127.0.0.1:${PORT}" >/tmp/relay-firestore-emulator.log 2>&1 &
    PID=$!
    ;;
  *) echo "RELAY_EMULATOR must be auto, docker or gcloud" >&2; exit 2 ;;
esac

for _ in $(seq 1 90); do
  if curl -fs "http://127.0.0.1:${PORT}/" 2>/dev/null | grep -q "Ok"; then READY=1; break; fi
  sleep 1
done
if [ -z "${READY:-}" ]; then
  echo "emulator did not become ready in 90 s" >&2
  [ "$MODE" = docker ] && docker logs "$NAME" 2>&1 | tail -20 >&2
  exit 1
fi
echo ">> emulator ready"

export FIRESTORE_EMULATOR_HOST="127.0.0.1:${PORT}"
set +e
uv run pytest -m firestore -q -rs "$@"
STATUS=$?
set -e
echo ">> stopping emulator"
exit $STATUS
