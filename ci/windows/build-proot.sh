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

# talloc (static), cross-compiled with waf's cross answers (no target execution).
curl -fsSL "https://download.samba.org/pub/talloc/talloc-$TALLOC_VERSION.tar.gz" | tar -xz
cd "talloc-$TALLOC_VERSION"
cat > cross-answers.txt <<'A'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for -D_LARGE_FILES: OK
Checking correct behavior of strtoll: OK
Checking for working strptime: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking for HAVE_SECURE_MKSTEMP: OK
Checking getconf large file support flags work: OK
Checking for HAVE_IFACE_IFCONF: FAIL
A
mkdir -p mockbin; printf '#!/bin/sh\nexit 1\n' > mockbin/pkg-config; chmod +x mockbin/pkg-config
PATH="$PWD/mockbin:$PATH" ./configure build --prefix="$W/talloc" --disable-rpath --disable-python \
  --cross-compile --cross-answers=cross-answers.txt
mkdir -p "$W/talloc/include" "$W/talloc/lib"
"$AR" rcs "$W/talloc/lib/libtalloc.a" bin/default/talloc*.o
cp talloc.h "$W/talloc/include/"
cd "$W"

# termux/proot (Android fixes), loader unbundled.
git init -q proot && cd proot && git fetch -q --depth 1 https://github.com/termux/proot "$PROOT_REF" && git checkout -q FETCH_HEAD
cd src
# Flags through the environment: the makefile appends its own (+=), which command-line variables would replace.
export CFLAGS="-I$W/talloc/include -Werror=implicit-function-declaration" LDFLAGS="-L$W/talloc/lib"
export PROOT_UNBUNDLE_LOADER=.
make -j"$(nproc)" V=1 proot loader/loader
"$STRIP" proot loader/loader
cp proot "$OUT/libproot.so"; cp loader/loader "$OUT/libproot-loader.so"
file "$OUT"/*.so || true
echo "proot $PROOT_REF, talloc $TALLOC_VERSION" > "$OUT/PROOT_VERSION.txt"
