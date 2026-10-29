#!/usr/bin/env bash
# Builds EasyRPG Player (GPL-3.0) for Android arm64-v8a as libraries AceArcade can host:
#   libSDL3.so            SDL 3.4.8 with its Java package relocated to org.libsdl3.app (the RGSS runtime already
#                         uses SDL2's org.libsdl.app; both must coexist in one APK)
#   libeasyrpg_android.so EasyRPG Player + liblcf and all codecs (static), started by
#                         org.easyrpg.player.player.EasyRpgPlayerActivity (AceArcade's host activity)
# Dependencies come from EasyRPG's own buildscripts (same versions EasyRPG ships).
#   bash ci/easyrpg/build.sh <output dir>
set -euo pipefail
OUT=$(realpath -m "${1:?output dir}")
BUILDSCRIPTS_REF=84cf2f449a0d1c09f17d2bbf3d28f4f573aa718a
PLAYER_REF=096c004def987f3545198304b1b7a8352f284a50
# bump to rebuild the player library only: 2
SDL_REF=release-3.4.8
WS=$PWD/easyrpg-build
mkdir -p "$WS" "$OUT"
cd "$WS"

fetch() { # repo dir ref
  if [ ! -d "$2/.git" ]; then git init -q "$2"; git -C "$2" remote add origin "$1"; fi
  git -C "$2" fetch -q --depth 1 origin "$3" && git -C "$2" checkout -q FETCH_HEAD
}
fetch https://github.com/EasyRPG/buildscripts.git buildscripts $BUILDSCRIPTS_REF
fetch https://github.com/EasyRPG/Player.git Player $PLAYER_REF
fetch https://github.com/libsdl-org/SDL.git SDL $SDL_REF

# ---- 1. Dependencies (arm64-v8a only)
cd "$WS/buildscripts/android"
export BUILD_LIBLCF=1
if [ ! -f arm64-v8a-toolchain/lib/liblcf.a ]; then
  [ -d android-sdk/ndk ] || ./1_download_library.sh > "$WS/download.log" 2>&1 || { tail -n 60 "$WS/download.log"; exit 1; }
  sed -i -e '/^build "ARMeabi-v7a"/d' -e '/^build "x86"/d' -e '/^build "x86_64"/d' 2_build_toolchain.sh
  ./2_build_toolchain.sh > "$WS/toolchain.log" 2>&1 || { tail -n 120 "$WS/toolchain.log"; exit 1; }
fi
PREFIX=$WS/buildscripts/android/arm64-v8a-toolchain
NDK=$(ls -d "$WS"/buildscripts/android/android-sdk/ndk/* | head -1)
TOOLCHAIN=$NDK/build/cmake/android.toolchain.cmake
echo "NDK: $NDK"

# ---- 2. SDL3 with the Java package relocated
cd "$WS/SDL"
sed -i 's#org_libsdl_app#org_libsdl3_app#g; s#"org/libsdl/app/#"org/libsdl3/app/#g' src/core/android/SDL_android.c src/hidapi/android/hid.cpp
grep -q 'org/libsdl3/app/SDLActivity' src/core/android/SDL_android.c
cmake -S . -B build -G Ninja -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=23 \
  -DCMAKE_BUILD_TYPE=Release -DSDL_SHARED=ON -DSDL_STATIC=OFF -DSDL_TEST_LIBRARY=OFF -DSDL_TESTS=OFF -DSDL_EXAMPLES=OFF \
  -DCMAKE_INSTALL_PREFIX="$PREFIX" > "$WS/sdl.log" 2>&1 || { tail -n 60 "$WS/sdl.log"; exit 1; }
cmake --build build >> "$WS/sdl.log" 2>&1 || { tail -n 80 "$WS/sdl.log"; exit 1; }
cmake --install build >> "$WS/sdl.log" 2>&1

# ---- 3. EasyRPG Player
cd "$WS/Player"
# AceArcade patch: flush every log line (the log file is how AceArcade sees that a game has loaded and why it failed;
# EasyRPG otherwise writes it only when the buffer fills or the game exits).
sed -i "s|output_time() << prefix << msg << '\\\\n';|output_time() << prefix << msg << std::endl;|" src/output.cpp
grep -q "output_time() << prefix << msg << std::endl;" src/output.cpp
cmake -S . -B build-android -G Ninja -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=23 \
  -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release -DPLAYER_GRADLE_BUILD=ON \
  -DPLAYER_ANDROID_TOOLCHAIN_PATH="$WS/buildscripts/android" -DBUILD_SHARED_LIBS=ON -DPLAYER_ENABLE_TESTS=OFF \
  -DCMAKE_FIND_ROOT_PATH="$PREFIX" -DCMAKE_PREFIX_PATH="$PREFIX" > "$WS/player.log" 2>&1 || { tail -n 80 "$WS/player.log"; exit 1; }
cmake --build build-android --target easyrpg_android >> "$WS/player.log" 2>&1 || { grep -n "error" "$WS/player.log" | head -40; tail -n 60 "$WS/player.log"; exit 1; }

# ---- 4. Collect
cp "$WS/SDL/build/libSDL3.so" "$OUT/"
find "$WS/Player/build-android" -name "*.so" -exec cp -v {} "$OUT/" \;
"$NDK"/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip --strip-unneeded "$OUT"/*.so
for f in "$OUT"/*.so; do
  echo "$(basename "$f"): $(stat -c %s "$f") bytes; needs: $("$NDK"/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf -d "$f" | awk '/NEEDED/ {print $5}' | tr -d '[]' | tr '\n' ' ')"
done
echo "EasyRPG Player $PLAYER_REF, buildscripts $BUILDSCRIPTS_REF, SDL $SDL_REF" > "$OUT/EASYRPG_VERSION.txt"
