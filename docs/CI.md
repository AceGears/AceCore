# AceArcade CI/CD: builds, caches, releases and costs

This covers `.github/workflows/*`. The repository is **private**, so every GitHub Actions minute and every stored
artifact counts against the owner account's allowance (see [Costs](#costs-and-billing)).

## Workflows at a glance

| Workflow | Starts on | Runners | What it does |
|---|---|---|---|
| **AceArcade Android** (`build.yml`) | push to `main`/`master`/`overhaul*` (app paths only), PR into `main`, tag `v*`, manual | Linux | `app`: Ruby runtime tests, compile, unit tests, APK assembly check (~7 min). Manual/tag: engines, full APK, emulator tests, release |
| **AceArcade desktop** (`desktop.yml`) | push touching desktop/detection code, tag `desktop-v*`, manual | Linux; Windows/macOS **only** on manual/tag | push: desktop unit tests (~2 min, Linux). Manual/tag: Windows and/or macOS zips (+ release) |
| **Publish Android APK from a build** (`publish.yml`) | manual | Linux | attaches an existing run's APK to a release; no rebuild |
| **CI usage report** (`usage-report.yml`) | 1st of each month, manual | Linux (~1 min) | minutes per workflow/runner, estimated cost, artifact storage |
| **Clean up CI artifacts** (`artifact-cleanup.yml`) | manual only (dry run by default) | Linux | deletes superseded artifacts to stop storage charges |

### What does *not* start a build
Documentation (`**.md`, `docs/**`), issue/PR templates, Dependabot config, the desktop app and its CI files don't start
the Android workflow; Android-only changes don't start the desktop workflow. A newer push to the same branch cancels the
superseded run (`concurrency`); tags, manual runs and releases are never cancelled.

> If branch protection is added later with required checks, require **"Build & test"** only for paths that run it, or
> GitHub will wait forever on a docs-only PR. (No branch protection exists today.)

## Manual Android build (the APK people install)

**Actions → AceArcade Android → Run workflow.** Defaults build the full APK with every engine and run the emulator tests.

Engines are expensive (RGSS ~11 min, EasyRPG ~18 min, Godot ~23 min of NDK/SCons work) and change rarely, so each engine
job resolves its binary in this order, **never reusing a binary built from different sources**:

1. **Actions cache** keyed by the engine's exact inputs:
   - RGSS: port repo + revision (`MKXPZ_PORT_REF`), NDK version, hash of `ci/patches/**`
   - EasyRPG: hash of `ci/easyrpg/**` (pins Player, buildscripts, SDL inside `build.sh`)
   - Godot: hash of `ci/godot/**` (pins the Godot tag inside `build.sh`)
   - plus `ENGINE_CACHE_VERSION` (bump it when you change the build *commands* in `build.yml`)
2. **A run you name** (`natives_run_id` / `easyrpg_run_id` / `godot_run_id`): accepted only if `ci/engine-reuse.sh`
   proves that run's commit has byte-identical engine sources and pins; its provenance is written to
   `ENGINE_PROVENANCE.txt` inside the engine artifact. The result is saved to the cache, so later runs reuse it automatically.
3. Otherwise the engine is **built**, then cached.

Each engine job prints which path it took ("reused the cached build", "reused the verified build of run …", or
"building"). The full APK job lists the engine libraries inside the APK in the run summary.

Unused cache entries are evicted by GitHub after 7 days; the next manual build then reuses the newest engine artifact
only if you pass its run id, otherwise it rebuilds once.

## Releases

| | Android | Desktop |
|---|---|---|
| Trigger | push tag `vX.Y.Z` (must equal `versionName` in `app/build.gradle.kts`) | push tag `desktop-vX.Y.Z`, or manual run with `release_tag` |
| Gate | all engines present in the APK; emulator tests passed | each platform's build verified; a failed platform is reported as missing, never silently dropped |
| Files | `AceArcade-vX.Y.Z-Android-arm64-debug.apk`, `AceArcade-vX.Y.Z-Android-checksums.sha256` | `AceArcade-vX.Y.Z-Windows-x64.zip`, `AceArcade-vX.Y.Z-macOS-arm64.zip`, `AceArcade-vX.Y.Z-desktop-checksums.sha256` |

`ci/release-publish.sh` creates the release once, replaces same-named files on re-runs (no duplicates), retries transient
failures with backoff, then verifies every file's name and size on the release and writes a summary table. A failed
publish can be re-run alone (**Re-run failed jobs**) without rebuilding.

### Signing
- **Android:** debug builds use the public development key `ci/acecore-dev.keystore` (lets new builds install over old ones).
  A Play/production release needs your own key. `app/build.gradle.kts` reads it from `keystore.properties` or the
  `ACECORE_STORE_FILE`, `ACECORE_STORE_PASSWORD`, `ACECORE_KEY_ALIAS` and `ACECORE_KEY_PASSWORD` environment variables.
  Wiring a signed `assembleRelease` into the tag build is a deliberate next step: release builds enable R8 shrinking,
  and that combination has not been tested on a device yet. JNI keep rules were added for SDL2/SDL3, EasyRPG and Godot.
- **Windows/macOS:** see `desktop/SIGNING.md` (Azure Trusted Signing or a .pfx; Apple Developer ID + notarization).
  Both are applied automatically when the secrets exist.

## Costs and billing

