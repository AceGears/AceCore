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
sleep 2; kill $PID 2>/dev/null; wait $PID 2>/dev/null
echo "--- engine log"; tail -40 engine.log
if [ -f launch_ok ]; then
  echo "::notice title=mkxp-z launch test passed::$(cat launch_ok)"
  [ -n "$shot" ] && echo "::notice title=mkxp-z launch screenshot (jpeg base64)::$(base64 < "$2/shot.jpg" | tr -d '\n')"
  exit 0
fi
msg="$( [ -f launch_error ] && cat launch_error || echo "no result after ${i}s" ) | $(tail -5 engine.log | tr '\n' ' ')"
echo "::error title=mkxp-z launch test failed::${msg:0:900}"
exit 1
