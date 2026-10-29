#!/usr/bin/env bash
# Signs (and, with Apple credentials, notarizes) AceArcade.app, then zips it.
#   macos-sign.sh <path/to/AceArcade.app> <out.zip>
# Secrets (all optional; without them the app is ad-hoc signed and macOS will warn on first launch):
#   MACOS_CERT_P12       base64 of a "Developer ID Application" certificate exported as .p12
#   MACOS_CERT_PASSWORD  password of that .p12
#   APPLE_ID, APPLE_TEAM_ID, APPLE_APP_PASSWORD   for notarization (app-specific password from appleid.apple.com)
set -euo pipefail
APP="$1"; OUT="$2"
HERE="$(cd "$(dirname "$0")" && pwd)"
ENT="$HERE/macos-entitlements.plist"

if [ -z "${MACOS_CERT_P12:-}" ]; then
  echo "::warning title=macOS signing::No Developer ID certificate configured (MACOS_CERT_P12). The app is ad-hoc signed; macOS will warn when it is first opened."
  codesign --force --deep --sign - "$APP"
  ditto -c -k --sequesterRsrc --keepParent "$APP" "$OUT"
  exit 0
fi

KC="$RUNNER_TEMP/signing.keychain-db"; KCPW="$(uuidgen)"
echo "$MACOS_CERT_P12" | base64 --decode > "$RUNNER_TEMP/cert.p12"
security create-keychain -p "$KCPW" "$KC"
security set-keychain-settings -lut 21600 "$KC"
security unlock-keychain -p "$KCPW" "$KC"
security import "$RUNNER_TEMP/cert.p12" -k "$KC" -P "$MACOS_CERT_PASSWORD" -T /usr/bin/codesign
security set-key-partition-list -S apple-tool:,apple: -s -k "$KCPW" "$KC" >/dev/null
security list-keychains -d user -s "$KC" $(security list-keychains -d user | tr -d '"')
ID=$(security find-identity -v -p codesigning "$KC" | grep -m1 'Developer ID Application' | sed 's/.*"\(.*\)"/\1/')
[ -n "$ID" ] || { echo "::error title=macOS signing::No 'Developer ID Application' identity in MACOS_CERT_P12"; exit 1; }
echo "::notice title=macOS signing::Signing with $ID"

sign() { codesign --force --timestamp --options runtime --entitlements "$ENT" --sign "$ID" "$1"; }

# Inside-out: every Mach-O file (deepest first), then nested bundles, then the app itself.
find "$APP" -type f -print0 | while IFS= read -r -d '' f; do
  if file -b "$f" | grep -q 'Mach-O'; then echo "$(echo "$f" | tr -cd '/' | wc -c) $f"; fi
done | sort -rn | cut -d' ' -f2- | while IFS= read -r f; do sign "$f"; done
find "$APP/Contents" -depth \( -name '*.app' -o -name '*.framework' -o -name '*.bundle' \) -type d | while IFS= read -r b; do sign "$b"; done
sign "$APP"
codesign --verify --deep --strict --verbose=2 "$APP"

if [ -n "${APPLE_ID:-}" ] && [ -n "${APPLE_TEAM_ID:-}" ] && [ -n "${APPLE_APP_PASSWORD:-}" ]; then
  ditto -c -k --sequesterRsrc --keepParent "$APP" "$RUNNER_TEMP/notarize.zip"
  xcrun notarytool submit "$RUNNER_TEMP/notarize.zip" --apple-id "$APPLE_ID" --team-id "$APPLE_TEAM_ID" \
    --password "$APPLE_APP_PASSWORD" --wait --timeout 60m --output-format json | tee "$RUNNER_TEMP/notary.json"
  if ! grep -q '"status" *: *"Accepted"' "$RUNNER_TEMP/notary.json"; then
    SID=$(sed -n 's/.*"id" *: *"\([^"]*\)".*/\1/p' "$RUNNER_TEMP/notary.json" | head -1)
    xcrun notarytool log "$SID" --apple-id "$APPLE_ID" --team-id "$APPLE_TEAM_ID" --password "$APPLE_APP_PASSWORD" | head -80 || true
    echo "::error title=macOS notarization::Apple did not accept the app"; exit 1
  fi
  xcrun stapler staple "$APP"
  spctl --assess --type execute --verbose=2 "$APP"
  echo "::notice title=macOS signing::Signed, notarized and stapled - Gatekeeper accepts it"
else
  echo "::warning title=macOS notarization::Signed but not notarized (APPLE_ID / APPLE_TEAM_ID / APPLE_APP_PASSWORD missing); macOS will still warn."
fi
ditto -c -k --sequesterRsrc --keepParent "$APP" "$OUT"
