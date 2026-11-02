#!/bin/bash
# The Windows runtime's Linux user space (arm64): Debian (X/VNC server, PulseAudio, the native libraries Box64 maps
# Wine's calls to) + Box64 (x86_64 -> arm64) + Wine (x86_64 WoW64). Packed as one tar.xz the app unpacks on first use.
# Needs docker with arm64 emulation (docker/setup-qemu-action) and an aarch64 cross compiler.
# Usage: build-rootfs.sh OUTDIR   -> OUTDIR/windows-rootfs.tar.xz, OUTDIR/WINDOWS_VERSION.txt
set -euo pipefail
OUT=$(realpath -m "$1"); mkdir -p "$OUT"
HERE=$(cd "$(dirname "$0")" && pwd); . "$HERE/versions.env"
W=$(mktemp -d); R="$W/rootfs"; mkdir -p "$R"

echo "== Debian (arm64) with the runtime's native packages"
docker run --rm --platform linux/arm64 -v "$W:/out" "$DEBIAN_IMAGE" bash -euc '
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y -qq --no-install-recommends \
    tigervnc-standalone-server x11-utils xdotool pulseaudio \
    libx11-6 libxext6 libxrender1 libxrandr2 libxi6 libxcursor1 libxinerama1 libxcomposite1 libxfixes3 libxxf86vm1 \
    libfreetype6 libfontconfig1 fonts-dejavu-core libasound2t64 libpulse0 libgnutls30t64 libgl1 libglx-mesa0 \
    libegl1 libgles2 libvulkan1 libsdl2-2.0-0 libudev1 libdbus-1-3 libunwind8 ca-certificates procps \
    libgl1-mesa-dri libglu1-mesa > /dev/null
  apt-get clean
  rm -rf /var/lib/apt/lists/* /usr/share/man/* /usr/share/info/* /var/cache/* /var/log/*
  find /usr/share/doc -type f ! -name copyright -delete   # keep the licence file of every package
  find /usr/share/locale -mindepth 1 -maxdepth 1 ! -name "en*" -exec rm -rf {} +
  tar --numeric-owner -cpf /out/debian.tar --one-file-system \
      --exclude=./proc --exclude=./sys --exclude=./dev --exclude=./out --exclude=./tmp -C / .
'
tar -xpf "$W/debian.tar" -C "$R" 2>/dev/null || tar -xf "$W/debian.tar" -C "$R"
rm -f "$W/debian.tar"
mkdir -p "$R/tmp" "$R/dev" "$R/proc" "$R/sys" "$R/root" "$R/storage" "$R/sdcard"
DEBV=$(cat "$R/etc/debian_version")

echo "== Box64 (cross-compiled for arm64)"
git init -q "$W/box64" && git -C "$W/box64" fetch -q --depth 1 https://github.com/ptitSeb/box64 "$BOX64_REF" \
  && git -C "$W/box64" checkout -q FETCH_HEAD
# Android (apps targeting API 29+) refuses executable file-backed pages that were modified (SELinux execmod), which
# Wine needs for every relocated DLL ("failed to set ... protection ... noexec filesystem?"). Guest x86 code is only
# translated, never executed natively, so Box64 keeps PROT_EXEC in its own records and maps those pages without it.
git -C "$W/box64" apply "$HERE/box64-android-noexec.patch"
cmake -S "$W/box64" -B "$W/box64/build" -DCMAKE_BUILD_TYPE=RelWithDebInfo -DARM_DYNAREC=ON -DBAD_SIGNAL=ON \
  -DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc -DCMAKE_ASM_COMPILER=aarch64-linux-gnu-gcc -DNOGIT=ON > /dev/null
cmake --build "$W/box64/build" -j"$(nproc)" > "$W/box64-build.log" 2>&1 || { tail -50 "$W/box64-build.log"; exit 1; }
install -m 755 "$W/box64/build/box64" "$R/usr/local/bin/box64"
aarch64-linux-gnu-strip "$R/usr/local/bin/box64"
mkdir -p "$R/usr/lib/box64-x86_64-linux-gnu"
cp -a "$W/box64/x64lib/." "$R/usr/lib/box64-x86_64-linux-gnu/"
install -m 644 "$W/box64/system/box64.box64rc" "$R/etc/box64.box64rc"

echo "== Wine $WINE_VERSION (x86_64 WoW64)"
curl -fsSL -o "$W/wine.tar.xz" "https://github.com/Kron4ek/Wine-Builds/releases/download/$WINE_VERSION/$WINE_ASSET"
sha256sum "$W/wine.tar.xz" | tee "$OUT/wine.sha256"
if [ -f "$HERE/wine.sha256" ]; then
  [ "$(cut -d' ' -f1 "$HERE/wine.sha256")" = "$(cut -d' ' -f1 "$OUT/wine.sha256")" ] \
    || { echo "::error title=Windows runtime::Wine download doesn't match ci/windows/wine.sha256"; exit 1; }
else
  echo "::warning title=Windows runtime::no pinned Wine checksum yet (ci/windows/wine.sha256); got $(cut -d' ' -f1 "$OUT/wine.sha256")"
fi
mkdir -p "$W/wine" && tar -xJf "$W/wine.tar.xz" -C "$W/wine" --strip-components=1
rm -rf "$W/wine/share/man" "$W/wine/share/applications" "$W/wine/include"
find "$W/wine/lib" -name '*.a' -delete
mkdir -p "$R/opt" && mv "$W/wine" "$R/opt/wine"

echo "== AceArcade scripts"
cp -a "$HERE/rootfs-overlay/." "$R/"
chmod 755 "$R/opt/acearcade/"*.sh
printf 'Debian %s\nBox64 %s\nWine %s (%s)\n' "$DEBV" "$BOX64_REF" "$WINE_VERSION" "$WINE_ASSET" > "$R/opt/acearcade/VERSION"
cp "$R/opt/acearcade/VERSION" "$OUT/WINDOWS_VERSION.txt"
du -sh "$R" | tee -a "$OUT/WINDOWS_VERSION.txt"

echo "== Pack"
tar --numeric-owner -C "$R" -cf - . | xz -T0 -6 > "$OUT/windows-rootfs.tar.xz"
ls -la "$OUT"
