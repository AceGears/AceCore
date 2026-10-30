#!/usr/bin/env bash
# Reuse a previously built game engine instead of recompiling it - only when it is provably the same build.
#   ci/engine-reuse.sh <engine> <artifact-name> <out-dir> <run-id|''> <source-path>...
# env: ENGINE_PINS="VAR1 VAR2"  workflow env pins that define the engine build (compared in build.yml at both commits)
# Order:
#   1. <out-dir> already filled (Actions cache hit for the current sources)                     -> reuse
#   2. <run-id> given: that run's commit must have byte-identical <source-path>s and pins,
#      and its artifact must download                                                        -> reuse (provenance noted)
#   3. otherwise                                                                               -> build (ok=false)
# Writes ok=true|false and how=... to $GITHUB_OUTPUT, and ENGINE_PROVENANCE.txt into <out-dir> on reuse.
set -uo pipefail
ENGINE="$1"; ART="$2"; OUT="$3"; RUN="${4:-}"; shift 4
SOURCES=("$@")
out() { echo "ok=$1" >> "$GITHUB_OUTPUT"; echo "how=$2" >> "$GITHUB_OUTPUT"; echo "::notice title=$ENGINE engine::$2"; exit 0; }

if [ -n "$(ls -A "$OUT" 2>/dev/null)" ]; then out true "unchanged sources - reused the cached build"; fi
[ -n "$RUN" ] || out false "no cached build for these sources - building"

SHA=$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$RUN" -q .head_sha 2>/dev/null)
[ -n "$SHA" ] || out false "run $RUN not found - building"
git fetch -q --depth=1 origin "$SHA" 2>/dev/null || true
git cat-file -e "$SHA^{commit}" 2>/dev/null || out false "commit ${SHA:0:7} of run $RUN unavailable - building"
for p in "${SOURCES[@]}"; do
  if ! git diff --quiet "$SHA" HEAD -- "$p"; then out false "run $RUN was built from different $p (${SHA:0:7}) - building"; fi
done
for v in ${ENGINE_PINS:-}; do
  old=$(git show "$SHA:.github/workflows/build.yml" 2>/dev/null | grep -m1 "^  $v:" | tr -d ' ')
  new=$(grep -m1 "^  $v:" .github/workflows/build.yml | tr -d ' ')
  [ "$old" = "$new" ] || out false "run $RUN used a different $v ($old vs $new) - building"
done
mkdir -p "$OUT"
gh run download "$RUN" --repo "$GITHUB_REPOSITORY" -n "$ART" -D "$OUT" > /dev/null 2>&1 \
  || out false "artifact $ART of run $RUN is not downloadable (expired?) - building"
[ -n "$(ls -A "$OUT")" ] || out false "artifact of run $RUN is empty - building"
echo "reused from run $RUN (commit $SHA); identical sources: ${SOURCES[*]}; pins: ${ENGINE_PINS:-none}" > "$OUT/ENGINE_PROVENANCE.txt"
out true "reused the verified build of run $RUN (same sources as ${SHA:0:7})"
