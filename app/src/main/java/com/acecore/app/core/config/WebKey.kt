package com.acecore.app.core.config

/**
 * A keyboard key as seen by a web game. Games read either the legacy `keyCode` (RPG Maker MV/MZ,
 * Construct 2) or the modern `key`/`code` (Construct 3, most new HTML5 games); AceCore sends all three.
 */
data class WebKey(val id: String, val label: String, val keyCode: Int, val key: String, val code: String) {
    /** The equivalent android.view.KeyEvent keycode, for native (SDL) runtimes. 0 = none. */
    val androidKeyCode: Int get() = when {
        id.length == 1 && id[0] in 'A'..'Z' -> 29 + (id[0] - 'A')          // KEYCODE_A = 29
        id.length == 2 && id[0] == 'D' && id[1].isDigit() -> 7 + (id[1] - '0') // KEYCODE_0 = 7
        id.startsWith("F") && (id.drop(1).toIntOrNull() ?: 0) in 1..12 -> 131 + id.drop(1).toInt() - 1 // KEYCODE_F1 = 131
        else -> ANDROID_KEYS[id] ?: 0
    }

    companion object {
        private fun letter(c: Char) = WebKey(c.toString(), c.toString(), c.code, c.lowercaseChar().toString(), "Key$c")
        private fun digit(d: Int) = WebKey("D$d", d.toString(), 48 + d, d.toString(), "Digit$d")
        private fun fn(n: Int) = WebKey("F$n", "F$n", 111 + n, "F$n", "F$n")

        val ALL: List<WebKey> = buildList {
            add(WebKey("NONE", "— None —", 0, "", ""))
            add(WebKey("ENTER", "Enter", 13, "Enter", "Enter"))
            add(WebKey("ESCAPE", "Esc", 27, "Escape", "Escape"))
            add(WebKey("SPACE", "Space", 32, " ", "Space"))
            add(WebKey("SHIFT", "Shift", 16, "Shift", "ShiftLeft"))
            add(WebKey("CTRL", "Ctrl", 17, "Control", "ControlLeft"))
            add(WebKey("ALT", "Alt", 18, "Alt", "AltLeft"))
            add(WebKey("TAB", "Tab", 9, "Tab", "Tab"))
            add(WebKey("BACKSPACE", "Backspace", 8, "Backspace", "Backspace"))
            add(WebKey("UP", "↑", 38, "ArrowUp", "ArrowUp"))
            add(WebKey("DOWN", "↓", 40, "ArrowDown", "ArrowDown"))
            add(WebKey("LEFT", "←", 37, "ArrowLeft", "ArrowLeft"))
            add(WebKey("RIGHT", "→", 39, "ArrowRight", "ArrowRight"))
            add(WebKey("PAGEUP", "PgUp", 33, "PageUp", "PageUp"))
            add(WebKey("PAGEDOWN", "PgDn", 34, "PageDown", "PageDown"))
            add(WebKey("INSERT", "Ins", 45, "Insert", "Insert"))
            add(WebKey("DELETE", "Del", 46, "Delete", "Delete"))
            add(WebKey("HOME", "Home", 36, "Home", "Home"))
            add(WebKey("END", "End", 35, "End", "End"))
            ('A'..'Z').forEach { add(letter(it)) }
            (0..9).forEach { add(digit(it)) }
            (1..12).forEach { add(fn(it)) }
        }
        private val byId = ALL.associateBy { it.id }

        /** android.view.KeyEvent constants (kept numeric so this file stays free of Android imports). */
        private val ANDROID_KEYS = mapOf(
            "ENTER" to 66, "ESCAPE" to 111, "SPACE" to 62, "SHIFT" to 59, "CTRL" to 113, "ALT" to 57, "TAB" to 61,
            "BACKSPACE" to 67, "UP" to 19, "DOWN" to 20, "LEFT" to 21, "RIGHT" to 22, "PAGEUP" to 92, "PAGEDOWN" to 93,
            "INSERT" to 124, "DELETE" to 112, "HOME" to 122, "END" to 123,
        )
        fun byId(id: String?): WebKey? = id?.let { byId[it] }
    }
}

/** Physical controller inputs that can be mapped. Directions come from the D-pad and the left stick. */
enum class PadButton(val label: String) {
    A("A / Cross"), B("B / Circle"), X("X / Square"), Y("Y / Triangle"),
    L1("L1 / LB"), R1("R1 / RB"), L2("L2 / LT"), R2("R2 / RT"),
    START("Start / Options"), SELECT("Select / Share"), L3("L3"), R3("R3"),
    UP("D-pad Up"), DOWN("D-pad Down"), LEFT("D-pad Left"), RIGHT("D-pad Right"),
}

/** Serialised as "A=Z;B=X;..." so it can live in any layer of the config system. */
object ButtonMap {
    /** Defaults chosen for RPG Maker MV/MZ semantics (Z/Enter = OK, X/Esc = cancel/menu, Shift = dash, Q/W = page). */
    val DEFAULT: Map<PadButton, String> = mapOf(
        PadButton.A to "Z", PadButton.B to "X", PadButton.X to "SHIFT", PadButton.Y to "ESCAPE",
        PadButton.L1 to "Q", PadButton.R1 to "W", PadButton.L2 to "PAGEUP", PadButton.R2 to "PAGEDOWN",
        PadButton.START to "ENTER", PadButton.SELECT to "ESCAPE", PadButton.L3 to "NONE", PadButton.R3 to "NONE",
        PadButton.UP to "UP", PadButton.DOWN to "DOWN", PadButton.LEFT to "LEFT", PadButton.RIGHT to "RIGHT",
    )