GitHub bills private-repository Actions by the minute, per runner type, plus artifact storage. These are the published
list prices checked on 2026-10-29 (verify at docs.github.com, "GitHub Actions billing"):

| | Linux 2-core | Windows 2-core | macOS | Artifact storage |
|---|---|---|---|---|
| Price | $0.006/min | $0.010/min | $0.062/min | $0.25/GB-month (hourly) |
| Included per month | GitHub Free: **$12** of compute and 0.5 GB of storage. GitHub Pro: **$18** of compute and 1 GB of storage. | | | |

Without a valid payment method, or with a budget that stops usage, jobs are refused once the included amount is used:
"The job was not started because recent account payments have failed or your spending limit needs to be increased".

### What happened in October 2026 (measured from this repository's run history)
- 145 runs. Raw job minutes: Linux 1,610, Windows 42, macOS 55. At list prices that is ≈ **$13.5**, more than the Free
  plan's $12. The macOS work is 3% of the minutes but 25% of that cost.
- **10.0 GB of live artifacts**, all kept for the default 90 days. More than half of it is `AceCore-web-only-debug`
  (66 × 80 MB), which every push uploaded and nothing used. At 0.5–1 GB included, that is ≈ $2.25–2.40 per month on its own.
- Together these explain the October block. The exact cause is only visible in **Settings → Billing and plans → Usage**:
  - whether the account is on GitHub Free or Pro;
  - whether a payment method failed;
  - the Actions budget and its "stop usage" setting.

### What changed to reduce it
| Change | Effect |
|---|---|
| Pushes no longer upload the 80 MB web-only APK; test reports are uploaded only on failure | ~5 GB/90 days less storage at October's push rate |
| Artifact retention set per type (7–30 days for APKs/zips, 90 only for engine builds that are reused) | storage drains in weeks, not 90 days |
| Desktop pushes run Linux unit tests only; Windows/macOS on demand | removes October's 49 macOS + 22 Windows push minutes (≈ $3.3) |
| Engines reused from cache / verified runs | saves 11–23 min per engine per manual build when unchanged |
| Docs/desktop-only changes don't start Android builds; superseded pushes are cancelled | fewer redundant `app` runs |
| Gradle cache written from development branches too (`cache-read-only` only for PRs) | dependency downloads reused on `overhaul*` |
| `timeout-minutes` on every job | a hung job can't burn the default 6 hours |

Run **CI usage report** monthly (it runs itself on the 1st) to see the trend. Costs there are estimates at list prices;
GitHub's billing page is authoritative.

### Recommended billing settings (manual, owner only)
Set these in **Settings → Billing and plans**:
1. Add a valid payment method. Without one, usage hard-stops at the included amount.
2. **Budgets and alerts → Actions:** set a monthly budget (for example $10), alerts at 75/90/100%, and choose whether
   to stop at the limit.
3. Run **Clean up CI artifacts** once with `dry_run` off. At the time of writing that frees most of the ~10 GB.
   Engine builds and the newest APKs/zips are kept.

## Security
- `permissions: contents: read` by default. Write access exists only in release/publish jobs (`contents: write`; the desktop workflow as a whole, which has no pull-request trigger), the
  emulator job (publishes its screenshots as a pre-release) and the cleanup job (`actions: write`).
- Every third-party action is pinned to a full commit SHA (the version is in a comment). Dependabot proposes updates
  monthly, grouped, minor/patch only; major versions and the Godot library are never auto-updated.
- Pull requests run with GitHub's read-only token and no secrets (`pull_request`, never `pull_request_target`).
- Secrets used (values never printed): desktop signing (`MACOS_*`, `APPLE_*`, `AZURE_*`, `WINDOWS_CERT_*`).
- Not enabled, because GitHub charges for them on private repositories: CodeQL code scanning and secret scanning
  (GitHub Advanced Security). Enable them in Settings → Code security if the plan includes them.
- **Licensing:** the APK and desktop apps bundle GPL engines (mkxp-z GPL-2.0+, EasyRPG GPL-3.0). A private repository is
  fine, but anyone you distribute a build to is entitled to its corresponding source (`NOTICE.md`). This affects how
  AceArcade can be sold.

## Troubleshooting

| Symptom | Class | What to do |
|---|---|---|
| "The job was not started because recent account payments have failed…" | Billing | Check Settings → Billing (above). Don't re-run until fixed: the jobs can't start. Finished artifacts stay downloadable. |
| `error:`/`e:` annotations in "Compile" or "Unit tests" | Source | Fix the code; the annotation names file and line. |
| Engine job says "building" unexpectedly | Cache | Expected after engine sources change or after 7 days unused. Pass the last good run id to reuse it if the sources are identical. |
| Engine reuse refused ("built from different …") | Provenance | Working as intended: that run's engine doesn't match the current sources. Let it build. |
| `gradle` download / `curl` failures | Network | Re-run failed jobs; transient. |
| Release step failed | Release | Re-run the failed job only; publishing is idempotent and doesn't rebuild. |
| Emulator test failed | Test | Check the `device-results` artifact and the "Device test results" pre-release (screenshots, logcat). Some input-injection tests are flaky on the emulator; re-run once before investigating. |
| macOS "mkxp-z launch test failed" | Engine | The macOS zip is still built, without mkxp-z (RPG Maker XP/VX/Ace is shown as unavailable on Mac). Crash backtrace is in the job annotations. |
