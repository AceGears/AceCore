#!/bin/bash
# Runs the handheld cores through AceArcade's frontend on Linux (host builds from build-cores.sh host) with
# AceArcade's own test ROMs (make_test_roms.py) and a homebrew DS test program from melonDS DS's test suite.
# Usage: run-tests.sh HOST_BUILD_DIR MELONDS_DS_SOURCE_DIR RESULT_FILE
set -uo pipefail
B=$(realpath "$1"); MDS=$(realpath "$2"); RES=$(realpath -m "$3")
HERE=$(cd "$(dirname "$0")" && pwd)
T=$(mktemp -d)
python3 "$HERE/../make_test_roms.py" "$T/roms" > /dev/null
mkdir -p "$T/sys" "$T/save"
run() { echo; echo "### $1"; shift; "$B/handheld_test" --system "$T/sys" --save "$T/save" "$@" 2>&1 | grep -v '^  \[log'; return "${PIPESTATUS[0]}"; }
fails=0
MG="$B/libmgba_libretro.so"; DS="$B/libmelondsds_libretro.so"
MGOPT=(--option mgba_use_bios=OFF --option mgba_sgb_borders=OFF)

run "GBA: picture, sound, CodeBreaker cheat, save state, battery save" --core "$MG" --rom "$T/roms/acetest.gba" "${MGOPT[@]}" \
  --size 240x160 --pixel 120,80=FF00FF --counter 0x02000000 --cheat "32000100+0042" --marker 02000100=42 \
  --cheat-pixel 120,80=00FF00 --state --sram 'ACE!' || fails=$((fails+1))
GSA=$(python3 "$HERE/gsa_encrypt.py" 02000100 00000042)
run "GBA: encrypted GameShark (v1) cheat $GSA" --core "$MG" --rom "$T/roms/acetest.gba" "${MGOPT[@]}" \
  --cheat "$GSA" --marker 02000100=42 --cheat-pixel 120,80=00FF00 || fails=$((fails+1))
run "Game Boy: picture, GameShark cheat, save state, battery save" --core "$MG" --rom "$T/roms/acetest.gb" "${MGOPT[@]}" \
  --size 160x144 --pixel 80,72=000000 --counter 0xC000 --cheat "014200C1" --marker C100=42 --cheat-pixel 80,72=FFFFFF \
  --state --sram 'ACE!' || fails=$((fails+1))
run "Game Boy Color: GameShark cheat, save state, battery save" --core "$MG" --rom "$T/roms/acetest.gbc" "${MGOPT[@]}" \
  --size 160x144 --counter 0xC000 --cheat "014200C1" --marker C100=42 --state --sram 'ACE!' || fails=$((fails+1))
NDS=$(ls "$MDS"/test/nds/*.nds 2>/dev/null | grep -m1 mem_regions || ls "$MDS"/test/nds/*.nds | head -1)
run "Nintendo DS: picture, sound, Action Replay cheat, save state ($(basename "$NDS"))" --core "$DS" --rom "$NDS" \
  --option melonds_console_mode=ds --option melonds_sysfile_mode=builtin --option melonds_boot_mode=direct \
  --option melonds_number_of_screen_layouts=1 --option melonds_screen_layout1=top-bottom --option melonds_screen_gap=0 \
  --option melonds_touch_mode=touch --option melonds_show_cursor=disabled --option melonds_mic_input=silence \
  --option melonds_jit_fast_memory=disabled --frames 300 --size 256x384 \
  --cheat "02300000+ACEACE42" --marker 02300000=42 --mem-word 02300000=ACEACE42 --state --dump-maps || fails=$((fails+1))
echo
if [ $fails -eq 0 ]; then echo "pass: all handheld core tests" | tee "$RES"; else echo "fail: $fails handheld test group(s) failed" | tee "$RES"; fi
[ $fails -eq 0 ]
