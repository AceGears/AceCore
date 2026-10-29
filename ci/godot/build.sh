#!/usr/bin/env bash
# Builds Godot's Android engine library (libgodot_android.so, arm64-v8a) from the same release as the
# org.godotengine:godot library AceArcade uses, with one difference: path overrides are allowed
# (disable_path_overrides=no). The published template_release disables "--main-pack", which AceArcade needs to run
# a game's .pck straight from the user's folder. Everything else is Godot's standard release template.
#   bash ci/godot/build.sh <output dir>
set -euo pipefail
OUT=$(realpath -m "${1:?output dir}")
GODOT_TAG=4.7-stable
mkdir -p "$OUT"
[ -d godot-src/.git ] || git clone -q --depth 1 -b "$GODOT_TAG" https://github.com/godotengine/godot.git godot-src
cd godot-src
python3 -m pip install --quiet --break-system-packages scons 2>/dev/null || python3 -m pip install --quiet scons
export ANDROID_HOME=${ANDROID_HOME:-$ANDROID_SDK_ROOT}
scons platform=android target=template_release arch=arm64 production=yes disable_path_overrides=no \
  -j"$(nproc)" > "$OUT/../godot-build.log" 2>&1 || { tail -n 80 "$OUT/../godot-build.log"; exit 1; }
SO=$(find bin platform/android/java/lib/libs -name 'libgodot*.so' -path '*arm64*' | head -1)
[ -n "$SO" ] || SO=$(find bin -name 'libgodot*template_release*arm64*.so' | head -1)
echo "Built: $SO"
cp "$SO" "$OUT/libgodot_android.so"
if strings "$OUT/libgodot_android.so" | grep -q "compiled without support for path overrides"; then
  echo "::error::the built library still refuses --main-pack"; exit 1
fi
echo "Godot $GODOT_TAG template_release arm64, disable_path_overrides=no" > "$OUT/GODOT_VERSION.txt"
ls -la "$OUT"
