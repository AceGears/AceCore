package com.acecore.app.core.model

import com.acecore.app.core.handheld.HandheldPlatform

/**
 * Every engine AceCore can recognise. Recognising an engine is NOT the same as being able to run it:
 * see [RuntimeKind] and [com.acecore.app.runtime.RuntimeRegistry] for what this build can actually launch.
 */
enum class Engine(val displayName: String, val family: EngineFamily) {
    RPG_MAKER_MV("RPG Maker MV", EngineFamily.WEB),
    RPG_MAKER_MZ("RPG Maker MZ", EngineFamily.WEB),
    CONSTRUCT_2("Construct 2", EngineFamily.WEB),
    CONSTRUCT_3("Construct 3", EngineFamily.WEB),
    TYRANO("TyranoBuilder / TyranoScript", EngineFamily.WEB),
    HTML5("HTML5", EngineFamily.WEB),
    RPG_MAKER_XP("RPG Maker XP", EngineFamily.RGSS),
    RPG_MAKER_VX("RPG Maker VX", EngineFamily.RGSS),
    RPG_MAKER_VX_ACE("RPG Maker VX Ace", EngineFamily.RGSS),
    POKEMON_ESSENTIALS("Pokémon Essentials", EngineFamily.RGSS),
    RPG_MAKER_2K("RPG Maker 2000/2003", EngineFamily.EASYRPG),
    RENPY("Ren'Py", EngineFamily.RENPY),
    GODOT("Godot", EngineFamily.GODOT),
    FLASH("Adobe Flash (SWF)", EngineFamily.FLASH),
    UNITY("Unity", EngineFamily.UNSUPPORTED_NATIVE),
    UNREAL("Unreal Engine", EngineFamily.UNSUPPORTED_NATIVE),
    GAMEMAKER("GameMaker", EngineFamily.WINDOWS),
    WOLF_RPG("WOLF RPG Editor", EngineFamily.WINDOWS),
    NATIVE_WINDOWS("Native Windows program", EngineFamily.WINDOWS),
    GAME_BOY("Game Boy", EngineFamily.HANDHELD),
    GAME_BOY_COLOR("Game Boy Color", EngineFamily.HANDHELD),
    GAME_BOY_ADVANCE("Game Boy Advance", EngineFamily.HANDHELD),
    NINTENDO_DS("Nintendo DS", EngineFamily.HANDHELD),
    UNKNOWN("Unknown", EngineFamily.UNSUPPORTED_NATIVE);

    companion object {
        fun fromName(name: String?): Engine = entries.firstOrNull { it.name == name } ?: UNKNOWN

        /** The library engine of a Nintendo handheld platform. */
        fun of(platform: HandheldPlatform): Engine = when (platform) {
            HandheldPlatform.GB -> GAME_BOY
            HandheldPlatform.GBC -> GAME_BOY_COLOR
            HandheldPlatform.GBA -> GAME_BOY_ADVANCE
            HandheldPlatform.NDS -> NINTENDO_DS
        }
    }

    /** The handheld platform of a [EngineFamily.HANDHELD] engine (null for every other engine). */
    val handheldPlatform: HandheldPlatform? get() = when (this) {
        GAME_BOY -> HandheldPlatform.GB
        GAME_BOY_COLOR -> HandheldPlatform.GBC
        GAME_BOY_ADVANCE -> HandheldPlatform.GBA
        NINTENDO_DS -> HandheldPlatform.NDS
        else -> null
    }
}

/**
 * WINDOWS: native Windows programs, run by the Windows runtime (Wine + Box64) when this build includes it.
 * HANDHELD: Nintendo handheld ROM files (Game Boy, Game Boy Color, Game Boy Advance, Nintendo DS); every ROM file is
 * its own library entry (treeUri + rootDocId point at the file, not a folder).
 */
enum class EngineFamily { WEB, RGSS, EASYRPG, RENPY, GODOT, FLASH, WINDOWS, HANDHELD, UNSUPPORTED_NATIVE }

/** Compatibility status shown in the UI. Values mirror the reporting vocabulary in the project brief. */
enum class CompatStatus(val label: String) {
    PLAYABLE("Playable"),
    PLAYABLE_UNTESTED("Runtime available"),
    RUNTIME_NOT_BUILT("Runtime not built yet"),
    UNSUPPORTED("Not supported"),
    PACKED("Needs extraction"),
    MISSING_FILES("Files missing"),
}
