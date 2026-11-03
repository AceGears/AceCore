#!/bin/bash
# Builds the Nintendo handheld emulator cores (libretro builds of mGBA and melonDS DS) from the pinned sources in
# versions.env, plus AceArcade's own frontend library (ci/handheld/frontend), for one target:
#   android  arm64-v8a shared libraries for the APK (NDK, API 26, static libc++, 16 KB pages), named lib*.so so
#            Android extracts them into the app's native library folder.
#   host     the same for x86_64 Linux, used by the CI core tests (ci/handheld/test).
# Usage: build-cores.sh android|host OUT_DIR
set -euo pipefail
TARGET="$1"; OUT=$(realpath -m "$2")
HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck disable=SC1091
. "$HERE/versions.env"
W=${HANDHELD_WORK:-$PWD/handheld-build}
mkdir -p "$W" "$OUT"

fetch() { # repo ref dir
  if [ ! -d "$3/.git" ]; then git init -q "$3"; git -C "$3" remote add origin "$1"; fi
  git -C "$3" fetch -q --depth 1 origin "$2"
  git -C "$3" checkout -q --force FETCH_HEAD
  echo "$3 at $(git -C "$3" rev-parse HEAD)"
}
fetch "$MGBA_REPO" "$MGBA_REF" "$W/mgba"
fetch "$MELONDSDS_REPO" "$MELONDSDS_REF" "$W/melonds-ds"

COMMON=(-G Ninja -DCMAKE_BUILD_TYPE=Release)
if [ "$TARGET" = android ]; then
  : "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}"
  COMMON+=(-DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a
           -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static
           -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON
           -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384 -Wl,--build-id=sha1"
           -DCMAKE_MODULE_LINKER_FLAGS="-Wl,-z,max-page-size=16384 -Wl,--build-id=sha1")
  SUFFIX=""
else
  SUFFIX=""
fi

echo "== mGBA (libretro)"
cmake -S "$W/mgba" -B "$W/mgba-$TARGET" "${COMMON[@]}" -DBUILD_LIBRETRO=ON -DBUILD_QT=OFF -DBUILD_SDL=OFF \
  -DBUILD_SHARED=OFF -DBUILD_STATIC=OFF -DBUILD_GL=OFF -DBUILD_GLES2=OFF -DBUILD_GLES3=OFF -DUSE_DEBUGGERS=OFF \
  -DUSE_EDITLINE=OFF -DUSE_FFMPEG=OFF -DUSE_ZLIB=ON -DUSE_PNG=OFF -DUSE_LIBZIP=OFF -DUSE_MINIZIP=OFF -DUSE_SQLITE3=OFF \
  -DUSE_ELF=OFF -DUSE_LZMA=OFF -DUSE_DISCORD_RPC=OFF -DUSE_GDB_STUB=OFF -DUSE_LUA=OFF -DUSE_JSON_C=OFF \
  -DENABLE_SCRIPTING=OFF > "$W/mgba-$TARGET.cmake.log" 2>&1 || { tail -40 "$W/mgba-$TARGET.cmake.log"; exit 1; }
ninja -C "$W/mgba-$TARGET" mgba_libretro > "$W/mgba-$TARGET.build.log" 2>&1 || { tail -60 "$W/mgba-$TARGET.build.log"; exit 1; }
cp "$W/mgba-$TARGET/mgba_libretro.so" "$OUT/libmgba_libretro.so"

echo "== melonDS DS (libretro; software renderer, no OpenGL)"
cmake -S "$W/melonds-ds" -B "$W/melonds-$TARGET" "${COMMON[@]}" -DENABLE_OPENGL=OFF -DBUILD_TESTING=OFF \
  -DMELONDS_REPOSITORY_TAG="$MELONDS_REF" -DTRACY_ENABLE=OFF > "$W/melonds-$TARGET.cmake.log" 2>&1 \
  || { tail -40 "$W/melonds-$TARGET.cmake.log"; exit 1; }
ninja -C "$W/melonds-$TARGET" > "$W/melonds-$TARGET.build.log" 2>&1 || { tail -60 "$W/melonds-$TARGET.build.log"; exit 1; }
SO=$(find "$W/melonds-$TARGET" -name 'melondsds_libretro*.so' | head -1)
[ -n "$SO" ] || { echo "melonDS DS library not found"; exit 1; }
cp "$SO" "$OUT/libmelondsds_libretro.so"

echo "== AceArcade frontend"
cmake -S "$HERE/frontend" -B "$W/frontend-$TARGET" "${COMMON[@]}" -DLIBRETRO_INCLUDE="$W/mgba/src/platform/libretro" \
  > "$W/frontend-$TARGET.cmake.log" 2>&1 || { tail -40 "$W/frontend-$TARGET.cmake.log"; exit 1; }
ninja -C "$W/frontend-$TARGET" > "$W/frontend-$TARGET.build.log" 2>&1 || { tail -60 "$W/frontend-$TARGET.build.log"; exit 1; }
if [ "$TARGET" = android ]; then
  cp "$W/frontend-$TARGET/libacehandheld.so" "$OUT/"
  STRIP="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
  for f in "$OUT"/*.so; do "$STRIP" --strip-unneeded "$f"; done
else
  cp "$W/frontend-$TARGET/handheld_test" "$OUT/"
fi
{
  echo "mGBA $(git -C "$W/mgba" rev-parse HEAD) (0.10.5, MPL-2.0)"
  echo "melonDS DS $(git -C "$W/melonds-ds" rev-parse HEAD) (1.4.0, GPL-3.0), melonDS $MELONDS_REF"
} > "$OUT/HANDHELD_VERSION.txt"
ls -la "$OUT"
