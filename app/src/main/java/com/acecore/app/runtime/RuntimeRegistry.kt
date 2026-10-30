package com.acecore.app.runtime

import com.acecore.app.core.model.Engine
import com.acecore.app.core.model.EngineFamily

/** A runtime that may be bundled with AceCore. */
enum class RuntimeId(val displayName: String) {
    WEB("AceArcade Web Runtime (Android System WebView)"),
    RGSS_MKXPZ("RGSS runtime (mkxp-z based)"),
    EASYRPG("EasyRPG Player"),
    RENPY("Ren'Py runtime"),
    GODOT("Godot runtime"),
    RUFFLE("Ruffle (Flash)"),
    NONE("No runtime"),
}

data class RuntimeInfo(
    val id: RuntimeId,
    /** True only when this build contains a genuine launch path for the runtime. */
    val bundled: Boolean,
    /** Honest one-line status for the Compatibility Center. */
    val status: String,
    /** What still has to happen before this runtime can ship. */
    val blocker: String? = null,
    val license: String? = null,
    /** Version of the bundled runtime, when known at build time. */
    val version: String? = null,
    /** What kinds of game files it runs. */
    val formats: String? = null,
    /** Known limitations, stated plainly. */
    val limitations: String? = null,
)

/**
 * Single source of truth for what this build can launch. Nothing outside this file may claim an engine
 * is playable. When a native runtime is integrated, flip [RuntimeInfo.bundled] here AND add its launcher.
 */
object RuntimeRegistry {

    private val runtimes: Map<RuntimeId, RuntimeInfo> = mapOf(
        RuntimeId.WEB to RuntimeInfo(
            RuntimeId.WEB, bundled = true,
            status = "Bundled. Serves game files from your folder into an isolated WebView.",
            license = "Uses the device's Android System WebView (Chromium, BSD-3-Clause)",
        ),
        RuntimeId.RGSS_MKXPZ to RuntimeInfo(
            RuntimeId.RGSS_MKXPZ, bundled = false,
            status = "Integrated, but the native mkxp-z libraries are not in this build. Builds produced by AceArcade's CI workflow include them.",
            blocker = "Build with the 'AceArcade Android' GitHub Actions workflow (Run workflow) (it compiles mkxp-z, Ruby 3.1, SDL2 and OpenAL with the NDK), " +
                "or place the native libraries in app/src/main/jniLibs. See docs/RGSS_RUNTIME.md.",
            license = "GPL-2.0-or-later (mkxp-z); AceArcade is distributed under GPL-3.0-or-later",
        ),
        RuntimeId.EASYRPG to RuntimeInfo(
            RuntimeId.EASYRPG, bundled = false,
            formats = "RPG Maker 2000 / 2003 games (RPG_RT.ldb, RPG_RT.lmt, Map*.lmu)",
            status = "Detected, but this APK was built without EasyRPG Player's native libraries.",
            blocker = "AceArcade's CI builds them (EasyRPG Player + liblcf + SDL3, job \"easyrpg-natives\"); use an APK that includes them.",
            license = "GPL-3.0 (Player), MIT (liblcf)",
        ),
        RuntimeId.RENPY to RuntimeInfo(
            RuntimeId.RENPY, bundled = false,
            status = "Detected, but no Ren'Py runtime is included in this version.",
            blocker = "A PC Ren'Py game ships its own engine (renpy/ folder) as Python code plus compiled modules for Windows/" +
                "Linux/macOS only. Running it on Android needs a Python interpreter of the same generation (Python 2.7 for " +
                "Ren'Py 6–7, Python 3.9+ for Ren'Py 8) built for Android with Ren'Py's native modules (pygame_sdl2, " +
                "renpy.display.accelerator, ffmedia…) compiled for that exact Ren'Py version. Ren'Py's own Android tooling " +
                "(renpy-build/RAPT) builds one APK per game; a general launcher needs a runtime per Ren'Py release line. " +
                "That build work has not been done for this version.",
            formats = "Ren'Py 6.x–8.x PC releases (game/*.rpa, *.rpyc)",
            license = "MIT (Ren'Py), with LGPL components",
        ),
        RuntimeId.GODOT to RuntimeInfo(
            RuntimeId.GODOT, bundled = true,
            version = "Godot 4.7.0 (org.godotengine:godot)",
            formats = "Godot 4.0–4.7 exports: Game.pck next to the .exe, or the pack embedded in the .exe",
            limitations = "Godot 3 games, C# (.NET) games and GDExtension plugins (Windows DLLs) can't run. Games exported " +
                "for PC use desktop texture formats, which the engine converts at load time on phones without them (slower " +
                "first load). Keyboard-only games are played with the on-screen controller, which sends keys.",
            status = "Bundled: the Godot 4.7 Android engine runs Godot 4.0–4.7 games straight from your folder (.pck file, or " +
                "the pack inside the game's .exe). Godot 3 games, C# (.NET) games and games that rely on Windows-only " +
                "extensions (GDExtension DLLs) can't run.",
            license = "MIT",
        ),
        RuntimeId.RUFFLE to RuntimeInfo(
            RuntimeId.RUFFLE, bundled = true,
            version = "Ruffle 0.7.1 (WebAssembly)",
            status = "Bundled (Ruffle 0.7.1, WebAssembly) inside the Web Runtime. ActionScript 1/2 games are generally well " +
                "supported; ActionScript 3 support in Ruffle is still incomplete, so some AS3 games may not work.",
            license = "MIT OR Apache-2.0",
        ),
        RuntimeId.NONE to RuntimeInfo(
            RuntimeId.NONE, bundled = false,
            status = "This kind of game cannot run on Android through AceArcade.",
            blocker = "Native Windows code (x86/x64) cannot execute on Android without a full Windows " +
                "compatibility layer, which AceArcade does not include.",
        ),
    )

