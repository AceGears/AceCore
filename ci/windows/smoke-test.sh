#!/bin/bash
# Runs a 32-bit Windows program through the packed runtime (Box64 + Wine + Xvnc) on arm64 emulated by QEMU.
# QEMU is much slower than a phone and emulates signals differently, so a failure here is a lead, not proof; a pass
# shows the runtime's pieces fit together (Box64 runs Wine, WoW64 runs 32-bit code, the X server takes windows).
# Usage: smoke-test.sh windows-rootfs.tar.xz RESULT_FILE
set -uo pipefail
TAR=$(realpath "$1"); RES=$(realpath -m "$2")
HERE=$(cd "$(dirname "$0")" && pwd)
T=$(mktemp -d); chmod 777 "$T"
i686-w64-mingw32-gcc -O2 -mwindows -o "$T/smoke32.exe" "$HERE/smoke/smoke.c" || { echo "fail: MinGW build" > "$RES"; exit 1; }
docker import --platform linux/arm64 "$TAR" acearcade-windows:smoke > /dev/null || { echo "fail: docker import" > "$RES"; exit 1; }
start=$(date +%s)
docker run --rm --platform linux/arm64 -v "$T:/test" acearcade-windows:smoke /bin/sh -c '
  export HOME=/root DISPLAY=:0 PATH=/opt/wine/bin:/usr/local/bin:/usr/bin:/bin WINEPREFIX=/root/.wine
  export WINEDEBUG=-all WINEDLLOVERRIDES="mscoree,mshtml=" BOX64_NOBANNER=1 BOX64_LOG=1
  mkdir -p /tmp/.X11-unix
  Xvnc :0 -geometry 800x600 -depth 24 -SecurityTypes None -localhost -ac -nolisten tcp > /test/xvnc.log 2>&1 &
  sleep 3
  box64 --version
  timeout 2400 box64 wine wineboot -i > /test/wineboot.log 2>&1; echo "wineboot rc=$?"
  # Started the way run.sh starts games: the program'"'"'s folder is drive G:.
  ln -sfn /test "$WINEPREFIX/dosdevices/g:"
  cd /test && timeout 900 box64 wine '"'"'G:\smoke32.exe'"'"' > /test/wine.log 2>&1; echo "smoke rc=$?"
  box64 wineserver -k 2>/dev/null
' 2>&1 | tail -20
secs=$(( $(date +%s) - start ))
if [ -f "$T/smoke-ok.txt" ] && grep -qiF 'cwd=G:\ ' "$T/smoke-ok.txt"; then
  echo "pass: $(cat "$T/smoke-ok.txt") (${secs}s on QEMU)" > "$RES"
elif [ -f "$T/smoke-ok.txt" ]; then
  echo "fail: the program ran but not from drive G: ($(cat "$T/smoke-ok.txt"), ${secs}s on QEMU)" > "$RES"
else
  { echo "fail: no output from the test program (${secs}s on QEMU)"; tail -15 "$T/wineboot.log" "$T/wine.log" 2>/dev/null; } > "$RES"
fi
cat "$RES"
