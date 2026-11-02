#!/bin/sh
# AceArcade Windows runtime, started by the app under PRoot:  run.sh <program.exe (Linux path)> <width> <height>
# Display: a VNC X server the app shows on screen (and sends touches/keys to). Audio: PulseAudio, streamed to the app.
# Windows programs: Wine (x86_64 WoW64 build, so 32- and 64-bit programs) run through Box64 on the phone's ARM CPU.
# Everything the app reads is written to /tmp/acearcade (a folder the app shares with this environment).
EXE="$1"; W="${2:-1280}"; H="${3:-720}"
S=/tmp/acearcade
mkdir -p "$S" /tmp/.X11-unix
stage() { echo "$1" > "$S/stage.tmp" && mv "$S/stage.tmp" "$S/stage"; echo "[run] $1"; }
export HOME=/root USER=root LANG=C.UTF-8 TMPDIR=/tmp DISPLAY=:0
export PATH=/opt/wine/bin:/usr/local/bin:/usr/bin:/bin
export WINEPREFIX=/root/.wine WINEDLLOVERRIDES="mscoree,mshtml=" WINEESYNC=0 WINEFSYNC=0
export PULSE_SERVER="unix:$S/pulse.sock"
export BOX64_NOBANNER=1 BOX64_LOG=0
# Wine's own errors are logged (not its warnings); the app can override any of these settings for a game in
# /tmp/acearcade-env (written before each launch).
export WINEDEBUG=err+all,fixme-all,+process,+loaddll
# OpenGL on the CPU (Mesa llvmpipe): games drawing with OpenGL (SFML, SDL, Pokémon SDK's LiteRGSS) get a working
# context on the VNC display, which has no GPU behind it.
export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe MESA_GL_VERSION_OVERRIDE=3.3 LP_NUM_THREADS=4
[ -f /tmp/acearcade-env ] && . /tmp/acearcade-env
rm -f /tmp/.X0-lock /tmp/.X11-unix/X0 "$S/exit" "$S/window"

stage display
Xvnc :0 -geometry "${W}x${H}" -depth 24 -SecurityTypes None -localhost -rfbport 5900 -AlwaysShared -ac \
  -nolisten tcp > "$S/xvnc.log" 2>&1 &
XPID=$!
pulseaudio -n -F /opt/acearcade/pulse.pa --daemonize=no --exit-idle-time=-1 --use-pid-file=no > "$S/pulse.log" 2>&1 &
PPID_AUDIO=$!
i=0; while [ ! -e /tmp/.X11-unix/X0 ] && [ $i -lt 200 ]; do sleep 0.1; i=$((i+1)); done
if [ ! -e /tmp/.X11-unix/X0 ]; then stage "failed: the display server didn't start"; echo 127 > "$S/exit"; exit 127; fi
/opt/acearcade/watch-window.sh "$S/window" &
WPID=$!

if [ ! -f "$WINEPREFIX/.acearcade-ready" ]; then
  stage prefix
  box64 wine wineboot -i > "$S/wineboot.log" 2>&1
  box64 wineserver -w
  [ -f "$WINEPREFIX/system.reg" ] && touch "$WINEPREFIX/.acearcade-ready"
fi

stage game
cd "$(dirname "$EXE")" || true
box64 wine "$EXE" > "$S/wine.log" 2>&1
RC=$?
box64 wineserver -w 2>/dev/null
echo "$RC" > "$S/exit"
stage "exited $RC"
kill $WPID $PPID_AUDIO $XPID 2>/dev/null
wait 2>/dev/null
exit $RC
