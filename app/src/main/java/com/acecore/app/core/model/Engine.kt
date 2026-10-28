package com.acecore.app.core.model

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
    GAMEMAKER("GameMaker", EngineFamily.UNSUPPORTED_NATIVE),
    WOLF_RPG("WOLF RPG Editor", EngineFamily.UNSUPPORTED_NATIVE),
    NATIVE_WINDOWS("Native Windows program", EngineFamily.UNSUPPORTED_NATIVE),
    UNKNOWN("Unknown", EngineFamily.UNSUPPORTED_NATIVE);

    companion object {
        fun fromName(name: String?): Engine = entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

enum class EngineFamily { WEB, RGSS, EASYRPG, RENPY, GODOT, FLASH, UNSUPPORTED_NATIVE }

/** Compatibility status shown in the UI. Values mirror the reporting vocabulary in the project brief. */
enum class CompatStatus(val label: String) {
    PLAYABLE("Playable"),
    PLAYABLE_UNTESTED("Runtime available"),
    RUNTIME_NOT_BUILT("Runtime not built yet"),
    UNSUPPORTED("Not supported"),
    PACKED("Needs extraction"),
    MISSING_FILES("Files missing"),
}
