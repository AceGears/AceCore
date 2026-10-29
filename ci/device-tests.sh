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
note() { # title file  (GitHub shows at most 10 notices per step, so results are grouped)
  [ -s "$2" ] && echo "::notice title=$1::$(cut -c1-230 "$2" | sed 's/%/%25/g' | awk '{printf "%s%%0A", $0}')"
}
{
  for f in device-results/rgss-test.txt device-results/ui-test.txt device-results/godot-test.txt device-results/easyrpg-test.txt; do
    echo "== $(basename $f): $(grep -E 'OK \(|Tests run' $f | head -2 | tr '\n' ' ')"
    grep -E 'AssertionError|Exception' $f | head -2
  done
} > device-results/summary.txt
note "test results" device-results/summary.txt
{ grep -E "AceCoreTest" device-results/logcat.txt | tail -n 25; cat device-results/testresults/metrics.txt 2>/dev/null; } > device-results/metrics-log.txt
note "test metrics" device-results/metrics-log.txt
grep -E "AceArcadeInput" device-results/logcat.txt | head -n 20 > device-results/input.txt || true
[ -s device-results/input.txt ] || echo "no on-screen button presses were logged" > device-results/input.txt
note "on-screen input" device-results/input.txt
{ grep -E " godot  *:| Godot  *:|GodotFragment: [^ \t]" device-results/logcat.txt | grep -vE ": +at " | head -n 40; } > device-results/godot-start.txt
note "godot log" device-results/godot-start.txt
{ tail -n 15 device-results/testresults/godot-godot.log.txt 2>/dev/null; tail -n 30 device-results/testresults/easyrpg-easyrpg.log.txt 2>/dev/null || echo "no EasyRPG log file"; grep -E "EasyRPG Player" device-results/logcat.txt | tail -n 10; } > device-results/easyrpg.txt
note "easyrpg log" device-results/easyrpg.txt
[ -f device-results/testresults/rgss_walk_small.jpg ] && echo "::notice title=rgss screenshot (base64 jpeg)::$(base64 -w0 device-results/testresults/rgss_walk_small.jpg)"
echo "::notice title=abilist::$(cat device-results/abilist.txt)"
if [ $status -ne 0 ]; then
  echo "::error title=logcat (filtered)::$(tail -n 60 device-results/logcat-filtered.txt | sed 's/%/%25/g' | awk '{printf "%s%%0A", $0}')"
fi
exit $status
