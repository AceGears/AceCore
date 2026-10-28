# AceCore — Compatibility Feasibility Matrix

Research snapshot: October 2026. Licence facts are as published by each project; re-verify before release.

## Matrix

| Engine | How AceCore can run it | Upstream / licence | Feasibility | Status in this build |
|---|---|---|---|---|
| **HTML5 (generic)** | Android System WebView, files served from the SAF folder by `WebGameServer` | Chromium (system component) | High | **Implemented, not device-tested** |
| **RPG Maker MV** | Same Web Runtime; `www/` web root; NW.js-only plugins unavailable | Engine code ships inside each game (MIT core scripts) | High | **Implemented, not device-tested** |
| **RPG Maker MZ** | Same Web Runtime; root web root | Engine code ships inside each game | High | **Implemented, not device-tested** |
| **Construct 2 / 3** | Web Runtime (HTML5 export, or unpacked Electron/NW.js `resources/app`) | Runtime ships inside each export | High | **Implemented, not device-tested** |
| **TyranoBuilder / TyranoScript** | Web Runtime | TyranoScript MIT; ships inside each game | Medium-high (Electron builds must be unpacked from `app.asar`) | **Implemented, not device-tested** |
| **RPG Maker XP / VX / VX Ace** | Native RGSS runtime: **mkxp-z** (Ruby 3.1 + SDL2 + OpenAL + PhysFS) built with the NDK, hosted by an `SDLActivity`-style JNI activity | **GPL-2.0-or-later** | Medium (large NDK port; proven feasible by other Android launchers) | **Integrated (milestone 3)** via mkxp-z-android-reworked; natives built by CI; not device-tested. GPL decision made: AceCore is GPL-3.0-or-later |
| **Pokémon Essentials** | mkxp-z with the Essentials compatibility profile (v19+ ship `mkxp.json` and target mkxp-z directly; older versions need RGSS1 shims) | GPL-2.0+ (runtime); Essentials itself is a script kit inside each game | Medium | Runs on the integrated RGSS runtime (not device-tested); script analyzer reads exact Essentials version and flags DLL use |
| **RPG Maker 2000/2003** | **EasyRPG Player** (+ liblcf) built via NDK; already has an Android port upstream | Player **GPL-3.0**, liblcf MIT | Medium: see "0.4 engine evaluation" | Detection only. Blocked by an SDL clash with mkxp-z (below) |
| **Ren'Py** | Embed a Python 2.7 (Ren'Py ≤7) and Python 3 (Ren'Py 8) runtime + version-matched `renpy` modules; run the game's `game/` dir | Ren'Py MIT (+ LGPL deps) | Low-medium: each game bundles engine code tied to its version; needs per-major-version runtimes | Detection (version) only |
| **Godot 3 / 4** | Per-version Godot Android engine libraries launched with `--main-pack <game.pck>` | MIT | Medium for pure-GDScript games; impossible for GDExtension/C#/native-plugin games; .pck version must match engine exactly | Detection (pck header version) only |
| **Adobe Flash** | **Ruffle** self-hosted web build (WASM) inside the Web Runtime | MIT OR Apache-2.0 | High for AS1/2; partial for AS3 | **Implemented (milestone 2), not device-tested.** Ruffle 0.7.1 bundled |
| **Live2D (in VN projects)** | Only via the game's own engine (e.g. Ren'Py's Live2D support) plus Cubism Core | **Proprietary** Cubism SDK licence; redistribution requires agreement with Live2D Inc. | Low | Not started. Do not bundle without a licence agreement |
| **Unity / Unreal / GameMaker / WOLF RPG** | No viable path: native x86/x64 Windows code | — | Not feasible | Recognised and clearly explained as unsupported |
| **Arbitrary Windows .exe** | Would need a full Windows compatibility layer (Wine + x86 emulation, e.g. Box64) | — | Out of scope | `.exe` used only as a detection clue / launch selector |

## Key constraints discovered

**Google Play: executable code.** Play's Device and Network Abuse policy forbids downloading executable code (dex, JAR, .so) from outside Play, but exempts "code that runs in a virtual machine or an interpreter where either provides indirect access to Android APIs (such as JavaScript in a webview or browser)". The Web Runtime fits this exemption. Native runtimes (mkxp-z, EasyRPG, Godot, Ren'Py's Python) must be **bundled in the APK/AAB** (or delivered as Play Feature Delivery modules), never downloaded. Game scripts (Ruby, Python) are interpreted content supplied by the user, which is the same model existing launchers use, but a Play review is still a risk to plan for.

**Licensing is the main gate for native runtimes.** mkxp-z (GPL-2.0+) and EasyRPG Player (GPL-3.0) are copyleft. Linking them into AceCore means AceCore must be distributed under GPL-compatible terms (GPLv3 if both are included), with full source offered. The alternative is separate, independently installable runtime packages, but the brief rules out requiring external plugins. **Decision (Oct 2026): AceCore is distributed under GPL-3.0-or-later.**

**Target SDK.** Google Play requires new apps and updates to target API 36 (Android 16) from 31 Aug 2026; AceCore targets 36.

**Saves for web games.** MV/MZ in a browser store saves in `localStorage`. AceCore gives each game its own origin (`https://game-<id>.acecore.invalid`) so saves never collide between games. Saves live in AceCore's app data, not in the game's `save/` folder. Importing existing PC `.rpgsave` files needs a save manager (planned).

## 0.4 engine evaluation

* **RPG Maker 2000/2003 (EasyRPG Player).** Current EasyRPG Player for Android is built on **SDL3**
  (`builds/android/app/build.gradle` downloads an SDL3 AAR) and its activity extends `org.libsdl.app.SDLActivity`.
  mkxp-z's Android port is built on **SDL 2.26.3**, whose Java glue uses the same `org.libsdl.app` package and class
  names with different JNI methods. Both can't be in one APK as they are. Options: (a) build an EasyRPG release that
  still used SDL2 against SDL 2.26.3, or (b) rebuild one runtime's SDL with a renamed Java package (SDL's JNI names are
  derived from it). Both are native-toolchain projects of their own; not done in 0.4.
* **Godot 4.** Feasible for **one** engine version at a time: the official Android library
  (`org.godotengine:godot` on Maven Central) can be hosted in a separate `:godot` process and started with
  `--main-pack <path>` (direct mode supplies a real path; a .pck is a single file, so copy mode would only cache that
  file). Limits: a .pck generally needs the same major.minor engine version it was exported with, GDExtension/C#/native
  plugin games can't run, and the library adds tens of MB per bundled version. Recommended next runtime, scoped to the
  most common 4.x version among users' games.
* **Ren'Py.** Each game ships its own engine (`renpy/`, Python 2.7 for ≤7.x, Python 3 for 8.x) plus platform binaries
  for Windows/macOS/Linux only. Running it needs Android builds of Python, pygame_sdl2 and Ren'Py's native modules per
  major version. Not feasible inside this release.
* **TyranoBuilder, Construct 2/3, HTML5, RPG Maker MV/MZ, Flash:** already run on the web runtime / Ruffle; no change.
* **Unity, Unreal, GameMaker, WOLF RPG:** native Windows code; no Android path. Listed as "Not possible on Android".

## Recommended order of next runtimes

1. ~~Ruffle (Flash)~~: done in milestone 2.
2. ~~Licensing decision + mkxp-z~~: GPL chosen; integrated in milestone 3.
3. **Godot 4.x** for one engine version (see evaluation above).
4. **EasyRPG** (RPG Maker 2000/2003), after resolving the SDL2/SDL3 Java-package clash.
5. **Ren'Py** (hardest: per-version Python + engine).
