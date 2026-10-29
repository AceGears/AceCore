#!/usr/bin/env bash
# Builds mkxp-z (RPG Maker XP/VX/VX Ace engine, GPL-2.0+) for macOS arm64 from a pinned upstream commit.
#   build-mkxpz-macos.sh <out dir>
# AceArcade-specific build changes (nothing else is modified):
#   -Dworkdir_current=true  the engine runs the game in its working directory (AceArcade starts it in the game's folder),
#                           exactly like the game's own Game.exe on Windows, which reads Game.ini/mkxp.json next to it.
#   ACEARCADE_EXEC_NAME     environment variable read as mkxp-z's "execName" before the ini is read, so renamed games
#                           (Flux.exe + Flux.ini) start without renaming the game's files (same patch as on Android).
#   Downloads check removed upstream refuses to start when its working directory is under ~/Downloads (a guard for
#                           games bundled inside the engine's .app). With AceArcade the working directory is the
#                           user's game folder, which is often in Downloads.
set -euo pipefail
OUT="$1"
COMMIT="${MKXPZ_COMMIT:?}"
WORK="$RUNNER_TEMP/mkxpz-src"
brew install -q bison ccache cmake diffutils make meson ninja ruby >/dev/null
export PATH="$(brew --prefix bison)/bin:$PATH"
git init -q "$WORK"; cd "$WORK"
git remote add origin https://github.com/mkxp-z/mkxp-z
git fetch -q --depth 1 origin "$COMMIT"; git checkout -q FETCH_HEAD
python3 - <<'PY'
p = "src/main.cpp"; s = open(p).read()
old = "if (mkxp_fs::getCurrentDirectory().find(downloadsPath) == 0) {"
assert s.count(old) == 1, "Downloads check not found - review the patch for this mkxp-z version"
open(p, "w").write(s.replace(old, "if (false) { /* AceArcade: game folders may live in Downloads */ (void)downloadsPath;"))
p = "src/config.cpp"; s = open(p).read()
old = "    SET_STRINGOPT(execName, execName);\n"
assert s.count(old) == 1, "execName option not found - review the patch for this mkxp-z version"
open(p, "w").write(s.replace(old, old + """    // AceArcade: renamed games (Flux.exe + Flux.ini) - the launcher names the settings file before it is read.
    if (const char *aceExec = getenv("ACEARCADE_EXEC_NAME")) { if (aceExec[0]) execName = aceExec; }
"""))
PY
meson subprojects download >/dev/null
meson setup --prefix "$OUT" --cross-file macos/meson-arm64.txt --buildtype release -Dstrip=true -Dworkdir_current=true build
ninja -C build
meson install -C build --no-rebuild --tags mkxp-z
cd "$OUT"
ls -la
EXE=$(ls mkxp-z.* | grep -v '\.txt$' | head -1)
file "$EXE"
echo "--- linked libraries"
otool -L "$EXE" | grep -v '/System/\|/usr/lib/' || true
printf 'mkxp-z %s (macOS arm64), built with -Dworkdir_current=true; source: https://github.com/mkxp-z/mkxp-z/tree/%s\n' "$COMMIT" "$COMMIT" > ACEARCADE-BUILD.txt
