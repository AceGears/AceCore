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
            blocker = "Build with the 'AceCore full build' GitHub Actions workflow (it compiles mkxp-z, Ruby 3.1, SDL2 and OpenAL with the NDK), " +
                "or place the native libraries in app/src/main/jniLibs. See docs/RGSS_RUNTIME.md.",
            license = "GPL-2.0-or-later (mkxp-z); AceArcade is distributed under GPL-3.0-or-later",
        ),
        RuntimeId.EASYRPG to RuntimeInfo(
            RuntimeId.EASYRPG, bundled = false,
            status = "Detected, but EasyRPG Player is not built into this version.",
            blocker = "Requires building EasyRPG Player + liblcf for Android via NDK. EasyRPG Player is GPL-3.0.",
            license = "GPL-3.0 (Player), MIT (liblcf)",
        ),
        RuntimeId.RENPY to RuntimeInfo(
            RuntimeId.RENPY, bundled = false,
            status = "Detected, but no Ren'Py runtime is built into this version.",
            blocker = "Ren'Py games embed engine code matched to a specific Ren'Py/Python version. Needs Python 2.7 " +
                "(Ren'Py <= 7) and Python 3 (Ren'Py 8) builds for Android, plus version-matched renpy modules.",
            license = "MIT (Ren'Py), with LGPL components",
        ),
        RuntimeId.GODOT to RuntimeInfo(
            RuntimeId.GODOT, bundled = false,
            status = "Detected, but no Godot runtime is built into this version.",
            blocker = "A .pck only runs on the exact Godot major/minor that exported it; needs per-version Android " +
                "engine libraries started with --main-pack. GDExtension/C# games cannot run.",
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
            status = "This kind of game cannot run on Android through AceCore.",
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
        license = "GPL-2.0-or-later (mkxp-z); AceArcade is distributed under GPL-3.0-or-later",
    )

    fun info(id: RuntimeId): RuntimeInfo =
        if (id == RuntimeId.RGSS_MKXPZ && rgssNativeAvailable) rgssAvailableInfo else runtimes.getValue(id)
    fun info(engine: Engine): RuntimeInfo = info(runtimeFor(engine))
    fun canLaunch(engine: Engine): Boolean = info(engine).bundled
    fun all(): List<RuntimeInfo> = runtimes.keys.map { info(it) }
}
