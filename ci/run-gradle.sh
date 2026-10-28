#!/usr/bin/env bash
# Runs a Gradle command, keeps the full log, and turns Kotlin compiler errors and failed tests into GitHub
# annotations (readable through the Checks API even when raw logs are not).
set -o pipefail
LOG="${GRADLE_LOG:-gradle.log}"
./gradlew --no-daemon "$@" 2>&1 | tee "$LOG"
status=${PIPESTATUS[0]}
if [ "$status" -ne 0 ]; then
  n=0
  # Kotlin: "e: file:///abs/path/File.kt:12:5 Message"
  grep -E '^e: ' "$LOG" | head -60 | while IFS= read -r line; do
    loc=$(echo "$line" | sed -E 's#^e: (file://)?##' | cut -d' ' -f1)
    msg=$(echo "$line" | sed -E 's#^e: (file://)?[^ ]+ ##')
    file=$(echo "$loc" | sed -E 's#^(.*):([0-9]+):([0-9]+)$#\1#' | sed "s#^$GITHUB_WORKSPACE/##")
    ln=$(echo "$loc" | sed -E 's#^.*:([0-9]+):([0-9]+)$#\1#')
    echo "::error file=$file,line=$ln::$msg"
  done
  # Failed unit tests and other Gradle failures.
  grep -E 'FAILED$|^\s+(java|org\.junit|kotlin)\..*(Error|Exception)|What went wrong' -A3 "$LOG" | head -60 > failures.txt || true
  if [ -s failures.txt ]; then
    echo "::error title=gradle failures::$(sed 's/%/%25/g' failures.txt | awk '{printf "%s%%0A", $0}')"
  fi
  echo "::error title=gradle tail::$(tail -n 30 "$LOG" | sed 's/%/%25/g' | awk '{printf "%s%%0A", $0}')"
fi
exit "$status"
