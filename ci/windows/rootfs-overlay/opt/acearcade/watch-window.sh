#!/bin/sh
# Windows programs run without a window manager here. This does the two jobs one would do for the app:
#  - writes the position and size of the largest visible top-level window ("x y w h") to $1 whenever it changes, so
#    the app can show the game's window filling the screen;
#  - gives the keyboard to the topmost visible window (the game, or a dialog in front of it) whenever that changes.
#    Without a window manager the X server sends key presses to whatever window is under the mouse pointer, and the
#    pointer only moves when the screen is touched, so the game's keys would otherwise depend on where the last tap
#    was.
OUT="$1"; LAST=""; LASTTOP=""
while :; do
  BEST=""; BA=0; TOP=""
  # xwininfo lists children bottom to top, so the last viewable one is the topmost.
  for id in $(xwininfo -root -children 2>/dev/null | sed -n 's/^ *\(0x[0-9a-f]*\) .*/\1/p'); do
    INFO=$(xwininfo -id "$id" 2>/dev/null) || continue
    echo "$INFO" | grep -q "Map State: IsViewable" || continue
    X=$(echo "$INFO" | sed -n 's/.*Absolute upper-left X: *\(-*[0-9]*\).*/\1/p')
    Y=$(echo "$INFO" | sed -n 's/.*Absolute upper-left Y: *\(-*[0-9]*\).*/\1/p')
    WW=$(echo "$INFO" | sed -n 's/.*Width: *\([0-9]*\).*/\1/p')
    HH=$(echo "$INFO" | sed -n 's/.*Height: *\([0-9]*\).*/\1/p')
    [ "$WW" -gt 32 ] && [ "$HH" -gt 32 ] || continue
    TOP="$id"
    A=$((WW * HH))
    [ "$A" -gt "$BA" ] && { BA=$A; BEST="$X $Y $WW $HH"; }
  done
  if [ "$BEST" != "$LAST" ]; then echo "$BEST" > "$OUT.tmp" && mv "$OUT.tmp" "$OUT"; LAST="$BEST"; fi
  if [ -n "$TOP" ] && [ "$TOP" != "$LASTTOP" ]; then
    xdotool windowfocus "$TOP" 2>/dev/null && echo "[window] keyboard focus: $TOP"
    LASTTOP="$TOP"
  fi
  sleep 1
done
