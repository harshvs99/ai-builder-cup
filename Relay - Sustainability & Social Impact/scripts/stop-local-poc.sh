#!/usr/bin/env bash
# Stops the backend started by scripts/run-local-poc.sh (pid file in .run/).
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PIDFILE="$ROOT/.run/backend.pid"
QUIET=0; [ "${1:-}" = "--quiet" ] && QUIET=1
say() { [ "$QUIET" = 1 ] || echo "$@"; }

if [ ! -f "$PIDFILE" ]; then say "no pid file; nothing to stop"; exit 0; fi
PID="$(cat "$PIDFILE")"
if kill -0 "$PID" 2>/dev/null; then
  pkill -TERM -P "$PID" 2>/dev/null || true   # uv run may have spawned uvicorn as a child
  kill -TERM "$PID" 2>/dev/null || true
  for _ in $(seq 1 20); do kill -0 "$PID" 2>/dev/null || break; sleep 0.25; done
  if kill -0 "$PID" 2>/dev/null; then pkill -KILL -P "$PID" 2>/dev/null || true; kill -KILL "$PID" 2>/dev/null || true; fi
  say "stopped backend (pid $PID)"
else
  say "backend pid $PID not running"
fi
rm -f "$PIDFILE"
