#!/usr/bin/env bash
# Creates or updates a GitHub Release and attaches files - idempotent and safe to re-run:
#   * the release is created once (or reused if it exists); files with the same name are replaced (--clobber)
#   * transient API/network failures are retried with backoff; nothing is rebuilt
#   * afterwards every file is checked on the release (name + size) and listed in the job summary
#   ci/release-publish.sh <tag> <title> <file>... [env PRERELEASE=1, NOTES="..."]
set -uo pipefail
TAG="$1"; TITLE="$2"; shift 2
FILES=("$@")
REPO="${GITHUB_REPOSITORY:?}"
[ ${#FILES[@]} -gt 0 ] || { echo "::error title=release::no files to publish"; exit 1; }
for f in "${FILES[@]}"; do [ -s "$f" ] || { echo "::error title=release::missing or empty file: $f"; exit 1; }; done

retry() { # retry <what> <command...>: 4 attempts, 10/20/40 s backoff
  local what="$1"; shift; local n=1 out
  while true; do
    out=$("$@" 2>&1) && { echo "$out"; return 0; }
    [ $n -ge 4 ] && { echo "::error title=release: $what failed::$(echo "$out" | tail -c 600 | tr '\n' ' ')"; return 1; }
    echo "::warning title=release: $what (attempt $n)::$(echo "$out" | tail -c 300 | tr '\n' ' ')"
    sleep $((10 * 2 ** (n - 1))); n=$((n + 1))
  done
}

if gh release view "$TAG" --repo "$REPO" > /dev/null 2>&1; then
  echo "Release $TAG exists - updating its files"
  retry "upload" gh release upload "$TAG" "${FILES[@]}" --clobber --repo "$REPO" || exit 1
else
  args=(--repo "$REPO" --title "$TITLE" --target "${GITHUB_SHA:?}")
  [ "${PRERELEASE:-0}" = 1 ] && args+=(--prerelease)
  if [ -n "${NOTES:-}" ]; then args+=(--notes "$NOTES"); else args+=(--generate-notes); fi
  # A concurrent run may have created it meanwhile: fall back to uploading.
  retry "create" gh release create "$TAG" "${FILES[@]}" "${args[@]}" \
    || retry "upload" gh release upload "$TAG" "${FILES[@]}" --clobber --repo "$REPO" || exit 1
fi

# Verify: every file is attached with the right size.
assets=$(retry "verify" gh release view "$TAG" --repo "$REPO" --json assets,url -q '.url, (.assets[] | "\(.name)\t\(.size)")') || exit 1
url=$(echo "$assets" | head -1)
bad=0
{ echo "### Release $TAG"; echo; echo "$url"; echo; echo "| File | Size | SHA-256 |"; echo "|---|---|---|"; } >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
for f in "${FILES[@]}"; do
  n=$(basename "$f"); want=$(stat -c %s "$f" 2>/dev/null || stat -f %z "$f")
  got=$(echo "$assets" | awk -F'\t' -v n="$n" '$1==n {print $2}')
  if [ "$got" = "$want" ]; then echo "| $n | $want | \`$(sha256sum "$f" 2>/dev/null | cut -c1-16 || shasum -a 256 "$f" | cut -c1-16)…\` |" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
  else echo "::error title=release verification::$n: release has '${got:-nothing}', expected $want bytes"; bad=1; fi
done
[ $bad -eq 0 ] && echo "::notice title=Release $TAG::${#FILES[@]} file(s) verified at $url"
exit $bad
