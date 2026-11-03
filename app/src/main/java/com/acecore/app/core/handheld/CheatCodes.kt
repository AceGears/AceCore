package com.acecore.app.core.handheld

/**
 * Syntax check and format recognition for cheat codes, per system, limited to what the bundled emulators execute:
 * mGBA (Game Boy / Color: Game Genie, GameShark; Game Boy Advance: GameShark / Action Replay v1-v3 and CodeBreaker,
 * encrypted or not, detected by mGBA itself) and melonDS (Nintendo DS: Action Replay DS). A code that fails here is
 * never sent to the emulator. Codes are stored the way the libretro cheat files write them: lines joined with '+'.
 */
object CheatCodes {
    enum class Format(val label: String) {
        GAME_GENIE("Game Genie"),
        GAMESHARK_GB("GameShark"),
        CODEBREAKER("CodeBreaker"),
        GAMESHARK_AR("GameShark / Action Replay"),
        MIXED_GBA("GameShark / Action Replay + CodeBreaker"),
        ACTION_REPLAY_DS("Action Replay DS"),
    }

    sealed interface Check {
        data class Valid(val format: Format, val normalized: String, val lines: Int) : Check
        data class Invalid(val reason: String) : Check
    }

    private val HEX = Regex("[0-9A-Fa-f]+")
    /** Placeholder digits in "modifier" codes, e.g. "320232DA+000X" or "3300075C+00??": the user picks the value. */
    private val PLACEHOLDER = Regex("[Xx?]+")

    /**
     * Number of hex digits the user must supply for a modifier code (the longest placeholder run; 0 = an ordinary
     * code). Only placeholders in a code's last part (its value) count; anything else is left to [check] to reject.
     */
    fun placeholderDigits(code: String): Int {
        val last = tokens(code).lastOrNull() ?: return 0
        if (!last.any { it == 'X' || it == 'x' || it == '?' } || !last.all { HEX.matches(it.toString()) || it in "Xx?" }) return 0
        return PLACEHOLDER.findAll(last).maxOfOrNull { it.value.length } ?: 0
    }

    /** The code with every placeholder run replaced by [valueHex] (right-aligned, zero-padded to the run's length). */
    fun fill(code: String, valueHex: String): String =
        PLACEHOLDER.replace(code) { m -> valueHex.uppercase().takeLast(m.value.length).padStart(m.value.length, '0') }

    /** Splits a code into its parts: '+', spaces, new lines and commas all separate (as typed or as stored). */
    private fun tokens(code: String): List<String> = code.split('+', ' ', '\n', '\r', '\t', ',').map { it.trim() }.filter { it.isNotEmpty() }

    fun check(platform: HandheldPlatform, code: String): Check {
        // A modifier code is checked as it will be sent (with a value in place of its placeholders).
        val t = tokens(if (placeholderDigits(code) > 0) fill(code, "0") else code)
        if (t.isEmpty()) return Check.Invalid("The code is empty.")
        return when (platform) {
            HandheldPlatform.GB, HandheldPlatform.GBC -> checkGb(t)
            HandheldPlatform.GBA -> checkGba(t)
            HandheldPlatform.NDS -> checkNds(t)
        }
    }

    private fun checkGb(t: List<String>): Check {
        var genie = 0; var shark = 0
        for (p in t) {
            val bare = p.replace("-", "")
            when {
                p.contains('-') && (bare.length == 6 || bare.length == 9) && HEX.matches(bare) -> genie++
                p.length == 8 && HEX.matches(p) -> shark++
                else -> return Check.Invalid("“$p” is neither a Game Genie code (XXX-XXX or XXX-XXX-XXX) nor a GameShark code (8 hex digits).")
            }
        }
        if (genie > 0 && shark > 0) return Check.Invalid("Game Genie and GameShark parts can't be mixed in one cheat.")
        val fmt = if (genie > 0) Format.GAME_GENIE else Format.GAMESHARK_GB
        return Check.Valid(fmt, t.joinToString("+") { it.uppercase() }, t.size)
    }

    private fun checkGba(t: List<String>): Check {
        // Lines are an 8-digit address/command plus a 4-digit (CodeBreaker) or 8-digit (GameShark / Action Replay)
        // value. A code may also be given as single 16/12-digit lines.
        val lines = mutableListOf<String>()
        var i = 0
        var cb = 0; var gs = 0
        while (i < t.size) {
            val a = t[i]
            when {
                a.length == 16 && HEX.matches(a) -> { lines += "${a.take(8)} ${a.drop(8)}"; gs++; i++ }
                a.length == 12 && HEX.matches(a) -> { lines += "${a.take(8)} ${a.drop(8)}"; cb++; i++ }
                a.length == 8 && HEX.matches(a) -> {
                    val b = t.getOrNull(i + 1) ?: return Check.Invalid("“$a” has no value part after it.")
                    if (!HEX.matches(b) || (b.length != 4 && b.length != 8)) return Check.Invalid("“$a $b”: the value must be 4 or 8 hex digits.")
                    lines += "$a $b"; if (b.length == 4) cb++ else gs++
                    i += 2
                }
                else -> return Check.Invalid("“$a” isn't a Game Boy Advance code part (8 hex digits, then 4 or 8).")
            }
        }
        val fmt = when { cb > 0 && gs > 0 -> Format.MIXED_GBA; cb > 0 -> Format.CODEBREAKER; else -> Format.GAMESHARK_AR }
        return Check.Valid(fmt, lines.joinToString("+") { it.uppercase().replace(' ', '+') }, lines.size)
    }

    private fun checkNds(t: List<String>): Check {
        for (p in t) if (p.length != 8 || !HEX.matches(p)) return Check.Invalid("“$p”: Action Replay DS codes are pairs of 8-digit hex numbers.")
        if (t.size % 2 != 0) return Check.Invalid("Action Replay DS codes come in pairs of 8-digit numbers; this one has an odd count (${t.size}).")
        return Check.Valid(Format.ACTION_REPLAY_DS, t.joinToString("+") { it.uppercase() }, t.size / 2)
    }

    /**
     * Rough category for the cheat menu, from the cheat's description and group heading (the sources don't
     * categorise). Pokémon-specific words map to the Pokémon category only for Pokémon games.
     */
    fun categoryOf(description: String, group: String?, pokemonGame: Boolean): String {
        val s = (description + " " + (group ?: "")).lowercase()
        fun has(vararg w: String) = w.any { s.contains(it) }
        return when {
            has("master code", "must be on", "(m)", "enable code", "anti dma", "anti-dma") -> "Required"
            has("random battle", "random encounter", "encounter", "wild ", "repel") || (pokemonGame && has("shiny")) -> "Encounters"
            pokemonGame && has("pokemon", "pokémon", " ivs", " evs", " iv ", " ev ", "nature", "pokedex", "pokédex", "egg", "hatch", "friendship", "happiness", "species", "catch") -> "Pokémon"
            has("money", "gold", "cash", "coins", "rupee", "gil", "\$", "points", "score") -> "Money"
            has("item", "inventory", "ball", "potion", "candy", "candies", "key item", "bag", "weapon", "ammo") -> "Items"
            has("walk through", "walk thru", "no clip", "noclip", "teleport", "speed", "run ", "running", "jump", "moon jump", "fly") -> "Movement"
            has("battle", "hp", "health", "life", "lives", "pp", "damage", "attack", "defense", "exp", "experience", "level", "invincib", "one hit", "1 hit", "stat") -> "Battles & stats"
            has("unlock", "complete", "all levels", "stage", "badge", "story", "event", "flag", "clear") -> "Progression"
            else -> "Miscellaneous"
        }
    }
}
