package com.acecore.app.core.designsystem

import com.acecore.app.core.datastore.ThemePreset
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * One version (dark or light) of a theme, as ARGB longs. [backdrop] paints the screen behind everything (a base
 * gradient plus soft light "orbs"); themes with one get translucent glass cards over it ([glass]).
 */
data class Palette(
    val primary: Long,
    val secondary: Long,
    val accent: Long,
    val background: Long,
    val surface: Long,
    val glow: Long,
    val glowAlt: Long,
    val backdrop: Backdrop? = null,
    /** Card opacity on the backdrop (0–1) when glass is on. */
    val glassAlpha: Float = 1f,
) {
    val dark get() = Contrast.luminance(background) < 0.4
}

/** A vertical base gradient ([top] → [bottom]) with radial orbs (x, y, radius as fractions of the screen). */
data class Backdrop(val top: Long, val bottom: Long, val orbs: List<Orb>)
data class Orb(val color: Long, val x: Float, val y: Float, val radius: Float)

/** WCAG relative luminance / contrast ratio on ARGB longs (alpha ignored). */
object Contrast {
    private fun channel(c: Long, shift: Int): Double {
        val v = ((c shr shift) and 0xFF) / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    fun luminance(c: Long): Double = 0.2126 * channel(c, 16) + 0.7152 * channel(c, 8) + 0.0722 * channel(c, 0)
    fun ratio(a: Long, b: Long): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
    fun mix(a: Long, b: Long, t: Float): Long {
        fun ch(s: Int) = (((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t).toLong().coerceIn(0, 255)
        return (0xFFL shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
    /** Moves [c] toward black (or white) until it reaches [target] contrast against [bg]. */
    fun ensure(c: Long, bg: Long, target: Double): Long {
        if (ratio(c, bg) >= target) return c
        val toward = if (luminance(bg) > 0.4) 0xFF000000L else 0xFFFFFFFFL
        var t = 0f
        var out = c
        while (ratio(out, bg) < target && t < 1f) { t += 0.04f; out = mix(c, toward, t) }
        return out
    }
    /** Black or white, whichever reads better on a filled [c] (buttons, chips). */
    fun onFill(c: Long): Long = if (ratio(0xFF000000L, c) > ratio(0xFFFFFFFFL, c)) 0xFF000000L else 0xFFFFFFFFL

    /** Text colour the theme puts on [bg] (near-white on dark, near-black on light). */
    fun onColor(bg: Long): Long = if (luminance(bg) < 0.4) 0xFFF4F3FBL else 0xFF15122AL
}

object ThemePalettes {
    /**
     * The five premium themes, designed from the reference material: frosted aqua dashboard glass, violet
     * glow buttons on graphite, jade-lit smoky glass navigation, warm sandstone glass, and liquid "plasma" glass.
     */
    private val premium: Map<ThemePreset, Pair<Palette, Palette>> = mapOf(
        ThemePreset.AQUA_GLASS to (
            Palette(
                0xFF22D3EE, 0xFF8FD8E8, 0xFF5EEAD4, 0xFF071B26, 0xFF0F2A37, 0xFF22D3EE, 0xFF3B82F6,
                Backdrop(0xFF0B3446, 0xFF04111A, listOf(Orb(0xFF0E8FA0, 0.15f, 0.08f, 0.75f), Orb(0xFF1E5AA8, 0.95f, 0.55f, 0.6f))), 0.62f,
            ) to Palette(
                0xFF0E7490, 0xFF155E75, 0xFF0891B2, 0xFFE4F3F8, 0xFFFFFFFF, 0xFF06B6D4, 0xFF2563EB,
                Backdrop(0xFFC6E9F2, 0xFFEAF6FA, listOf(Orb(0xFF7DD3E8, 0.1f, 0.05f, 0.7f), Orb(0xFFA5C8F5, 0.9f, 0.6f, 0.55f))), 0.7f,
            )
        ),
        ThemePreset.VIOLET_PULSE to (
            Palette(
                0xFF8B5CF6, 0xFFC4B5FD, 0xFFA78BFA, 0xFF121016, 0xFF1C1A22, 0xFF8B5CF6, 0xFFF43F5E,
                Backdrop(0xFF1A1424, 0xFF0E0C12, listOf(Orb(0xFF6D28D9, 0.5f, 0.0f, 0.65f))), 0.78f,
            ) to Palette(
                0xFF6D28D9, 0xFF5B21B6, 0xFF7C3AED, 0xFFF5F3FA, 0xFFFFFFFF, 0xFF8B5CF6, 0xFFE11D48,
                Backdrop(0xFFEDE7FA, 0xFFF8F7FC, listOf(Orb(0xFFC4B5FD, 0.5f, 0.0f, 0.6f))), 0.8f,
            )
        ),
        ThemePreset.JADE_GLASS to (
            Palette(
                0xFF34D3B8, 0xFFA7C4BC, 0xFF5EEAD4, 0xFF0F1413, 0xFF19211F, 0xFF2DD4BF, 0xFF94A3B8,
                Backdrop(0xFF1D2826, 0xFF0A0E0D, listOf(Orb(0xFF0F766E, 0.5f, 0.95f, 0.6f), Orb(0xFF334155, 0.1f, 0.1f, 0.5f))), 0.68f,
            ) to Palette(
                0xFF0F766E, 0xFF3F5F58, 0xFF0D9488, 0xFFEEF3F1, 0xFFFFFFFF, 0xFF14B8A6, 0xFF64748B,
                Backdrop(0xFFE1EBE7, 0xFFF6F8F7, listOf(Orb(0xFF99E6D8, 0.5f, 0.95f, 0.55f))), 0.74f,
            )
        ),
        ThemePreset.SANDSTONE to (
            Palette(
                0xFFF26B3A, 0xFFE7C9A9, 0xFFFF8A4C, 0xFF17110C, 0xFF241A13, 0xFFF26B3A, 0xFFD4A373,
                Backdrop(0xFF3A2416, 0xFF140E0A, listOf(Orb(0xFFC2672E, 0.85f, 0.85f, 0.7f), Orb(0xFF5A3A22, 0.1f, 0.15f, 0.5f))), 0.7f,
            ) to Palette(
                0xFFC2410C, 0xFF7C4A2D, 0xFFEA580C, 0xFFF5EDE4, 0xFFFFFBF6, 0xFFF97316, 0xFFB45309,
                Backdrop(0xFFEAD7C3, 0xFFF9F2EA, listOf(Orb(0xFFF4B183, 0.85f, 0.85f, 0.65f))), 0.78f,
            )
        ),
        ThemePreset.PLASMA to (
            Palette(
                0xFF8B7CFF, 0xFFB8C1FF, 0xFF38E1FF, 0xFF07071A, 0xFF12122A, 0xFF7B5CFF, 0xFFFF4FB3,
                Backdrop(0xFF0C0B26, 0xFF05040F, listOf(Orb(0xFF00B4E6, 0.05f, 0.1f, 0.6f), Orb(0xFF7B2FF7, 0.95f, 0.35f, 0.65f), Orb(0xFFF72585, 0.3f, 1.0f, 0.55f))), 0.6f,
            ) to Palette(
                0xFF5B3DF5, 0xFF3F3A8C, 0xFF0891B2, 0xFFF1F0FF, 0xFFFFFFFF, 0xFF7C5CFF, 0xFFDB2777,
                Backdrop(0xFFE7E5FF, 0xFFF7F6FF, listOf(Orb(0xFFA5F3FC, 0.05f, 0.1f, 0.55f), Orb(0xFFC4B5FD, 0.95f, 0.35f, 0.6f), Orb(0xFFFBCFE8, 0.3f, 1.0f, 0.5f))), 0.7f,
            )
        ),
    )

    /** The classic themes' own versions (as before); the other half is derived with checked contrast. */
    private fun classic(preset: ThemePreset): Palette = when (preset) {
        ThemePreset.SIGNATURE_DARK -> Palette(0xFFE3202B, 0xFFC9CDD4, 0xFFFF5A4E, 0xFF0B0B0E, 0xFF16161B, 0xFFFF2A2A, 0xFFC9CDD4)
        ThemePreset.SIGNATURE_LIGHT -> Palette(0xFFC4141F, 0xFF4A4D57, 0xFFE3202B, 0xFFF4F4F6, 0xFFFFFFFF, 0xFFE3202B, 0xFF6B6F7A)
        ThemePreset.NEON_CLASSIC -> Palette(0xFFE5199C, 0xFF2E86FF, 0xFF2ED2FF, 0xFF06051A, 0xFF110C2A, 0xFFF03AD6, 0xFF2E86FF)
        ThemePreset.NEON_PURPLE -> Palette(0xFFB026FF, 0xFFE0B3FF, 0xFFD46BFF, 0xFF0A0414, 0xFF170A26, 0xFFB026FF, 0xFFFF2EC4)
        ThemePreset.CYBER_BLUE -> Palette(0xFF00B3FF, 0xFFA8E6FF, 0xFF00F0FF, 0xFF020A12, 0xFF0A1824, 0xFF00C8FF, 0xFF7A5CFF)
        ThemePreset.MINIMAL_DARK -> Palette(0xFFD0D3DA, 0xFF8A8F99, 0xFFFFFFFF, 0xFF0E0F11, 0xFF17181B, 0xFF3A3D44, 0xFF2A2C31)
        ThemePreset.AMOLED -> Palette(0xFFE3202B, 0xFFC9CDD4, 0xFFFF5A4E, 0xFF000000, 0xFF0B0B0D, 0xFFFF2A2A, 0xFFC9CDD4)
        ThemePreset.MIDNIGHT_BLUE -> Palette(0xFF4C8DFF, 0xFFB9C6E0, 0xFF7FB2FF, 0xFF070B18, 0xFF111833, 0xFF4C8DFF, 0xFF2ED2FF)
        ThemePreset.CRIMSON_RED -> Palette(0xFFFF1E3C, 0xFFFFB3BC, 0xFFFF6B7D, 0xFF140509, 0xFF230A10, 0xFFFF1E3C, 0xFFFF6B7D)
        ThemePreset.ROYAL_PURPLE -> Palette(0xFF9B5CFF, 0xFFD3C2F5, 0xFFC08BFF, 0xFF0D0816, 0xFF1A1129, 0xFF9B5CFF, 0xFFF03AD6)
        ThemePreset.EMERALD_GREEN -> Palette(0xFF1ED69B, 0xFFB5E8D6, 0xFF5CF0C0, 0xFF04120D, 0xFF0B2219, 0xFF1ED69B, 0xFF2ED2FF)
        ThemePreset.GOLD_EDITION -> Palette(0xFFE8B931, 0xFFF5E6B8, 0xFFFFD866, 0xFF0E0B05, 0xFF1C170B, 0xFFE8B931, 0xFFFFD866)
        else -> classic(ThemePreset.SIGNATURE_DARK)
    }

    /** A light version of a dark palette: pale tinted background, white cards, accents darkened to stay readable. */
    fun lightFrom(d: Palette): Palette {
        val bg = Contrast.mix(0xFFF6F6F8, d.glow, 0.05f)
        val surface = Contrast.mix(0xFFFFFFFF, d.glow, 0.015f)
        return Palette(
            primary = Contrast.ensure(d.primary, bg, 4.5), secondary = Contrast.ensure(d.secondary, bg, 4.5),
            accent = Contrast.ensure(d.accent, bg, 3.0), background = bg, surface = surface,
            glow = Contrast.ensure(d.glow, bg, 2.0), glowAlt = Contrast.ensure(d.glowAlt, bg, 2.0),
        )
    }

    /** A dark version of a light palette (used for custom light colours in dark mode). */
    fun darkFrom(l: Palette): Palette {
        val bg = Contrast.mix(0xFF0C0C10, l.glow, 0.06f)
        val surface = Contrast.mix(0xFF17171C, l.glow, 0.06f)
        return Palette(
            primary = Contrast.ensure(l.primary, bg, 4.5), secondary = Contrast.ensure(l.secondary, bg, 4.5),
            accent = Contrast.ensure(l.accent, bg, 3.0), background = bg, surface = surface,
            glow = Contrast.ensure(l.glow, bg, 2.0), glowAlt = Contrast.ensure(l.glowAlt, bg, 2.0),
        )
    }

    /** The palette of [preset] in dark or light mode (CUSTOM and DYNAMIC are handled by the caller). */
    fun of(preset: ThemePreset, dark: Boolean): Palette {
        premium[preset]?.let { (d, l) -> return if (dark) d else l }
        return when (preset) {
            ThemePreset.SIGNATURE_DARK, ThemePreset.SIGNATURE_LIGHT -> classic(if (dark) ThemePreset.SIGNATURE_DARK else ThemePreset.SIGNATURE_LIGHT)
            // AMOLED's light side is plain white with the same red.
            ThemePreset.AMOLED -> if (dark) classic(preset) else lightFrom(classic(preset)).copy(background = 0xFFFFFFFF, surface = 0xFFF7F7F8)
            else -> if (dark) classic(preset) else lightFrom(classic(preset))
        }
    }

    /** Custom colours in the requested mode: as entered when they match it, otherwise the derived counterpart. */
    fun custom(entered: Palette, dark: Boolean): Palette = when {
        entered.dark == dark -> entered
        dark -> darkFrom(entered)
        else -> lightFrom(entered)
    }
}
