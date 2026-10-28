#!/usr/bin/env bash
# Runs AceCore's instrumented tests on the CI emulator and collects screenshots, metrics and logs.
set -u
PKG=com.acecore.app.debug
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner
mkdir -p device-results
adb shell getprop ro.product.cpu.abilist | tee device-results/abilist.txt
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Direct game access (what the user grants in Settings > All files access).
adb shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow || adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow
adb logcat -c
status=0
adb shell am instrument -w -r -e class com.acecore.app.RgssDirectLaunchTest $RUNNER | tee device-results/rgss-test.txt
grep -q "^OK (" device-results/rgss-test.txt || status=1
grep -q "AssumptionViolatedException\|no RGSS native" device-results/rgss-test.txt && { echo "::error title=rgss test skipped::the RGSS natives were not loadable"; status=1; }
adb shell am instrument -w -r -e class com.acecore.app.UiTourTest $RUNNER | tee device-results/ui-test.txt
grep -q "^OK (" device-results/ui-test.txt || status=1
adb pull /sdcard/Android/data/$PKG/files/testresults device-results/ || true
adb logcat -d > device-results/logcat.txt || true
grep -iE "AceCore|SDL|mkxp|libc|DEBUG|FATAL|AndroidRuntime|native_bridge|houdini|ndk_translation" device-results/logcat.txt | tail -400 > device-results/logcat-filtered.txt || true
for f in device-results/rgss-test.txt device-results/ui-test.txt; do
  echo "::notice title=$(basename $f)::$(grep -E 'OK \(|FAILURES|Tests run|INSTRUMENTATION_STATUS: stack=|AssertionError|Exception' $f | head -20 | sed 's/%/%25/g' | awk '{printf "%s%%0A", $0}')"
done
[ -f device-results/testresults/metrics.txt ] && echo "::notice title=metrics::$(cat device-results/testresults/metrics.txt | awk '{printf "%s%%0A", $0}')"
echo "::notice title=abilist::$(cat device-results/abilist.txt)"
if [ $status -ne 0 ]; then
  echo "::error title=logcat (filtered)::$(tail -n 60 device-results/logcat-filtered.txt | sed 's/%/%25/g' | awk '{printf "%s%%0A", $0}')"
fi
exit $status
