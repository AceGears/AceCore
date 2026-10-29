# Building AceArcade for Android in Android Studio

You can build the app on your own computer, with no GitHub Actions minutes. The game engines are native
libraries that CI already built. Download them once and put them in the project, then build as usual.

## 1. Get the code

In Android Studio, use **File › New › Project from Version Control** with `https://github.com/AceGears/AceCore`
and check out the branch **overhaul-0.4**. If you already have it, use **Git › Pull**. Let Gradle sync finish.
It also syncs the desktop module, which only downloads its dependencies.

Requirements: Android Studio Narwhal or newer (it brings JDK 17+), Android SDK Platform 36.

## 2. Add the engines (download once, while signed in to GitHub)

| Engine | Artifact | What to copy |
|---|---|---|
| RPG Maker XP/VX/Ace + Pokémon Essentials (mkxp-z, with the renamed-ini fix) | [rgss-natives-arm64-v8a](https://github.com/AceGears/AceCore/actions/runs/40964752662/artifacts/12937618503) | every `.so` inside its `arm64-v8a` folder |
| RPG Maker 2000/2003 (EasyRPG Player) | [easyrpg-natives-arm64-v8a](https://github.com/AceGears/AceCore/actions/runs/40910959950/artifacts/12913119934) | every `.so` |
| Godot 4 | [godot-natives-arm64-v8a](https://github.com/AceGears/AceCore/actions/runs/40911549388/artifacts/12914733793) | `libgodot_android.so` |

Unzip each artifact and copy the files listed into **`app/src/main/jniLibs/arm64-v8a/`**. Create the folder if
it doesn't exist. Git ignores that folder, so the libraries are never committed. Artifacts expire after 90 days;
CI can rebuild them (`build.yml`, `build_rgss` / `build_easyrpg` / `build_godot`).

Without these files the app still builds and runs web/HTML5 and Flash games, but the RGSS, EasyRPG and Godot
engines show as "Unavailable".

## 3. Build and install

- Phone connected with USB debugging: choose the **app** run configuration and press **Run**. Or
- **Build › Build App Bundle(s) / APK(s) › Build APK(s)**: the APK is in `app/build/outputs/apk/debug/`.

Debug builds are signed with the project's development key (`ci/acecore-dev.keystore`), the same key the CI
builds use. So the APK installs **over** the version already on your phone and keeps your library, settings,
playtime and saves. Don't uninstall first.

Optional: the built-in "Test this engine" game for Godot (`assets/selftest/godot/SelfTest.pck`) is exported by
CI with the Godot editor. Without it, the Godot test button is hidden; Godot games still run.
