#!/bin/bash
# PRoot for Android (arm64), built with the NDK: the Windows runtime's Linux user space runs under it, without root.
# Android (targetSdk >= 29) only lets an app execute files from its native library folder, so proot and its loader
# are shipped as libproot.so / libproot-loader.so (jniLibs); the loader path is given at run time (PROOT_LOADER).
# Usage: build-proot.sh OUTDIR
set -euo pipefail
OUT=$(realpath -m "$1"); mkdir -p "$OUT"
HERE=$(cd "$(dirname "$0")" && pwd); . "$HERE/versions.env"
NDK=${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:?NDK not found}}
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
API=26
export CC="$TC/aarch64-linux-android$API-clang" AR="$TC/llvm-ar" RANLIB="$TC/llvm-ranlib" STRIP="$TC/llvm-strip"
export OBJCOPY="$TC/llvm-objcopy" OBJDUMP="$TC/llvm-objdump"
W=$(mktemp -d); cd "$W"

# talloc (static): talloc.c compiled directly with a small replace.h (ci/windows/talloc); its waf build can't
# cross-compile without running target programs.
if ! curl -fsSL "https://download.samba.org/pub/talloc/talloc-$TALLOC_VERSION.tar.gz" | tar -xz; then
  git clone -q --depth 1 --filter=blob:none --sparse --branch "talloc-$TALLOC_VERSION" https://github.com/samba-team/samba samba
  git -C samba sparse-checkout set lib/talloc
  mkdir -p "talloc-$TALLOC_VERSION" && cp samba/lib/talloc/talloc.[ch] "talloc-$TALLOC_VERSION/"
fi
IFS=. read -r TMAJ TMIN TREL <<< "$TALLOC_VERSION"
mkdir -p "$W/talloc/include" "$W/talloc/lib"
"$CC" -c -O2 -fPIC -DTALLOC_BUILD_VERSION_MAJOR=$TMAJ -DTALLOC_BUILD_VERSION_MINOR=$TMIN -DTALLOC_BUILD_VERSION_RELEASE=$TREL \
  -I"$HERE/talloc" -I"talloc-$TALLOC_VERSION" "talloc-$TALLOC_VERSION/talloc.c" -o "$W/talloc.o"
"$AR" rcs "$W/talloc/lib/libtalloc.a" "$W/talloc.o"
cp "talloc-$TALLOC_VERSION/talloc.h" "$W/talloc/include/"
cd "$W"

# termux/proot (Android fixes), loader unbundled.
git init -q proot && cd proot && git fetch -q --depth 1 https://github.com/termux/proot "$PROOT_REF" && git checkout -q FETCH_HEAD
cd src
# One extension misses <string.h> (an error with current NDK compilers).
grep -q "<string.h>" extension/ashmem_memfd/ashmem_memfd.c || sed -i "1i #include <string.h>" extension/ashmem_memfd/ashmem_memfd.c
# Flags through the environment: the makefile appends its own (+=), which command-line variables would replace.
export CFLAGS="-I$W/talloc/include" LDFLAGS="-L$W/talloc/lib"
export PROOT_UNBUNDLE_LOADER=.
make -j"$(nproc)" V=1 proot loader/loader
"$STRIP" proot loader/loader
cp proot "$OUT/libproot.so"; cp loader/loader "$OUT/libproot-loader.so"
file "$OUT"/*.so || true
echo "proot $PROOT_REF, talloc $TALLOC_VERSION" > "$OUT/PROOT_VERSION.txt"
