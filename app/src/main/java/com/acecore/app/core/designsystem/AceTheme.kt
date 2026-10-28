package com.acecore.app.core.designsystem

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.acecore.app.core.datastore.ThemePreset
import com.acecore.app.core.datastore.ThemeSettings

/** Extra design tokens that Material 3 has no slot for. */
@Immutable
data class AceTokens(
    val glow: Color,
    val glowIntensity: Float,
    val gradientIntensity: Float,
    val borderWidth: Dp,
    val corner: Dp,
    val surfaceAlpha: Float,
    val animationScale: Float,
    val reduceMotion: Boolean,
    val isDark: Boolean,
    val chrome: Color,
)

val LocalAceTokens = staticCompositionLocalOf {
    AceTokens(Color(0xFFE3132B), 0.45f, 0.6f, 1.dp, 16.dp, 1f, 1f, false, true, Color(0xFFC9CDD3))
}

object AceTheme {
    val tokens: AceTokens @Composable get() = LocalAceTokens.current
}

/** Brand palette extracted from the supplied AceCore logo and icon: crimson, chrome silver, near-black. */
object AcePalette {
    val Crimson = Color(0xFFE3132B)
    val CrimsonDeep = Color(0xFF8E0716)
    val CrimsonGlow = Color(0xFFFF4D5E)
    val Chrome = Color(0xFFC9CDD3)
    val ChromeBright = Color(0xFFF1F2F4)
    val Gunmetal = Color(0xFF2A2B31)
    val Carbon = Color(0xFF0B0B0E)
}

private data class PresetSeed(val primary: Long, val secondary: Long, val accent: Long, val background: Long, val surface: Long, val glow: Long)

private fun seedFor(preset: ThemePreset): PresetSeed = when (preset) {
    ThemePreset.SIGNATURE_DARK -> PresetSeed(0xFFE3132B, 0xFFC9CDD3, 0xFFFF4D5E, 0xFF0B0B0E, 0xFF16161B, 0xFFE3132B)
    ThemePreset.SIGNATURE_LIGHT -> PresetSeed(0xFFC8102E, 0xFF5B5F66, 0xFFE3132B, 0xFFF5F5F7, 0xFFFFFFFF, 0xFFE3132B)
    ThemePreset.AMOLED -> PresetSeed(0xFFE3132B, 0xFFB8BCC2, 0xFFFF4D5E, 0xFF000000, 0xFF0A0A0B, 0xFFE3132B)
    ThemePreset.MIDNIGHT_BLUE -> PresetSeed(0xFF4C8DFF, 0xFFB9C6E0, 0xFF7FB2FF, 0xFF070B18, 0xFF111833, 0xFF4C8DFF)
    ThemePreset.CRIMSON_RED -> PresetSeed(0xFFFF1E3C, 0xFFFFB3BC, 0xFFFF6B7D, 0xFF140509, 0xFF230A10, 0xFFFF1E3C)
    ThemePreset.ROYAL_PURPLE -> PresetSeed(0xFF9B5CFF, 0xFFD3C2F5, 0xFFC08BFF, 0xFF0D0816, 0xFF1A1129, 0xFF9B5CFF)
    ThemePreset.EMERALD_GREEN -> PresetSeed(0xFF1ED69B, 0xFFB5E8D6, 0xFF5CF0C0, 0xFF04120D, 0xFF0B2219, 0xFF1ED69B)
    ThemePreset.GOLD_EDITION -> PresetSeed(0xFFE8B931, 0xFFF5E6B8, 0xFFFFD866, 0xFF0E0B05, 0xFF1C170B, 0xFFE8B931)
    ThemePreset.DYNAMIC, ThemePreset.CUSTOM -> seedFor(ThemePreset.SIGNATURE_DARK)
}

/** ARGB long (0xAARRGGBB) -> Compose Color. */
fun Long.toColor(): Color = Color(this.toInt())

/** Accent (glow) colour for a theme without composing it; used to tint the game overlay. */
fun accentArgbFor(theme: ThemeSettings): Long = when (theme.preset) {
    ThemePreset.CUSTOM -> theme.glowColor
    else -> seedFor(theme.preset).glow
}

/** Compose Color -> ARGB long for persistence. */
fun Color.toArgbLong(): Long = (((alpha * 255f + 0.5f).toInt() shl 24) or ((red * 255f + 0.5f).toInt() shl 16) or
    ((green * 255f + 0.5f).toInt() shl 8) or (blue * 255f + 0.5f).toInt()).toLong() and 0xFFFFFFFFL

private fun schemeFrom(primary: Color, secondary: Color, accent: Color, background: Color, surface: Color, surfaceAlpha: Float): ColorScheme {
    val dark = background.luminance() < 0.4f
    val onBg = if (dark) Color(0xFFF1F2F4) else Color(0xFF141418)
    val onPrimary = if (primary.luminance() > 0.5f) Color.Black else Color.White
    val surf = surface.copy(alpha = 1f)
    val variant = lerpColor(surf, onBg, if (dark) 0.08f else 0.05f)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = lerpColor(background, primary, 0.28f),
        onPrimaryContainer = onBg,
        secondary = secondary,
        onSecondary = if (secondary.luminance() > 0.5f) Color.Black else Color.White,
        secondaryContainer = lerpColor(background, secondary, 0.18f),
        onSecondaryContainer = onBg,
        tertiary = accent,
        onTertiary = if (accent.luminance() > 0.5f) Color.Black else Color.White,
        background = background,
        onBackground = onBg,
        surface = surf.copy(alpha = surfaceAlpha).compositeOver(background),
        onSurface = onBg,
        surfaceVariant = variant,
        onSurfaceVariant = onBg.copy(alpha = 0.72f).compositeOver(variant),
        surfaceContainerLowest = lerpColor(background, surf, 0.4f),
        surfaceContainerLow = lerpColor(background, surf, 0.7f),
        surfaceContainer = surf,
        surfaceContainerHigh = lerpColor(surf, onBg, 0.05f),
        surfaceContainerHighest = lerpColor(surf, onBg, 0.09f),
        outline = onBg.copy(alpha = 0.28f).compositeOver(background),
        outlineVariant = onBg.copy(alpha = 0.12f).compositeOver(background),
        inverseSurface = onBg,
        inverseOnSurface = background,
    )
}

