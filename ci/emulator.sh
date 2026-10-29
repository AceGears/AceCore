#!/usr/bin/env bash
# Starts an Android emulator on the CI runner (KVM), with its log kept for diagnosis, then runs device-tests.sh.
set -u
API=${EMU_API:-30}
IMG="system-images;android-$API;google_apis;x86_64"
SDK="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
SDKM=$(ls "$SDK"/cmdline-tools/*/bin/sdkmanager | head -1)
AVDM=$(ls "$SDK"/cmdline-tools/*/bin/avdmanager | head -1)
yes | "$SDKM" --licenses > /dev/null || true
timeout 900 "$SDKM" --install "platform-tools" "emulator" "$IMG" > sdk-install.log 2>&1 || { echo "::error title=sdkmanager::$(tail -n 20 sdk-install.log | awk '{printf "%s%%0A", $0}')"; exit 1; }
# One AVD folder for avdmanager and the emulator (newer tools otherwise disagree about ~/.android vs ~/.config).
export ANDROID_AVD_HOME="$HOME/.android/avd"
mkdir -p "$ANDROID_AVD_HOME"
echo no | "$AVDM" create avd -n ci -k "$IMG" -d pixel_5 --force
printf 'hw.ramSize=3072\ndisk.dataPartition.size=4096M\nhw.gpu.enabled=yes\nhw.gpu.mode=swiftshader_indirect\n' >> "$ANDROID_AVD_HOME/ci.avd/config.ini"
export PATH="$SDK/emulator:$SDK/platform-tools:$PATH"
emulator -accel-check | tee emu-accel.txt
nohup emulator -avd ci -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -camera-back none -camera-front none > emulator.log 2>&1 &
echo "::group::emulator start"
if ! timeout 600 adb wait-for-device; then
  echo "::error title=emulator did not appear::$(tail -n 60 emulator.log | sed 's/%/%25/g' | awk '{printf "%s%%0A", $0}')"
  exit 1
fi
echo "::endgroup::"
for i in $(seq 1 120); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 5
done
if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; then
  echo "::error title=emulator did not boot::$(tail -n 40 emulator.log | sed 's/%/%25/g' | awk '{printf "%s%%0A", $0}')"
  exit 1
fi
echo "::notice title=emulator booted::after about $((i*5)) s, API $API"
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell input keyevent 82
bash ci/device-tests.sh
