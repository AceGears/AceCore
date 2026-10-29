#!/usr/bin/env bash
# Signs AceArcade.exe and every unsigned .exe/.dll in the portable folder with a code-signing
# certificate (.pfx), then zips it. Runs in Git Bash on windows-latest.
#   windows-sign.sh <path/to/AceArcade folder> <out.zip>
# Secrets (optional; without them the app is unsigned and Windows SmartScreen will warn):
#   WINDOWS_CERT_PFX       base64 of the .pfx code-signing certificate
#   WINDOWS_CERT_PASSWORD  its password
# (Azure Trusted Signing is handled by the workflow before this script runs; then SIGNED_BY_AZURE=1.)
set -euo pipefail
DIR="$1"; OUT="$2"

if [ "${SIGNED_BY_AZURE:-}" = 1 ]; then
  echo "::notice title=Windows signing::Signed with Azure Trusted Signing"
elif [ -n "${WINDOWS_CERT_PFX:-}" ]; then
  ST=$(ls -d "/c/Program Files (x86)/Windows Kits/10/bin/"*/x64/signtool.exe | sort -V | tail -1)
  echo "$WINDOWS_CERT_PFX" | base64 --decode > "$RUNNER_TEMP/cert.pfx"
  n=0
  while IFS= read -r -d '' f; do
    if ! "$ST" verify //pa //q "$f" >/dev/null 2>&1; then
      "$ST" sign //f "$(cygpath -w "$RUNNER_TEMP/cert.pfx")" //p "$WINDOWS_CERT_PASSWORD" //fd SHA256 \
        //tr http://timestamp.digicert.com //td SHA256 //d AceArcade "$(cygpath -w "$f")" >/dev/null
      n=$((n+1))
    fi
  done < <(find "$DIR" -type f \( -iname '*.exe' -o -iname '*.dll' \) -print0)
  "$ST" verify //pa "$(cygpath -w "$DIR/AceArcade.exe")"
  rm -f "$RUNNER_TEMP/cert.pfx"
  echo "::notice title=Windows signing::Signed $n files with the configured certificate"
else
  echo "::warning title=Windows signing::No code-signing certificate configured. AceArcade.exe is unsigned; Windows SmartScreen will warn when it is first opened."
fi
(cd "$(dirname "$DIR")" && 7z a -tzip -mx=7 "$OUT" "$(basename "$DIR")" >/dev/null)
