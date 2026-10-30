#!/usr/bin/env bash
# Starts the bundled mkxp-z on a generated RPG Maker XP test game the way AceArcade does (working directory =
# game folder) and checks that the game's own script ran: it writes launch_ok after ~3 s of rendered frames.
#   mkxpz-launch-test.sh <mkxp-z executable> <scratch dir>
set -uo pipefail
EXE="$1"; G="$2/launch-test-game"
rm -rf "$G"; ruby "$(dirname "$0")/make_launch_test_rgss.rb" "$G" >/dev/null
cd "$G"
"$EXE" > engine.log 2>&1 & PID=$!
shot=""
for i in $(seq 1 120); do
  sleep 1
  if [ -z "$shot" ] && [ -f launch_shot_now ]; then
    screencapture -x "$2/shot.png" 2>/dev/null && sips -Z 200 -s format jpeg "$2/shot.png" --out "$2/shot.jpg" >/dev/null 2>&1 && shot=1
  fi
  [ -f launch_ok ] || [ -f launch_error ] && break
  kill -0 $PID 2>/dev/null || break
done
sleep 2; kill $PID 2>/dev/null; wait $PID 2>/dev/null; CODE=$?
echo "--- engine log (exit $CODE)"; cat engine.log
ls -la
n=0
while IFS= read -r line; do n=$((n+1)); [ $n -le 3 ] && echo "::notice title=mkxp-z log $n::${line:0:900}"; done < <(fold -w 900 engine.log | tail -3)
CR=$(ls -t ~/Library/Logs/DiagnosticReports/*mkxp* 2>/dev/null | head -1)
[ -n "$CR" ] && echo "::notice title=mkxp-z crash report::$(grep -m1 -A12 -i 'exception\|"exception"\|Crashed' "$CR" | tr '\n' ' ' | cut -c1-900)"
# A crash (signal) without a result: run once more under lldb and report the backtrace of the crashing thread.
if [ ! -f launch_ok ] && [ "${CODE:-0}" -ge 128 ] && command -v lldb >/dev/null; then
  rm -f launch_started launch_error
  timeout_s=60; ( sleep $timeout_s; pkill -f "$EXE" ) & W=$!
  lldb --batch -o "process launch --" -o "bt 25" -o "quit" -- "$EXE" > "$2/lldb.txt" 2>&1
  kill $W 2>/dev/null
  awk '/stop reason|frame #/' "$2/lldb.txt" | head -26 | cut -c1-300 | paste -sd"|" - | fold -w 900 | head -5 | while IFS= read -r l; do
    echo "::notice title=mkxp-z crash backtrace::${l:0:900}"
  done
fi
if [ -f launch_ok ]; then
  echo "::notice title=mkxp-z launch test passed::$(cat launch_ok)"
  [ -n "$shot" ] && echo "::notice title=mkxp-z launch screenshot (jpeg base64)::$(base64 < "$2/shot.jpg" | tr -d '\n')"
  exit 0
fi
msg="exit $CODE after ${i}s: $( [ -f launch_error ] && cat launch_error || echo "the game script wrote no result" )$( [ -f launch_started ] && echo " (script started)" || echo " (script never started)")"
echo "::error title=mkxp-z launch test failed::${msg:0:900}"
exit 1
