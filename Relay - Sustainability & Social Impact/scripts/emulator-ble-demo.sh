#!/usr/bin/env bash
# Two-emulator BLE mesh demo: real Android BLE (GATT) between two emulators over netsim's virtual radio.
#
#   emulator A (origin)  --BLE-->  emulator B (gateway)  --HTTP-->  backend on the host
#
# Steps (each prints PASS/FAIL with the evidence line; the script stops at the first FAIL):
#   a  both nodes advertise + scan, link up, HELLO exchanged, B is gateway, A is 1 hop from it
#   b  NEED from A reaches B over BLE, B uploads it, a MEDICAL ticket appears in the backend
#   c  RECEIVED ACK (Odia text) travels backend -> B -> BLE -> A
#   d  dashboard approve -> DISPATCHED ACK reaches A
#   e  store-and-forward: B's Bluetooth off, second NEED from A is held, B back on, it is delivered by itself
#
# Prerequisites: both emulators booted (see docs/demo-runbook.md), backend running (started for you if
# /healthz does not answer), Android Studio JBR for the Gradle build.
# Flags: --clean (uninstall first)  --no-install (reuse the installed app)  --no-build  --skip-offline (skip step e)
# Env:   A=emulator-5554  B=emulator-5556  HOST_URL=http://localhost:8080  DEVICE_URL=http://10.0.2.2:8080
#        DEVICE_KEY=dev-relay-key  ADB=~/Library/Android/sdk/platform-tools/adb
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
A="${A:-emulator-5554}"; B="${B:-emulator-5556}"
HOST_URL="${HOST_URL:-http://localhost:8080}"
DEVICE_URL="${DEVICE_URL:-http://10.0.2.2:8080}"
DEVICE_KEY="${DEVICE_KEY:-dev-relay-key}"
PKG=org.relay.app
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
LOGDIR="$ROOT/.run/ble-demo-logs"
CLEAN=0; INSTALL=1; BUILD=1; OFFLINE_STEP=1
for a in "$@"; do
  case "$a" in
    --clean) CLEAN=1 ;; --no-install) INSTALL=0 ;; --no-build) BUILD=0 ;; --skip-offline) OFFLINE_STEP=0 ;;
    -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
    *) echo "unknown flag: $a" >&2; exit 2 ;;
  esac
