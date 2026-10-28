# RGSS runtime (RPG Maker XP / VX / VX Ace, Pokémon Essentials)

## How it fits together

```
Library (main process)                         :rgss process
──────────────────────                         ─────────────
LaunchCoordinator.prepareRgss()
  ├─ GameInstaller: sync SAF folder ─────────▶ <external files>/games/game_<id>/   (working copy; saves live here)
  ├─ write <files>/gamedata/game_<id>/mkxp.json (player settings; merged over the game's own mkxp.json)
  └─ start RgssActivity(RgssLaunchSpec) ────▶ RgssActivity extends org.libsdl.app.SDLActivity
                                                 ├─ GAME_PATH (static, read by native main via JNI)
                                                 ├─ getFilesDir() → gamedata/game_<id>  (= SDL pref path)
                                                 ├─ loads libSDL2…libruby…libmkxp-z
                                                 ├─ Compose overlay: virtual gamepad, keyboard, menu
                                                 └─ on exit: playtime → session file, System.exit (Ruby)
```

* **Engine:** [mkxp-z](https://github.com/mkxp-z/mkxp-z) through the Android port
  [mkxp-z-android-reworked](https://github.com/BookerRues9/mkxp-z-android-reworked), pinned in
  `.github/workflows/build.yml` (`MKXPZ_PORT_REF`). Native libraries are built by CI and are **not** committed.
* **Java glue:** `app/src/main/java/org/libsdl/app/` is SDL 2.26.3's Android Java code, copied unchanged from that
  port revision. It must match the native SDL version; SDL refuses to start on a mismatch.
* **JNI contract** (mkxp-z → host activity class): static field `GAME_PATH`, static methods
  `getSystemLanguage()`, `hasVibrator()`, `vibrate(int)`, `vibrateStop()`, `inMultiWindow(Activity)`. Provided by
  `RgssActivity`'s companion object. If the port is updated, re-check `grep -rn "GetStatic" app/jni/mkxp-z`.
* **Why a working copy:** mkxp-z (PhysFS, Ruby `File`) needs real paths. Copying into app-specific storage avoids
  the "All files access" permission, which Google Play restricts. Only new or changed files are copied on later
  launches. Files the game creates (saves) are never overwritten or deleted by a sync.
* **Per-game data:** SDL on Android returns `getFilesDir()` as the pref path for every app/game. `RgssActivity`
  overrides it so each game has its own folder, keeping Essentials v19+ saves and settings separate.
* **Availability:** `RgssRuntime.isPresent()` checks the APK for all seven libraries. Without them, RGSS games are
  still detected, analysed and listed, but marked "Runtime not built" (honest status, no fake launch).

## Building an APK with the runtime

1. Copy `ci/build.yml` to `.github/workflows/build.yml`, then push AceCore to a GitHub repository.
2. **Actions → AceCore build → Run workflow**, leave "build RGSS" ticked. Pushing a `v*` tag does the same.
3. Download the **AceCore-full-debug-arm64** artifact, or run **Publish APK from a build** with that run ID to attach it to a pre-release.

To build locally instead, run the "rgss-natives" steps on Linux (WSL2 works), then copy the resulting `*.so` into
`app/src/main/jniLibs/arm64-v8a/` (git-ignored) and build normally in Android Studio.

## Known limitations

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
