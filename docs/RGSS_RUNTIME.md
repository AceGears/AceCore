# RGSS runtime (RPG Maker XP / VX / VX Ace, Pokémon Essentials)

## How it fits together

```
Library (main process)                              :rgss process
──────────────────────                              ─────────────
LaunchCoordinator.prepareRgss()
  ├─ direct mode (default, needs "All files access"):
  │    SAF tree + doc id ──DirectStorage──▶ /storage/emulated/0/<folder>   (real path; nothing copied)
  ├─ copy mode (fallback / user choice):
  │    GameInstaller: one-time copy ───────▶ <external files>/games/game_<id>/  (later launches: no re-check)
  ├─ writes <files>/gamedata/game_<id>/mkxp.json         (player settings + preloadScript)
  ├─ writes <files>/gamedata/game_<id>/acecore_preload.rb (runtime helper, see below)
  └─ start RgssActivity(RgssLaunchSpec) ──────────▶ RgssActivity extends org.libsdl.app.SDLActivity
                                                      ├─ GAME_PATH = folder with Game.ini (JNI static)
                                                      ├─ getFilesDir() → gamedata/game_<id> (= SDL pref path)
                                                      ├─ libSDL2…libruby…libmkxp-z
                                                      ├─ Compose overlay: gamepad, keyboard, menu, cheats
                                                      └─ on exit: play time → session file, System.exit
```

* **Engine:** [mkxp-z](https://github.com/mkxp-z/mkxp-z) through the Android port
  [mkxp-z-android-reworked](https://github.com/BookerRues9/mkxp-z-android-reworked), pinned in
  `.github/workflows/build.yml` (`MKXPZ_PORT_REF`). Native libraries are built by CI and are **not** committed.
* **Java glue:** `app/src/main/java/org/libsdl/app/` is SDL 2.26.3's Android Java code, copied unchanged from that
  port revision. It must match the native SDL version; SDL refuses to start on a mismatch.
* **JNI contract** (mkxp-z → host activity class): static field `GAME_PATH`, static methods
  `getSystemLanguage()`, `hasVibrator()`, `vibrate(int)`, `vibrateStop()`, `inMultiWindow(Activity)`. Provided by
  `RgssActivity`'s companion object. If the port is updated, re-check `grep -rn "GetStatic" app/jni/mkxp-z`.

### Why games used to be copied, and what changed in 0.4

mkxp-z opens game files by path: PhysFS for graphics/audio and Ruby's own `File`/`Dir`, which Pokémon Essentials
uses directly (for example to find PBS and save files). A folder picked with the Storage Access Framework is only
reachable through `content://` URIs, which neither can use. A virtual-filesystem layer would only cover PhysFS, not
Ruby's `File`/`Dir`, so it can't make Essentials work. 0.3 therefore copied every game into app storage (23,474
files / 571 MB for one test game) and re-walked the whole folder through SAF on every launch to check for changes.

0.4 does what the upstream port itself does: with Android's **All files access** (API 30+) or the storage permission
(API 26–29) the SAF folder is mapped to its real path (`primary:Games/X` → `/storage/emulated/0/Games/X`; SD cards
via `StorageManager` volumes; `home:` → Documents; Downloads `raw:` ids) and mkxp-z runs the game **in place**.
The permission is optional and asked for only when an RGSS game is first played. Without it, or for folders whose
provider has no real path (cloud providers), the private copy remains, with two changes: it is made once, and later
launches start immediately. A rescan marks it outdated so the next launch copies only new or changed files.
Game pages can copy saves out of an old private copy into the game folder (never overwriting) and delete the copy.

### Runtime helper (`assets/acecore/rgss/acecore_runtime.rb`)

Loaded with mkxp-z's `preloadScript` option before the game's scripts. It hooks `Graphics.update` (rescuing every
error so it can't break a game) and:

* writes `ready` on the first frame → launch statistics and measured start-up time (tap → first frame), and the
  in-game "Starting…" indicator disappears at that moment;
* writes `error` if the game ends with a Ruby exception (shown in Troubleshooting);
* when cheats are enabled for the game, executes commands from AceCore's in-game menu (`cmd` → `result`/`state`).
  Cheats call the game's own methods after checking they exist (`respond_to?`), so each one is offered only when the
  running game supports it. Method names were checked against Pokémon Essentials v21.1 source; behaviour is covered by
  `app/src/test/ruby/runtime_test.rb` with mock Essentials v20/v21, v17/v18 and RPG Maker XP environments.

It can be turned off per game or globally (Game settings › Advanced › AceCore game helper).

* **Per-game data:** SDL on Android returns `getFilesDir()` as the pref path for every app/game. `RgssActivity`
  overrides it so each game has its own folder, keeping Essentials v19+ saves and settings separate.
* **Availability:** `RgssRuntime.isPresent()` checks the APK for all seven libraries. Without them, RGSS games are
  still detected, analysed and listed, but marked "Not playable yet" (honest status, no fake launch).

## Building an APK with the runtime

1. The workflow is `.github/workflows/build.yml` ("AceArcade Android"); see `docs/CI.md`.
2. **Actions → AceCore build → Run workflow**, leave "build RGSS" ticked. Pushing a `v*` tag does the same.
3. Download the **AceCore-full-debug-arm64** artifact, or run **Publish APK from a build** with that run ID to attach it to a pre-release.

To build locally instead, run the "rgss-natives" steps on Linux (WSL2 works), then copy the resulting `*.so` into
`app/src/main/jniLibs/arm64-v8a/` (git-ignored) and build normally in Android Studio.

## Known limitations

* Direct mode needs "All files access", which Google Play only allows for some app categories. A Play release would
  need a policy declaration or would have to rely on the private-copy fallback.
* arm64-v8a only for now. 32-bit-only devices see the runtime as unavailable.
* No true pause: the in-game menu overlays the running game.
* Physical controllers are handled by SDL/mkxp-z directly with mkxp-z's default bindings; AceCore's button
  remapping applies to the on-screen pad (and to all controls in web/Flash games).
* Windows DLL calls in scripts (Steam, custom input or video DLLs) cannot work. The Script Compatibility Analyzer
  lists them per game.
* **16 KB pages: not yet.** Verified on build v0.3.0-build1: mkxp-z, Ruby, SDL2, OpenAL and libc++_shared have 4 KB
  LOAD alignment (NDK 23 + ndk-build ignore the LDFLAGS used for the dependencies). They work on 4 KB-page devices
  (nearly all phones today). Fix before a Play release: move the port build to NDK r27+ and add
  `APP_LDFLAGS += -Wl,-z,max-page-size=16384` to Application.mk.
* Some mkxp-z features need a keyboard/mouse (F1 settings). AceCore disables F1 settings and offers "Restart game"
  (F12) in its menu instead.
