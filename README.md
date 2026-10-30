# AceArcade

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

## What's in 0.4

| Area | Status |
|---|---|
| **Direct launching (RGSS)**: with Android's "All files access", RPG Maker XP/VX/VX Ace and Pokémon Essentials games run in place from their folder (SAF id → real path); no copy, no per-launch file check. Asked once, on first play; optional | Implemented. Engine start from shared storage: **see emulator result below**; not yet tested on a phone |
| **Private copy fallback**: copied once, later launches immediate; rescan marks it outdated; cancelled copies finish next launch; saves can be copied out of an old copy and the copy deleted (game-created files are backed up first) | Implemented; installer unit-tested |
| **Launch-file browser**: Automatic / Manual, browse the game folder and sub-folders, any file selectable, clear explanation for files that can't start a game, choice stored permanently, "launch file missing" prompt | Implemented; rules unit-tested (13 tests) |
| Accurate wording: "Selected file: Game.exe · Runtime: RGSS-compatible Android runtime (mkxp-z) · Configuration: Game.ini" | Implemented |
| **Home screen**: logo header with search, layout and settings; no Continue Playing / filter chips; category tabs or pills; cover grid with columns, size, aspect ratio, spacing, radius, border colour, glow, titles; tap = play, long-press = game menu; multi-select | Implemented; UI tour on emulator (see below) |
| **Categories**: create/rename/icon/colour/cover, hide/unhide, drag-to-reorder, delete (games stay), many categories per game, add/move/remove (batch), linked folders with scan and optional auto-scan | Implemented; database behaviour Robolectric-tested |
| **Game page**: cover backdrop, Play/Favourite/Settings, How it starts, Game files (direct/copy), Statistics, Cheats, Categories, Manage, Game information; Diagnostics moved to a Troubleshooting screen | Implemented |
| **Statistics**: playtime (game on screen only), sessions, first/last played, average/longest session, successful/failed launches, measured start-up time | Implemented; DB update logic Robolectric-tested |
| **Cheat manager** (RGSS): enable switch, profiles, favourites, search, import/export, reset, save backups/restore; in-game panel: money, items, Poké Balls, level/EXP/IV/EV/nature/ability/friendship/shiny, heal, add Pokémon, send to PC, movement speed, no random encounters; RPG Maker gold/items/level/recover | Implemented. Ruby side tested against mocks of Essentials v21/v18 and RPG Maker XP (54 checks); method names checked against Essentials v21.1 source. **Not yet tried with a real Essentials game** |
| **Engines page**: grouped, runtime version, honest status (Runs on this device / Installed / Not in this version / Not supported) | Implemented |
| Database v1 → v2 migration keeps every game, playtime, favourite and setting; v0.3 categories become real categories | Robolectric-tested |
| RPG Maker 2000/2003, Godot, Ren'Py runtimes | **Not implemented**; evaluated in `docs/COMPATIBILITY_MATRIX.md` |

## Getting a build that runs RPG Maker XP/VX/Ace games
Android Studio builds the app; the native game engines come from CI (or, locally, see `docs/BUILD-ANDROID-STUDIO.md`).
On GitHub: **Actions → AceArcade Android → Run workflow**. Engines are rebuilt only when their pinned sources change;
the APK is the `AceArcade-Android-arm64` artifact. Releases: push a tag `vX.Y.Z` matching `versionName`.
How the pipelines, caches, costs and releases work: `docs/CI.md`. Engine details: `docs/RGSS_RUNTIME.md`.

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
