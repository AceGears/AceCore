#!/usr/bin/env bash
# Starts each bundled desktop engine on a real test game, the way AceArcade's Planner does, and reports what
# actually happened (evidence from the engine/game, not just "the process started"):
#   EasyRPG  EasyRPG TestGame-2000 (pinned)  -> log line "Engine configured as" (the game database loaded)
#   Godot    ci/godot-test exported as .pck  -> the game's own script writes godot_started + godot_walk (frames)
#   Ruffle   a generated one-frame SWF       -> still running after 15 s, no panic in its output
#   engine-launch-tests.sh <windows|macos> <runtimes dir> <scratch dir>
set -uo pipefail
OS="$1"; R="$2"; T="$3"; HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"
mkdir -p "$T"; failed=0

find_one() { find "$R/$1" -type f \( -name "$2" \) 2>/dev/null | head -1; }

# run <log> <seconds> <check-cmd> <cmd...>: starts cmd, polls check-cmd every second, then stops the engine.
run() {
  local log="$1" secs="$2" check="$3"; shift 3
  "$@" > "$log" 2>&1 & local pid=$!
  local ok=1
  for _ in $(seq 1 "$secs"); do
    sleep 1
    if eval "$check"; then ok=0; break; fi
    kill -0 $pid 2>/dev/null || break
  done
  if kill -0 $pid 2>/dev/null; then ALIVE=1; else ALIVE=0; fi
  kill $pid 2>/dev/null; [ "$OS" = windows ] && taskkill //F //T //PID "$(cat /proc/$pid/winpid 2>/dev/null)" >/dev/null 2>&1
  wait $pid 2>/dev/null
  return $ok
}
report() { # report <name> <0|1> <detail>
  if [ "$2" = 0 ]; then echo "::notice title=$1 launch test passed::${3:0:900}"
  else echo "::error title=$1 launch test failed::${3:0:900}"; failed=1; fi
}

# --- EasyRPG Player + EasyRPG TestGame-2000
E=$( [ "$OS" = macos ] && find "$R/easyrpg" -type f -path '*Contents/MacOS/*' | head -1 || find_one easyrpg 'Player.exe' )
if [ -n "$E" ]; then
  git init -q "$T/tg" && git -C "$T/tg" remote add origin https://github.com/EasyRPG/TestGame.git
  git -C "$T/tg" fetch -q --depth 1 origin 4bbedb73492c80b79d8290e0b06ea86875b0bdc8 && git -C "$T/tg" checkout -q FETCH_HEAD
  G="$T/tg/TestGame-2000"
  if run "$T/easyrpg.out" 60 "grep -rqs 'Engine configured as' '$T/easyrpg.out' '$G' ~/Library/Application\ Support/EasyRPG 2>/dev/null" \
       "$E" --project-path "$G" --window; then
    report EasyRPG 0 "$(grep -rhs 'Engine configured as' "$T/easyrpg.out" "$G" 2>/dev/null | head -1)"
  else report EasyRPG 1 "$(tail -c 600 "$T/easyrpg.out" | tr '\n' ' ') $(cat "$G"/easyrpg_log.txt 2>/dev/null | tail -c 300 | tr '\n' ' ')"; fi
else report EasyRPG 1 "EasyRPG Player not found under $R/easyrpg"; fi

# --- Godot (macOS: runs Godot 4 packs; on Windows Godot games bring their own program)
if [ "$OS" = macos ]; then
  GD=$(find "$R/godot" -type f -path '*Contents/MacOS/Godot' | head -1)
  if [ -n "$GD" ]; then
    "$GD" --headless --path "$ROOT/ci/godot-test" --import > /dev/null 2>&1 || true
    "$GD" --headless --path "$ROOT/ci/godot-test" --export-pack "Windows Desktop" "$T/TestGame.pck" > "$T/godot-export.out" 2>&1 || true
    if head -c 4 "$T/TestGame.pck" 2>/dev/null | grep -q GDPC; then
      mkdir -p "$T/godot-out"
      if run "$T/godot.out" 90 "[ -f '$T/godot-out/godot_walk' ] && [ \$(cut -d' ' -f3 '$T/godot-out/godot_walk') -ge 60 ]" \
           "$GD" --main-pack "$T/TestGame.pck" -- --out="$T/godot-out"; then
        report Godot 0 "Godot $(cat "$T/godot-out/godot_started"): the game's script ran $(cut -d' ' -f3 "$T/godot-out/godot_walk") frames"
      else report Godot 1 "started=$(cat "$T/godot-out/godot_started" 2>/dev/null || echo no) walk=$(cat "$T/godot-out/godot_walk" 2>/dev/null || echo none) | $(grep -i 'error\|fail' "$T/godot.out" | head -5 | tr '\n' ' ')"; fi
    else report Godot 1 "could not export the test pack: $(tail -c 400 "$T/godot-export.out" | tr '\n' ' ')"; fi
  else report Godot 1 "Godot not found under $R/godot"; fi
fi

# --- Ruffle + a generated SWF
RF=$( [ "$OS" = macos ] && (find "$R/ruffle" -type f -path '*Contents/MacOS/*' | head -1) || find_one ruffle 'ruffle.exe' )
if [ -n "$RF" ]; then
  "$(command -v python3 || command -v python)" - "$T/test.swf" <<'PY'
import struct, sys
def bits(vals, n):
    s = format(n, '05b') + ''.join(format(v & ((1 << n) - 1), f'0{n}b') for v in vals)
    s += '0' * (-len(s) % 8)
    return bytes(int(s[i:i+8], 2) for i in range(0, len(s), 8))
tag = lambda code, data: struct.pack('<H', (code << 6) | len(data)) + data
body = bits([0, 11000, 0, 8000], 15) + struct.pack('<HH', 24 << 8, 1) \
     + tag(9, bytes([11, 8, 32])) + tag(1, b'') + tag(0, b'')
open(sys.argv[1], 'wb').write(b'FWS' + bytes([10]) + struct.pack('<I', 8 + len(body)) + body)
PY
  run "$T/ruffle.out" 15 "false" "$RF" "$T/test.swf"
  if [ "$ALIVE" != 1 ]; then report Ruffle 1 "Ruffle exited within 15 s: $(tail -c 500 "$T/ruffle.out" | tr '\n' ' ')"
  elif grep -qi 'panic\|error: \|failed to' "$T/ruffle.out"; then report Ruffle 1 "$(grep -i 'panic\|error\|failed' "$T/ruffle.out" | head -5 | tr '\n' ' ')"
  else report Ruffle 0 "ran the SWF for 15 s without errors $(head -c 200 "$T/ruffle.out" | tr '\n' ' ')"; fi
else report Ruffle 1 "Ruffle not found under $R/ruffle"; fi

exit $failed
