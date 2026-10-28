# AceCore

Premium Android launcher for PC-origin indie games. **Version 0.4.0**: direct game launching (no full copies),
a launch-file browser, categories, a redesigned library and game page, real play statistics, and a cheat manager for
RPG Maker XP/VX/VX Ace and Pokémon Essentials, on top of the web runtime, Flash (Ruffle) and the RGSS runtime (mkxp-z).

**Licence: GPL-3.0-or-later** (see `LICENSE`, `NOTICE.md`). Required because AceCore integrates mkxp-z (GPL-2.0-or-later).

> **Build status:** compiled and unit-tested by GitHub Actions on every push (`.github/workflows/build.yml`), including
> Robolectric database-migration tests and Ruby tests of the RGSS runtime helper. Full APKs with the RGSS runtime and an
> emulator test run are produced by "Run workflow". Android Studio needs **JDK 21** for Gradle (pick "Use JVM 21";
> Gradle 8.14.3 does not support JDK 25). Features are marked "tested" only where they actually ran.

## Build

1. Open the `AceCore` folder in Android Studio (Narwhal / 2025.1 or newer). Let it sync.
   - If it offers to upgrade the Android Gradle Plugin to **9.x, decline for now**. AGP 9 changes the build DSL. Upgrading to a newer 8.x is fine.
   - JDK 17+ is required (Android Studio's bundled JDK works).
2. Run **app** on a device or emulator (Android 8.0+, API 26). Debug builds install as `com.acecore.app.debug`.
3. Unit tests: `./gradlew testDebugUnitTest`.
4. Release: copy `keystore.properties.example` to `keystore.properties` (git-ignored) and fill it in, or set the `ACECORE_*` environment variables. Then run `./gradlew bundleRelease` (AAB) or `assembleRelease` (APK).

## What works in this milestone

| Area | Status |
|---|---|
| Branding, design system, 10 themes + full custom theme (colours, glow, gradient, borders, corners, opacity, text size, animation speed), follow-system, reset, persistence | Implemented, not device-tested |
| Library: cover grid / compact / large / list / carousel, search, filters, categories, sorting, Continue Playing, Favorites, hide/unhide, playtime tracking | Implemented, not device-tested |
| Long-press game menu (play, details, edit, cover, launch file, settings, mapping, open folder, shortcut, favorite, hide, remove) | Implemented, not device-tested |
| Import via Storage Access Framework (single game or folder of games), duplicate detection, persisted folder grants, no broad storage permission | Implemented, not device-tested |
| Detection engine: 19 engines, multi-signal confidence scoring, version extraction (MV/MZ, Ren'Py, Godot pck, RGSS archives), Pokémon Essentials profile, packed Electron/NW.js detection, PE header validation, launch-file candidates | Implemented; **unit tests written, not yet run** |
| **Web Runtime**: HTML5, RPG Maker MV, MZ, Construct 2/3, TyranoScript. Isolated `:game` process, per-game origin (separate saves), SAF-backed file server with HTTP range support, offline by default, bridge script | Implemented, not device-tested. Bridge JS: **tested (Node)** |
| Asset path cache: case-insensitive, traversal-safe, persisted per game, invalidated on rescan, rebuild from pause menu | Implemented; unit tests written |
| Controllers: Android gamepads (buttons, D-pad, hats, sticks, analog triggers), hot-plug, automatic default mapping, visual remapping editor | Implemented, not device-tested |
| Virtual gamepad (multi-touch, D-pad with diagonals, drag-to-arrange layout saved per orientation, opacity, size, haptics); virtual PC keyboard with sticky modifiers | Implemented, not device-tested |
| Pause overlay: resume, controls toggle, layout editor, keyboard, FPS overlay (measured), reload, rebuild cache, recent script errors, exit | Implemented, not device-tested |
| Layered config (per-game → per-engine → global → default) with inherited/overridden indicators and reset | Implemented; unit tests written |
| Compatibility Center: engine, version, confidence, runtime status/licence/blocker, diagnostics, detection evidence, engine override | Implemented, not device-tested |
| Library backup/restore (metadata + settings, clearly not game data), cache stats/clear, diagnostic report | Implemented, not device-tested |
| **Flash (Ruffle 0.7.1)**: .swf games via the bundled Ruffle WebAssembly build on the game's origin; SWF header parsing (version, compression, AS3 detection with warning); Flash-specific default controller layout; existing library entries are upgraded automatically | Implemented, not device-tested. Host page + bridge: **tested (Node, simulated Ruffle)**; SWF parser: unit tests written |
| **RGSS runtime integration** (mkxp-z via mkxp-z-android-reworked): SDL host activity in an isolated process, JNI contract, per-game data dir, player settings via the engine's own config merge, AceCore virtual gamepad/keyboard/menu over SDL, Restart (F12), playtime | Implemented, not device-tested. **Native libraries are built by the CI workflow, not by Android Studio** (see `docs/RGSS_RUNTIME.md`). Without them RGSS games are shown as "Runtime not built" |
| Game installer: incremental private working copy (no all-files permission), protects saves, cancellable progress, space check; storage usage and leftover cleanup; opt-in deletion on removal | Implemented; **unit tests written** |
| Script Compatibility Analyzer: Ruby Marshal reader + zlib for Data/Scripts.*; Essentials version, mkxp-z awareness, Windows DLL / native-extension / Fiddle warnings | Implemented; decoding algorithm **verified against real Ruby 3.3 output**; Kotlin tests use those bytes as fixtures |
| CI (`.github/workflows/build.yml`): compile + unit tests on every push; full APK with RGSS runtime on tag/manual run | Written, **not yet run** |
| RPG Maker 2000/2003 (EasyRPG), Ren'Py, Godot runtimes | **Not implemented.** Detected and explained in-app; see `docs/COMPATIBILITY_MATRIX.md` |
| Cheat manager, save manager, shader library, Vulkan renderer, Live2D, performance profiles, display viewport editor | **Not implemented**. Cheats and save editing depend on the RGSS runtime (Ruby game-state adapters) |

## Fixed in milestone 3
- Web/Flash games launched in a separate task (`singleTask` + own affinity), so Android reported the result as cancelled immediately. Playtime and launch errors weren't recorded. The game activity now launches normally.

## Getting a build that runs RPG Maker XP/VX/Ace games
Android Studio builds everything except the native RGSS engine. Copy `ci/build.yml` to `.github/workflows/build.yml` (create the folders), push this folder to a GitHub repository, then
**Actions → AceCore build → Run workflow**. It compiles and tests AceCore, builds mkxp-z with the NDK and produces
`AceCore-full-debug-arm64`. Details: `docs/RGSS_RUNTIME.md`.

## Known limitations to check first on device
- Construct 3 exports using "worker mode" may not load if the WebView doesn't route worker requests through AceCore's file server. Re-export with worker mode off if this happens.
- If a game folder changes after its first launch, use **Rebuild file cache** in the pause menu, or **Rescan** on the game page.
- Ruffle adds about 29 MB of WebAssembly to the APK (two builds, for newer and older WebViews). ActionScript 3 games may not work fully; the app warns per game.
- Flash games packaged as Windows "projector" .exe files (the SWF is embedded inside the exe) aren't extracted yet.
- Web-game saves live inside AceCore. Existing PC saves are not imported yet.

## Architecture

Single `:app` module with packages that mirror the planned modules (`core/*`, `feature/*`, `runtime/*`). Splitting into Gradle modules is mechanical once the build is green, and was deferred to keep the unverified first build simple. Manual DI (`AppContainer`) replaces Hilt for the same reason.

```
core/   config (layered settings, key maps) · data (GameRepository) · database (Room) · datastore
        designsystem (theme engine, components) · detection (engine detector) · filesystem (SAF, path index)
feature/ library (home, tiles, game menu) · importer · details (Compatibility Center) · settings · launcher
runtime/ RuntimeRegistry (single source of truth for what can launch) · web (GameActivity, WebGameServer,
         overlays) · input (controller translation)
assets/acecore/bridge.js   injected into web games (input, pause/audio, exit, FPS, filtering, errors)
```

The launcher and games run in different processes. The game process never opens Room or DataStore; it receives a resolved `GameLaunchSpec` and writes playtime to a session file that the launcher collects, even if the game crashes.

## Docs
- `docs/COMPATIBILITY_MATRIX.md`: research, licensing, Play policy, runtime roadmap
- `docs/RELEASE_CHECKLIST.md`
- `PRIVACY.md`
