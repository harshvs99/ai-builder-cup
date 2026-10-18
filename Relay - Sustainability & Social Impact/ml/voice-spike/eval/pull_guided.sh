#!/usr/bin/env bash
# Copy the guided recordings (and results/summary, if an eval has run) from the phone to eval/work/guided/ and score them.
#   eval/pull_guided.sh [adb-serial]       (default serial RZCY21LMG0J; adb from PATH or ~/Library/Android/sdk/platform-tools)
# Uses `run-as ... tar` because files written by the app are not readable by `adb pull`.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SERIAL="${1:-RZCY21LMG0J}"
ADB="$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")"
OUT="$HERE/work/guided"
mkdir -p "$OUT"
"$ADB" -s "$SERIAL" exec-out run-as org.relay.voicespike tar -cf - -C files guided | tar -xf - -C "$HERE/work"
echo "pulled: $(ls "$OUT"/*.wav 2>/dev/null | wc -l | tr -d ' ') wav -> $OUT"
if [ -s "$OUT/results.jsonl" ]; then python3 -I "$HERE/summarize.py" "$OUT/results.jsonl"; else echo "no results.jsonl yet (run the Eval tab on the phone)"; fi