done
if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
mkdir -p "$LOGDIR"
LOG_A="$LOGDIR/A.log"; LOG_B="$LOGDIR/B.log"
PIDS=()
cleanup() { for p in "${PIDS[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null; done; }
trap cleanup EXIT

now_ms() { echo $(( $(date +%s%N) / 1000000 )); }
T_START=$(now_ms)
STEP_N=0
pass() { printf '  PASS  %-44s %s\n' "$1" "${2:-}"; }
fail() {
  printf '  FAIL  %-44s %s\n' "$1" "${2:-}"
  echo; echo "---- last RelayMesh lines, A ($A) ----"; grep -v "advertising started" "$LOG_A" 2>/dev/null | tail -15 | cut -c1-200
  echo "---- last RelayMesh lines, B ($B) ----"; grep -v "advertising started" "$LOG_B" 2>/dev/null | tail -15 | cut -c1-200
  echo; echo "RESULT: FAIL (full logs in $LOGDIR)"; exit 1
}

# wait_log <file> <ERE> <timeout_s> [from_line]  -> prints the first matching line, returns 0 on match
wait_log() {
  local f="$1" re="$2" to="$3" from="${4:-0}" end=$(( $(date +%s) + $3 )) line
  while :; do
    line="$(tail -n +"$((from + 1))" "$f" 2>/dev/null | grep -E -m1 -- "$re")" && [ -n "$line" ] && { echo "$line"; return 0; }
    [ "$(date +%s)" -ge "$end" ] && return 1
    sleep 0.25
  done
}
lines() { wc -l <"$1" 2>/dev/null | tr -d ' '; }
short() { sed -E 's/^[0-9.]+ +[0-9]+ +[0-9]+ I RelayMesh: //' | cut -c1-170; }
ticket_for() { # <msg_id> -> "ticket_no category max_hops status" or empty
  curl -fsS "$HOST_URL/v1/tickets" 2>/dev/null | python3 -c '
import sys, json
mid = sys.argv[1]
for t in json.load(sys.stdin)["tickets"]:
    if mid in t["msg_ids"]:
        print(t["ticket_no"], t["category"], t["max_hops"], t["status"]); break' "$1" 2>/dev/null
}
wait_ticket() { # <msg_id> <timeout_s>
  local end=$(( $(date +%s) + $2 )) r
  while :; do
    r="$(ticket_for "$1")"; [ -n "$r" ] && { echo "$r"; return 0; }
    [ "$(date +%s)" -ge "$end" ] && return 1
    sleep 0.25
  done
}
send_need() { # <serial> <text> <lang> <lat> <lon>
  local t="${2//\'/\'\\\'\'}"
  "$ADB" -s "$1" shell "am broadcast -n $PKG/.debug.DebugReceiver -a org.relay.app.DEBUG_SEND --es text '$t' --es lang $3 --es lat $4 --es lon $5 --es acc 8" >/dev/null
}
has_odia() { python3 -c 'import sys,re; sys.exit(0 if re.search("[଀-୿]", sys.stdin.read()) else 1)'; }

echo "== Relay two-emulator BLE demo (A=$A origin, B=$B gateway, backend $HOST_URL) =="

# ---------------------------------------------------------------- 0. preflight
echo "[0] preflight"
"$ADB" devices | grep -q "^$A[[:space:]]*device" || fail "emulator A online" "$A not in 'adb devices' (see docs/demo-runbook.md)"
"$ADB" devices | grep -q "^$B[[:space:]]*device" || fail "emulator B online" "$B not in 'adb devices'"
pass "both emulators online" "$A, $B"
if ! curl -fsS "$HOST_URL/healthz" >/dev/null 2>&1; then
  echo "  backend not answering; starting it via scripts/run-local-poc.sh --no-e2e"
  "$ROOT/scripts/run-local-poc.sh" --no-e2e >"$LOGDIR/backend-start.log" 2>&1 || { tail -20 "$LOGDIR/backend-start.log"; fail "backend start" "see $LOGDIR/backend-start.log"; }
fi
curl -fsS "$HOST_URL/healthz" >/dev/null 2>&1 && pass "backend healthy" "$HOST_URL/healthz" || fail "backend healthy" "$HOST_URL"

# ---------------------------------------------------------------- 1. install + configure + start
echo "[1] install, configure, start"
if [ "$INSTALL" = 1 ]; then
  if [ "$BUILD" = 1 ]; then
    (cd "$ROOT/android" && ./gradlew --console=plain -q :app:assembleDebug) >"$LOGDIR/build.log" 2>&1 || { tail -20 "$LOGDIR/build.log"; fail "gradle assembleDebug" "see $LOGDIR/build.log"; }
  fi
  [ -f "$APK" ] || fail "apk present" "$APK missing"
  for d in "$A" "$B"; do
    [ "$CLEAN" = 1 ] && "$ADB" -s "$d" uninstall $PKG >/dev/null 2>&1
    "$ADB" -s "$d" install -r -g "$APK" 2>&1 | grep -q Success || fail "install on $d" "adb install failed"
  done
  pass "installed (runtime permissions granted with -g)" "$([ "$CLEAN" = 1 ] && echo clean || echo upgrade)"
fi
for d in "$A" "$B"; do
  "$ADB" -s "$d" shell am force-stop $PKG
  for p in BLUETOOTH_SCAN BLUETOOTH_ADVERTISE BLUETOOTH_CONNECT ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS RECORD_AUDIO; do
    "$ADB" -s "$d" shell pm grant $PKG android.permission.$p >/dev/null 2>&1
  done
  "$ADB" -s "$d" shell cmd bluetooth_manager enable >/dev/null 2>&1
  for _ in $(seq 1 40); do [ "$("$ADB" -s "$d" shell settings get global bluetooth_on | tr -d '\r')" = 1 ] && break; sleep 0.5; done
  [ "$("$ADB" -s "$d" shell settings get global bluetooth_on | tr -d '\r')" = 1 ] || fail "Bluetooth on ($d)" "bluetooth_on != 1"
  "$ADB" -s "$d" logcat -c
done
: >"$LOG_A"; : >"$LOG_B"
"$ADB" -s "$A" logcat -v epoch -s RelayMesh >"$LOG_A" 2>&1 & PIDS+=($!)
"$ADB" -s "$B" logcat -v epoch -s RelayMesh >"$LOG_B" 2>&1 & PIDS+=($!)
sleep 1
# Configure BEFORE the service starts so A never acts as a gateway even though the emulator has internet.
"$ADB" -s "$B" shell am broadcast -n $PKG/.debug.DebugReceiver -a org.relay.app.DEBUG_CONFIG --es backend "$DEVICE_URL" --es key "$DEVICE_KEY" --es offline false >/dev/null
"$ADB" -s "$A" shell am broadcast -n $PKG/.debug.DebugReceiver -a org.relay.app.DEBUG_CONFIG --es backend "$DEVICE_URL" --es key "$DEVICE_KEY" --es offline true >/dev/null
wait_log "$LOG_B" "DEBUG_CONFIG backend=.*offline=false" 15 >/dev/null || fail "configure B (gateway)" "DEBUG_CONFIG not logged"
wait_log "$LOG_A" "DEBUG_CONFIG backend=.*offline=true" 15 >/dev/null || fail "configure A (origin, offline)" "DEBUG_CONFIG not logged"
pass "configured" "B gateway (offline=false), A origin (offline=true), backend $DEVICE_URL"
for d in "$B" "$A"; do
  out="$("$ADB" -s "$d" shell am start-foreground-service -n $PKG/.RelayService 2>&1)"
  if echo "$out" | grep -qiE "error|not allowed|denied"; then
    "$ADB" -s "$d" shell am start -n $PKG/.MainActivity >/dev/null 2>&1; sleep 2
    "$ADB" -s "$d" shell am start-foreground-service -n $PKG/.RelayService >/dev/null 2>&1
  fi
done
ID_B="$(wait_log "$LOG_B" "engine: starting \(node [0-9a-f]{16}\)" 20 | sed -E 's/.*\(node ([0-9a-f]{16})\).*/\1/')"
ID_A="$(wait_log "$LOG_A" "engine: starting \(node [0-9a-f]{16}\)" 20 | sed -E 's/.*\(node ([0-9a-f]{16})\).*/\1/')"
[ -n "$ID_A" ] && [ -n "$ID_B" ] || fail "RelayService started on both" "no 'engine: starting' line (A='$ID_A' B='$ID_B')"
pass "RelayService running" "A node $ID_A, B node $ID_B"

# ---------------------------------------------------------------- a. BLE link + HELLO
echo "[a] BLE discovery, link, HELLO, gateway state"
T_LINK0=$(now_ms)
for who in A B; do
  f="$LOG_A"; [ $who = B ] && f="$LOG_B"
  wait_log "$f" "BLE: scan started" 20 >/dev/null && wait_log "$f" "BLE: advertising started" 20 >/dev/null || fail "$who advertises and scans" "no 'scan started'/'advertising started'"
done
pass "both advertise + scan"
l1="$(wait_log "$LOG_A" "linkUp [pc]:" 60)" || fail "linkUp on A" "no link in 60 s (BLE radio not shared? see docs/demo-runbook.md)"
l2="$(wait_log "$LOG_B" "linkUp [pc]:" 60)" || fail "linkUp on B" "no link in 60 s"
pass "linkUp on A" "$(echo "$l1" | short)"; pass "linkUp on B" "$(echo "$l2" | short)"
h1="$(wait_log "$LOG_A" "HELLO from $ID_B .*gateway=true hops=0" 30)" || fail "A receives B's HELLO (gateway, hops 0)" "not seen"
h2="$(wait_log "$LOG_B" "HELLO from $ID_A .*gateway=false hops=1" 45)" || fail "B receives A's HELLO (hops-to-gateway 1)" "not seen"
pass "HELLO at A: B is a gateway, 0 hops" "$(echo "$h1" | short)"
pass "HELLO at B: A is 1 hop from the gateway" "$(echo "$h2" | short)"
g="$(wait_log "$LOG_B" "gateway: ONLINE" 20)" || fail "B reports gateway ONLINE" "not seen"
pass "B reports gateway" "$(echo "$g" | short)"
grep -q "gateway: ONLINE\|sync ok" "$LOG_A" && fail "A stays a non-gateway" "A synced to the backend although offline=true"
pass "A is not a gateway (offline=true)"
pass "link established in" "$(( $(now_ms) - T_LINK0 )) ms after service start"

# ---------------------------------------------------------------- b. NEED A -> B -> backend
echo "[b] NEED from A reaches the backend"
mA=$(lines "$LOG_A"); mB=$(lines "$LOG_B")
T0=$(now_ms)
send_need "$A" "my mother needs insulin, near the school" or 20.2961 85.8245
s="$(wait_log "$LOG_A" "DEBUG_SEND .*-> NEED [0-9a-f]{16}" 10 "$mA")" || fail "DEBUG_SEND accepted" "no DEBUG_SEND line (debug receiver not reached?)"
MSG="$(echo "$s" | sed -E 's/.*-> NEED ([0-9a-f]{16}).*/\1/')"
o="$(wait_log "$LOG_A" "originated NEED $MSG" 10 "$mA")" || fail "A originated NEED" "not seen"
pass "A originated NEED" "$(echo "$o" | short)"
d="$(wait_log "$LOG_B" "NEED delivered $MSG from=[pc]:" 20 "$mB")" || fail "B received NEED over BLE" "not seen in 20 s"
pass "B NEED delivered over BLE" "$(echo "$d" | short)"
y="$(wait_log "$LOG_B" "sync ok frames=[1-9].*accepted=1" 20 "$mB")" || fail "B sync ok accepted=1" "not seen"
pass "B uploaded to backend" "$(echo "$y" | short)"
tk="$(wait_ticket "$MSG" 20)" || fail "ticket in backend" "no ticket for msg $MSG"
T_TICKET=$(( $(now_ms) - T0 ))
read -r TNO TCAT THOPS TSTAT <<<"$tk"
[ "$TCAT" = MEDICAL ] || fail "ticket category MEDICAL" "got $TCAT"
pass "backend ticket #$TNO $TCAT max_hops=$THOPS ($TSTAT)" "DEBUG_SEND -> ticket visible in ${T_TICKET} ms"
[ "$THOPS" = 0 ] && pass "max_hops=0 as SPEC 7 defines (hops = 7 - ttl; an adjacent gateway sees ttl 7)" || echo "  note  max_hops=$THOPS (SPEC: hops = 7 - ttl on arrival at the gateway)"

# ---------------------------------------------------------------- c. RECEIVED ACK back to A
echo "[c] RECEIVED ACK flows back to A"
og="$(wait_log "$LOG_B" "originating ACK ref=$MSG ticket=#$TNO status=RECEIVED" 20 "$mB")" || fail "B originates the RECEIVED ACK" "not seen"
pass "B floods RECEIVED ACK" "$(echo "$og" | short)"
ak="$(wait_log "$LOG_A" "ACK for me ref=$MSG .*status=RECEIVED" 30 "$mA")" || fail "A receives RECEIVED ACK" "not seen in 30 s"
T_ACK=$(( $(now_ms) - T0 ))
echo "$ak" | has_odia || fail "ACK text is Odia" "$(echo "$ak" | short)"
pass "A ACK for me (RECEIVED, Odia)" "DEBUG_SEND -> ACK at A in ${T_ACK} ms"
echo "        $(echo "$ak" | short)"

# ---------------------------------------------------------------- d. approve -> DISPATCHED
echo "[d] dashboard approve -> DISPATCHED ACK at A"
mA=$(lines "$LOG_A"); T1=$(now_ms)
curl -fsS -X POST "$HOST_URL/v1/tickets/$TNO/approve" >/dev/null || fail "approve ticket #$TNO" "POST failed"
dk="$(wait_log "$LOG_A" "ACK for me ref=$MSG .*status=DISPATCHED" 45 "$mA")" || fail "A receives DISPATCHED ACK" "not seen in 45 s"
T_DISP=$(( $(now_ms) - T1 ))
echo "$dk" | has_odia || fail "DISPATCHED text is Odia" "$(echo "$dk" | short)"
pass "A ACK for me (DISPATCHED, Odia)" "approve -> ACK at A in ${T_DISP} ms (gateway polls every 10 s)"
echo "        $(echo "$dk" | short)"

# ---------------------------------------------------------------- e. store and forward
if [ "$OFFLINE_STEP" = 1 ]; then
  echo "[e] store-and-forward across a gateway outage"
  mA=$(lines "$LOG_A"); mB=$(lines "$LOG_B")
  "$ADB" -s "$B" shell cmd bluetooth_manager disable >/dev/null
  wait_log "$LOG_B" "Bluetooth turned off" 20 "$mB" >/dev/null || fail "B Bluetooth off" "app did not see STATE_OFF"
  wait_log "$LOG_A" "linkDown" 30 "$mA" >/dev/null || echo "  note  A has not yet reported linkDown (supervision timeout); continuing"
  pass "B Bluetooth disabled" "B's transport stopped"
  mA=$(lines "$LOG_A"); mB=$(lines "$LOG_B")
  send_need "$A" "we are stuck, water is rising, 5 people on the roof" en 20.3010 85.8300
  s2="$(wait_log "$LOG_A" "DEBUG_SEND .*-> NEED [0-9a-f]{16}" 10 "$mA")" || fail "second DEBUG_SEND" "not seen"
  MSG2="$(echo "$s2" | sed -E 's/.*-> NEED ([0-9a-f]{16}).*/\1/')"
  pass "A originated second NEED while B is offline" "$MSG2"
  sleep 10
  [ -z "$(ticket_for "$MSG2")" ] && pass "not delivered while B is offline (held in A's store)" || fail "second NEED must not reach backend yet" "ticket already exists"
  mA=$(lines "$LOG_A"); mB=$(lines "$LOG_B"); T2=$(now_ms)
  "$ADB" -s "$B" shell cmd bluetooth_manager enable >/dev/null
  wait_log "$LOG_B" "Bluetooth turned on" 30 "$mB" >/dev/null || fail "B Bluetooth on" "app did not see STATE_ON"
  dd2="$(wait_log "$LOG_B" "NEED delivered $MSG2 from=[pc]:" 90 "$mB")" || fail "B receives the held NEED after reconnect" "not seen in 90 s"
  pass "B NEED delivered after reconnect (no user action)" "$(echo "$dd2" | short)"
  tk2="$(wait_ticket "$MSG2" 30)" || fail "second ticket in backend" "none for $MSG2"
  read -r TNO2 TCAT2 THOPS2 TSTAT2 <<<"$tk2"
  pass "backend ticket #$TNO2 $TCAT2 ($TSTAT2)" "Bluetooth re-enabled -> ticket in $(( $(now_ms) - T2 )) ms"
  ak2="$(wait_log "$LOG_A" "ACK for me ref=$MSG2 .*status=RECEIVED" 45 "$mA")" || fail "A receives RECEIVED ACK for the second NEED" "not seen"
  pass "A ACK for me (second NEED)" "$(echo "$ak2" | short)"
fi

echo
echo "RESULT: PASS  (A->ticket ${T_TICKET} ms, A->ACK ${T_ACK} ms, approve->ACK ${T_DISP} ms; logs in $LOGDIR)"
echo "Dashboard: $HOST_URL/   Services stay running on both emulators."
