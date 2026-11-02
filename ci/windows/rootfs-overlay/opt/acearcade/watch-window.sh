#!/bin/sh
# Writes the position and size of the largest visible top-level window ("x y w h") to $1 whenever it changes, so the
# app can show the game's window filling the screen. Windows programs run without a window manager here.
OUT="$1"; LAST=""
while :; do
  BEST=""; BA=0
  for id in $(xwininfo -root -children 2>/dev/null | sed -n 's/^ *\(0x[0-9a-f]*\) .*/\1/p'); do
    INFO=$(xwininfo -id "$id" 2>/dev/null) || continue
    echo "$INFO" | grep -q "Map State: IsViewable" || continue
    X=$(echo "$INFO" | sed -n 's/.*Absolute upper-left X: *\(-*[0-9]*\).*/\1/p')
    Y=$(echo "$INFO" | sed -n 's/.*Absolute upper-left Y: *\(-*[0-9]*\).*/\1/p')
    WW=$(echo "$INFO" | sed -n 's/.*Width: *\([0-9]*\).*/\1/p')
    HH=$(echo "$INFO" | sed -n 's/.*Height: *\([0-9]*\).*/\1/p')
    A=$((WW * HH))
    [ "$A" -gt "$BA" ] && [ "$WW" -gt 32 ] && [ "$HH" -gt 32 ] && { BA=$A; BEST="$X $Y $WW $HH"; }
  done
  if [ "$BEST" != "$LAST" ]; then echo "$BEST" > "$OUT.tmp" && mv "$OUT.tmp" "$OUT"; LAST="$BEST"; fi
  sleep 1
done