    fun runtimeFor(engine: Engine): RuntimeId = when (engine.family) {
        EngineFamily.WEB -> RuntimeId.WEB
        EngineFamily.RGSS -> RuntimeId.RGSS_MKXPZ
        EngineFamily.EASYRPG -> RuntimeId.EASYRPG
        EngineFamily.RENPY -> RuntimeId.RENPY
        EngineFamily.GODOT -> RuntimeId.GODOT
        EngineFamily.FLASH -> RuntimeId.RUFFLE
        EngineFamily.UNSUPPORTED_NATIVE -> RuntimeId.NONE
    }

    /**
     * Set once at startup from the APK contents. Native runtimes are optional at build time, so availability is a
     * property of the installed APK, not of the source code.
     */
    @Volatile var rgssNativeAvailable: Boolean = false

    private val rgssAvailableInfo = RuntimeInfo(
        RuntimeId.RGSS_MKXPZ, bundled = true,
        version = "mkxp-z via mkxp-z-android-reworked @ b668e08 · Ruby 3.1 · SDL 2.26.3",
        status = "Bundled (mkxp-z for Android). Games run directly from their folder when AceArcade has file access, otherwise from a private copy.",
        formats = "RPG Maker XP / VX / VX Ace games (Game.ini + Data/Scripts.*), incl. Pokémon Essentials",
        limitations = "Windows-only script calls (Win32API) work only where AceArcade implements them (WinInet downloads, " +
            "a few kernel32 calls). The Android engine is an older mkxp-z; AceArcade's compatibility layer adds the newer " +
            "functions games use (Essentials v21 timing in seconds, Graphics.display_*, System.*, Audio volumes).",
        license = "GPL-2.0-or-later (mkxp-z); AceArcade is distributed under GPL-3.0-or-later",
    )

    /** True when this APK contains EasyRPG Player's native libraries (CI "easyrpg-natives" job). */
    @Volatile var easyRpgNativeAvailable: Boolean = false

    private val easyRpgAvailableInfo = RuntimeInfo(
        RuntimeId.EASYRPG, bundled = true,
        version = "EasyRPG Player (096c004, Oct 2026) · liblcf · SDL 3.4.8",
        status = "Bundled (EasyRPG Player). RPG Maker 2000/2003 games run straight from your folder. Games that rely on the " +
            "RTP (FullPackageFlag=0) need the RTP files; patched engines (DynRPG, some Maniac Patch features) may not work fully.",
        license = "GPL-3.0 (EasyRPG Player), MIT (liblcf)",
        formats = "RPG Maker 2000 / 2003 games (RPG_RT.ldb, RPG_RT.lmt, Map*.lmu)",
        limitations = "Games that use the RTP need the RTP installed. DynRPG plugins (Windows DLLs) don't run; Maniac " +
            "Patch support follows EasyRPG's own progress.",
    )

    fun info(id: RuntimeId): RuntimeInfo = when {
        id == RuntimeId.RGSS_MKXPZ && rgssNativeAvailable -> rgssAvailableInfo
        id == RuntimeId.EASYRPG && easyRpgNativeAvailable -> easyRpgAvailableInfo
        else -> runtimes.getValue(id)
    }
    fun info(engine: Engine): RuntimeInfo = info(runtimeFor(engine))
    fun canLaunch(engine: Engine): Boolean = info(engine).bundled
    fun all(): List<RuntimeInfo> = runtimes.keys.map { info(it) }
}