fun lerpColor(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)

private val baseTypography = Typography()

private fun aceTypography(): Typography {
    val display = FontFamily.SansSerif
    fun TextStyle.tight(weight: FontWeight, tracking: Float = 0f) = copy(fontFamily = display, fontWeight = weight, letterSpacing = tracking.sp)
    return baseTypography.copy(
        displaySmall = baseTypography.displaySmall.tight(FontWeight.Black, -0.5f),
        headlineLarge = baseTypography.headlineLarge.tight(FontWeight.ExtraBold, -0.3f),
        headlineMedium = baseTypography.headlineMedium.tight(FontWeight.ExtraBold, -0.2f),
        headlineSmall = baseTypography.headlineSmall.tight(FontWeight.Bold),
        titleLarge = baseTypography.titleLarge.tight(FontWeight.Bold),
        titleMedium = baseTypography.titleMedium.tight(FontWeight.SemiBold, 0.1f),
        titleSmall = baseTypography.titleSmall.tight(FontWeight.SemiBold, 0.1f),
        labelLarge = baseTypography.labelLarge.tight(FontWeight.SemiBold, 0.4f),
        labelMedium = baseTypography.labelMedium.tight(FontWeight.SemiBold, 0.5f),
        labelSmall = baseTypography.labelSmall.tight(FontWeight.Bold, 0.8f),
    )
}

/** True when the OS asks for reduced motion (animator duration scale set to 0). */
@Composable
fun systemReducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
}

@Composable
fun AceCoreTheme(settings: ThemeSettings, reduceMotion: Boolean = false, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val surfaceAlpha = settings.surfaceOpacity.coerceIn(40, 100) / 100f

    val effectivePreset = when {
        settings.followSystem && settings.preset == ThemePreset.SIGNATURE_DARK && !systemDark -> ThemePreset.SIGNATURE_LIGHT
        settings.followSystem && settings.preset == ThemePreset.SIGNATURE_LIGHT && systemDark -> ThemePreset.SIGNATURE_DARK
        else -> settings.preset
    }

    val glowColor: Color
    val scheme: ColorScheme = when {
        effectivePreset == ThemePreset.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val s = if (systemDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            glowColor = s.primary
            s
        }
        effectivePreset == ThemePreset.CUSTOM -> {
            glowColor = settings.glowColor.toColor()
            schemeFrom(settings.primary.toColor(), settings.secondary.toColor(), settings.accent.toColor(),
                settings.background.toColor(), settings.surface.toColor(), surfaceAlpha)
        }
        else -> {
            val seed = seedFor(effectivePreset)
            glowColor = Color(seed.glow.toInt())
            schemeFrom(Color(seed.primary.toInt()), Color(seed.secondary.toInt()), Color(seed.accent.toInt()),
                Color(seed.background.toInt()), Color(seed.surface.toInt()), surfaceAlpha)
        }
    }
    val isDark = scheme.background.luminance() < 0.4f
    val sysReduce = systemReducedMotion()
    val tokens = AceTokens(
        glow = glowColor,
        glowIntensity = settings.glowIntensity.coerceIn(0, 100) / 100f,
        gradientIntensity = settings.gradientIntensity.coerceIn(0, 100) / 100f,
        borderWidth = settings.borderThickness.coerceIn(0, 4).dp,
        corner = settings.cornerRadius.coerceIn(0, 32).dp,
        surfaceAlpha = surfaceAlpha,
        animationScale = if (reduceMotion || sysReduce) 0f else 100f / settings.animationSpeed.coerceIn(25, 300),
        reduceMotion = reduceMotion || sysReduce,
        isDark = isDark,
        chrome = if (isDark) AcePalette.Chrome else AcePalette.Gunmetal,
    )
    val c = tokens.corner
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(c * 0.35f), small = RoundedCornerShape(c * 0.6f),
        medium = RoundedCornerShape(c), large = RoundedCornerShape(c * 1.4f), extraLarge = RoundedCornerShape(c * 1.8f),
    )
    val density = LocalDensity.current
    val scaled = Density(density.density, density.fontScale * settings.textScale.coerceIn(80, 150) / 100f)
    CompositionLocalProvider(LocalAceTokens provides tokens, LocalDensity provides scaled) {
        MaterialTheme(colorScheme = scheme, typography = aceTypography(), shapes = shapes, content = content)
    }
}

/** Duration helper honouring the user's animation speed and reduce-motion preferences. */
@Composable
fun aceDuration(baseMillis: Int): Int = (baseMillis * AceTheme.tokens.animationScale).toInt()

val Dp.half get() = this / 2