    fun encode(map: Map<PadButton, String>): String =
        PadButton.entries.joinToString(";") { "${it.name}=${map[it] ?: DEFAULT.getValue(it)}" }

    fun decode(s: String?): Map<PadButton, String> {
        val out = DEFAULT.toMutableMap()
        s?.split(';')?.forEach { pair ->
            val k = pair.substringBefore('=', "")
            val v = pair.substringAfter('=', "")
            val btn = PadButton.entries.firstOrNull { it.name == k } ?: return@forEach
            if (WebKey.byId(v) != null) out[btn] = v
        }
        return out
    }

    val DEFAULT_ENCODED: String = encode(DEFAULT)

    /** Flash games overwhelmingly use arrows + Space, with Z/X/C as secondary actions. */
    val FLASH_DEFAULT: Map<PadButton, String> = DEFAULT + mapOf(
        PadButton.A to "SPACE", PadButton.B to "Z", PadButton.X to "X", PadButton.Y to "C",
        PadButton.L1 to "SHIFT", PadButton.R1 to "CTRL", PadButton.START to "ENTER", PadButton.SELECT to "ESCAPE",
    )
    val FLASH_DEFAULT_ENCODED: String = encode(FLASH_DEFAULT)

    /**
     * RPG Maker XP/VX/VX Ace (mkxp-z, incl. Pokémon Essentials). Keys follow mkxp-z's default bindings so every pad
     * button reaches a distinct RGSS button: Enter → C (confirm), X → B (cancel/menu), Shift → A, D → Z, Q → L,
     * W → R, A → X, S → Y. (The MV/MZ layout sent Z for the A button, which is "A/Action" in RPG Maker XP, not
     * confirm, and PageUp/PageDown, which mkxp-z doesn't bind.)
     */
    val RGSS_DEFAULT: Map<PadButton, String> = DEFAULT + mapOf(
        PadButton.A to "ENTER", PadButton.B to "X", PadButton.X to "SHIFT", PadButton.Y to "D",
        PadButton.L1 to "Q", PadButton.R1 to "W", PadButton.L2 to "A", PadButton.R2 to "S",
        PadButton.START to "ESCAPE", PadButton.SELECT to "Z",
    )
    val RGSS_DEFAULT_ENCODED: String = encode(RGSS_DEFAULT)

    fun defaultFor(rgss: Boolean) = if (rgss) RGSS_DEFAULT else DEFAULT

    /** Like [decode], filling unmapped buttons from [base]. */
    fun decode(s: String?, base: Map<PadButton, String>): Map<PadButton, String> {
        val out = base.toMutableMap()
        s?.split(';')?.forEach { pair ->
            val btn = PadButton.entries.firstOrNull { it.name == pair.substringBefore('=', "") } ?: return@forEach
            val v = pair.substringAfter('=', "")
            if (WebKey.byId(v) != null) out[btn] = v
        }
        return out
    }
}

/**
 * What a keyboard key does in an RPG Maker XP/VX/VX Ace game under mkxp-z's default bindings ([rgss] = 1 for XP,
 * 2/3 for VX/VX Ace), named with the game's own button names when it provides them (mkxp.json "bindingNames").
 */
object RgssKeys {
    private val common = mapOf(
        "UP" to "up", "DOWN" to "down", "LEFT" to "left", "RIGHT" to "right",
        "ENTER" to "c", "SPACE" to "c", "ESCAPE" to "b", "X" to "b", "SHIFT" to "a",
        "D" to "z", "Q" to "l", "W" to "r", "A" to "x", "S" to "y",
    )
    private val standardNames = mapOf(
        "up" to "Up", "down" to "Down", "left" to "Left", "right" to "Right",
        "a" to "A", "b" to "B (cancel / menu)", "c" to "C (confirm)", "x" to "X", "y" to "Y", "z" to "Z", "l" to "L", "r" to "R",
    )

    /** RGSS button ("a".."z", "l", "r", directions) a key reaches, or null if mkxp-z doesn't bind it. */
    fun button(keyId: String, rgss: Int): String? = when (keyId) {
        "Z" -> if (rgss == 1) "a" else "c"
        "C" -> if (rgss == 1) "c" else null
        "F12" -> "reset"
        else -> common[keyId]
    }

    fun describe(keyId: String, rgss: Int, gameNames: Map<String, String>): String {
        val b = button(keyId, rgss) ?: return if (keyId == "NONE") "Nothing" else "Keyboard key (only if the game reads it itself)"
        if (b == "reset") return "Restart game (F12)"
        val std = standardNames[b] ?: b
        val custom = gameNames[b]
        return if (custom != null && b.length == 1) "${b.uppercase()} · $custom" else std
    }

    /** Button names from a game's mkxp.json ("bindingNames": {"c": "Use", ...}). */
    fun gameNames(mkxpJson: String?): Map<String, String> {
        val block = mkxpJson?.let { Regex("\"bindingNames\"\\s*:\\s*\\{([^}]*)\\}").find(it)?.groupValues?.get(1) } ?: return emptyMap()
        return Regex("\"([a-z])\"\\s*:\\s*\"([^\"]{1,24})\"").findAll(block).associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** Keys offered in the button-mapping panel, in a sensible order. */
    val CHOICES = listOf("ENTER", "SPACE", "ESCAPE", "X", "SHIFT", "Z", "C", "D", "A", "S", "Q", "W",
        "UP", "DOWN", "LEFT", "RIGHT", "F5", "F8", "F12", "V", "NONE")
}
