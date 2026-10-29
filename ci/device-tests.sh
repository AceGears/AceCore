#!/usr/bin/env bash
# Runs AceCore's instrumented tests on the CI emulator and collects screenshots, metrics and logs.
set -u
PKG=com.acecore.app.debug
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner
mkdir -p device-results
# Each test is limited to 15 minutes so one hung game can't use up the job; results are still collected.
adb shell getprop ro.product.cpu.abilist | tee device-results/abilist.txt
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Direct game access (what the user grants in Settings > All files access).
adb shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow || adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow
adb logcat -c
status=0
timeout 900 adb shell am instrument -w -r -e class com.acecore.app.RgssDirectLaunchTest $RUNNER | tee device-results/rgss-test.txt
grep -q "^OK (" device-results/rgss-test.txt || status=1
grep -q "AssumptionViolatedException\|no RGSS native" device-results/rgss-test.txt && { echo "::error title=rgss test skipped::the RGSS natives were not loadable"; status=1; }
adb shell am force-stop $PKG; adb shell input keyevent KEYCODE_HOME; sleep 2
timeout 900 adb shell am instrument -w -r -e class com.acecore.app.UiTourTest $RUNNER | tee device-results/ui-test.txt
grep -q "^OK (" device-results/ui-test.txt || status=1
adb shell am force-stop $PKG; sleep 1
timeout 900 adb shell am instrument -w -r -e class com.acecore.app.GodotLaunchTest $RUNNER | tee device-results/godot-test.txt
grep -q "^OK (" device-results/godot-test.txt || status=1
adb shell am force-stop $PKG; sleep 1
timeout 900 adb shell am instrument -w -r -e class com.acecore.app.EasyRpgLaunchTest $RUNNER | tee device-results/easyrpg-test.txt
grep -q "^OK (" device-results/easyrpg-test.txt || status=1
adb pull /sdcard/Android/data/$PKG/files/testresults device-results/ || true
adb logcat -d > device-results/logcat.txt || true
grep -iE "AceCore|AceArcade|godot|EasyRPG|SDL|mkxp|libc|DEBUG|FATAL|AndroidRuntime|native_bridge|houdini|ndk_translation" device-results/logcat.txt | tail -400 > device-results/logcat-filtered.txt || true
for f in device-results/rgss-test.txt device-results/ui-test.txt device-results/godot-test.txt device-results/easyrpg-test.txt; do
  echo "::notice title=$(basename $f)::$(grep -E 'OK \(|FAILURES|Tests run|INSTRUMENTATION_STATUS: stack=|AssertionError|Exception' $f | head -20 | sed 's/%/%25/g' | awk '{printf "%s%%0A", $0}')"
done
[ -f device-results/testresults/metrics.txt ] && echo "::notice title=metrics::$(cat device-results/testresults/metrics.txt | awk '{printf "%s%%0A", $0}')"
for f in device-results/testresults/godot-godot.log.txt device-results/testresults/easyrpg-easyrpg.log.txt device-results/testresults/godot-timeline.txt device-results/testresults/easyrpg-timeline.txt; do
  [ -f "$f" ] && echo "::notice title=$(basename $f)::$(tail -n 40 "$f" | cut -c1-200 | sed 's/%/%25/g' | awk '{printf "%s%%0A", $0}')"
done
note() { # title file
  [ -s "$2" ] && echo "::notice title=$1::$(cut -c1-230 "$2" | sed 's/%/%25/g' | awk '{printf "%s%%0A", $0}')"
}
grep -E "AceArcadeInput" device-results/logcat.txt | head -n 30 > device-results/input.txt || true
note "on-screen input (first 30)" device-results/input.txt
grep -E "Godot|godot" device-results/logcat.txt | grep -vE "^\S+ \S+ +[0-9]+ +[0-9]+ [A-Z] [A-Za-z]+ *: +at |chatty|iorapd|ArtManager" | head -n 45 > device-results/godot-start.txt || true
note "godot start" device-results/godot-start.txt
grep -E "EasyRPG|easyrpg|SDL" device-results/logcat.txt | grep -vE ": +at |chatty|iorapd|ArtManager" | tail -n 45 > device-results/easyrpg-start.txt || true
note "easyrpg start" device-results/easyrpg-start.txt
grep -E "AceCoreTest" device-results/logcat.txt | tail -n 30 > device-results/metrics-log.txt || true
note "test metrics" device-results/metrics-log.txt
grep -iE "godot|EasyRPG" device-results/logcat.txt | grep -ivE "chatty|ActivityTaskManager|ndk_translation" | tail -n 50 > device-results/engines-logcat.txt || true
echo "::notice title=engine logcat::$(cut -c1-220 device-results/engines-logcat.txt | sed 's/%/%25/g' | awk '{printf "%s%%0A", $0}')"
echo "::notice title=abilist::$(cat device-results/abilist.txt)"
if [ $status -ne 0 ]; then
  echo "::error title=logcat (filtered)::$(tail -n 60 device-results/logcat-filtered.txt | sed 's/%/%25/g' | awk '{printf "%s%%0A", $0}')"
fi
exit $status
