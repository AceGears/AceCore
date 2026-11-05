package com.acecore.app.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.acecore.app.core.config.ConfigKey
import com.acecore.app.core.model.Engine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "acecore_settings")

/**
 * Theme families. Every theme has a dark and a light version ([ThemeMode] picks which). SIGNATURE_LIGHT is kept only so
 * older saved settings still load: it reads as AceArcade Steel in light mode.
 */
enum class ThemePreset(val label: String, val premium: Boolean = false) {
    // Premium themes: translucent "glass" cards over a coloured backdrop.
    AQUA_GLASS("Aqua Glass", true),
    VIOLET_PULSE("Violet Pulse", true),
    JADE_GLASS("Jade Glass", true),
    SANDSTONE("Sandstone", true),
    PLASMA("Plasma", true),
    SIGNATURE_DARK("AceArcade Steel"),
    SIGNATURE_LIGHT("AceArcade Steel Light"),
    NEON_CLASSIC("AceArcade Neon (classic)"),
    NEON_PURPLE("Neon Purple"),
    CYBER_BLUE("Cyber Blue"),
    MINIMAL_DARK("Minimal"),
    AMOLED("AMOLED Black"),
    MIDNIGHT_BLUE("Midnight Blue"),
    CRIMSON_RED("Crimson Red"),
    ROYAL_PURPLE("Royal Purple"),
    EMERALD_GREEN("Emerald Green"),
    GOLD_EDITION("Gold Edition"),
    DYNAMIC("Dynamic Material You"),
    CUSTOM("Custom"),
}

enum class LibraryLayout(val label: String) { GRID("Grid"), COMPACT("Compact grid"), LARGE("Large covers"), LIST("List"), CAROUSEL("Carousel") }

/** Cover shape in the library grid (width : height). */
enum class CoverAspect(val label: String, val ratio: Float) {
    PORTRAIT("3:4 Portrait", 0.75f),
    TALL("2:3 Tall", 0.6667f),
    SQUARE("1:1 Square", 1f),
    LANDSCAPE("4:3 Landscape", 1.3333f),
    WIDE("16:9 Wide", 1.7778f),
}

enum class CategoryBarStyle(val label: String) {
    TABS("Tabs"), PILLS("Pills"),
    // Bottom docks (the glass navigation references).
    DOCK("Dock: bubble"), WAVE("Dock: glow wave"), SPOTLIGHT("Dock: spotlight"), DROP("Dock: liquid drop"),
}

enum class LibrarySort(val label: String) {
    ALPHABETICAL("Alphabetical"), RECENTLY_ADDED("Recently added"), RECENTLY_PLAYED("Recently played"),
    MOST_PLAYED("Most played"), ENGINE("Engine"), CUSTOM("Custom order"),
}

/** What tapping a game in the library does. */
enum class TapAction(val label: String) { PLAY("Play the game"), DETAILS("Open its details (double-tap plays)") }

/** Light or dark version of the theme. */
enum class ThemeMode(val label: String) { SYSTEM("Follow system"), LIGHT("Light"), DARK("Dark") }

/** How selection indicators move: a liquid pill that stretches toward its target, or a plain slide. */
enum class MotionStyle(val label: String) { LIQUID("Liquid"), CLASSIC("Classic") }

enum class NavStyle(val label: String) { LABELED("Icons and labels"), ICONS("Icons only") }

data class ThemeSettings(
    val preset: ThemePreset = ThemePreset.SIGNATURE_DARK,
    /** When true, Signature Dark/Light follow the system dark-mode setting automatically. */
    val followSystem: Boolean = true,
    val primary: Long = 0xFFE3202BL,
    val secondary: Long = 0xFFC9CDD4L,
    val accent: Long = 0xFFFF5A4EL,
    val background: Long = 0xFF0B0B0EL,
    val surface: Long = 0xFF16161BL,
    val glowColor: Long = 0xFFFF2A2AL,
    val surfaceOpacity: Int = 100,
    val gradientIntensity: Int = 60,
    val glowIntensity: Int = 45,
    val borderThickness: Int = 1,
    val cornerRadius: Int = 16,
    val textScale: Int = 100,
    val animationSpeed: Int = 100,
    val navStyle: NavStyle = NavStyle.LABELED,
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val motion: MotionStyle = MotionStyle.LIQUID,
    /** Shake-and-dissolve when games or categories are removed. */
    val deleteEffect: Boolean = true,
    /** Translucent glass cards on themes that have a backdrop (off: solid cards on the same colours). */
    val glass: Boolean = true,
)

data class LibrarySettings(
    val layout: LibraryLayout = LibraryLayout.GRID,
    val coverWidth: Int = 120,
    val spacing: Int = 12,
    val showTitles: Boolean = true,
    val coverRadius: Int = 14,
    val coverBorder: Boolean = true,
    val coverGlow: Boolean = true,
    val sort: LibrarySort = LibrarySort.RECENTLY_PLAYED,
    val showHidden: Boolean = false,
    /** Fixed number of grid columns; 0 = automatic from [coverWidth]. */
    val columns: Int = 0,
    val aspect: CoverAspect = CoverAspect.PORTRAIT,
    /** Cover border colour (ARGB); 0 = follow the theme's glow colour. */
    val borderColor: Long = 0L,
    /** Selected category on the home screen; [ALL_GAMES] = every game. */
    val selectedCategory: Long = ALL_GAMES,
    val showHiddenCategories: Boolean = false,
    val categoryBar: CategoryBarStyle = CategoryBarStyle.TABS,
    val showCategoryCounts: Boolean = true,
    /** Show an "All games" tab. Off: the library shows the user's categories, plus "Other" for games in none. */
    val showAllGames: Boolean = false,
    /** Download box art for identified Game Boy / GBA / DS ROMs that have none (libretro thumbnail server). Off by default. */
    val onlineCovers: Boolean = false,
    /** Swipe left / right over the games to switch categories. */
    val swipeCategories: Boolean = true,
    /** Pinch the grid to make covers bigger or smaller. */
    val pinchGrid: Boolean = true,
    val tapAction: TapAction = TapAction.PLAY,
) {
    companion object {
        const val ALL_GAMES = -1L
        /** The automatic tab with the games that are in no category. */
        const val OTHER = -2L
    }
}

data class AppSettings(
    val theme: ThemeSettings = ThemeSettings(),
    val library: LibrarySettings = LibrarySettings(),
    val reduceMotion: Boolean = false,
    val webDebugging: Boolean = false,
    /** Global layer of the layered config (ConfigKey.id -> value), only keys the user changed. */
    val globalConfig: Map<String, String> = emptyMap(),
    /** Engine layer: engine -> (ConfigKey.id -> value). */
    val engineConfig: Map<Engine, Map<String, String>> = emptyMap(),
    /** Runtime id name -> last time a game actually ran with it on this device. */
    val runtimeVerifiedAt: Map<String, Long> = emptyMap(),
    /** When the built-in engine test last passed on this device (Settings › Game engines › Test this engine). */
    val runtimeSelfTestAt: Map<String, Long> = emptyMap(),
)

class SettingsRepository(private val context: Context) {

    private object K {
        val preset = stringPreferencesKey("theme.preset")
        val followSystem = booleanPreferencesKey("theme.followSystem")
        val primary = longPreferencesKey("theme.primary")
        val secondary = longPreferencesKey("theme.secondary")
        val accent = longPreferencesKey("theme.accent")
        val background = longPreferencesKey("theme.background")
        val surface = longPreferencesKey("theme.surface")
        val glowColor = longPreferencesKey("theme.glowColor")
        val surfaceOpacity = intPreferencesKey("theme.surfaceOpacity")
        val gradient = intPreferencesKey("theme.gradient")
        val glow = intPreferencesKey("theme.glow")
        val border = intPreferencesKey("theme.border")
        val corner = intPreferencesKey("theme.corner")
        val textScale = intPreferencesKey("theme.textScale")
        val animSpeed = intPreferencesKey("theme.animSpeed")
        val navStyle = stringPreferencesKey("theme.navStyle")
        val themeMode = stringPreferencesKey("theme.mode")
        val motion = stringPreferencesKey("theme.motion")
        val deleteEffect = booleanPreferencesKey("theme.deleteEffect")
        val glass = booleanPreferencesKey("theme.glass")

        val layout = stringPreferencesKey("library.layout")
        val coverWidth = intPreferencesKey("library.coverWidth")
        val spacing = intPreferencesKey("library.spacing")
        val showTitles = booleanPreferencesKey("library.showTitles")
        val coverRadius = intPreferencesKey("library.coverRadius")
        val coverBorder = booleanPreferencesKey("library.coverBorder")
        val coverGlow = booleanPreferencesKey("library.coverGlow")
        val sort = stringPreferencesKey("library.sort")
        val showHidden = booleanPreferencesKey("library.showHidden")
        val columns = intPreferencesKey("library.columns")
        val aspect = stringPreferencesKey("library.aspect")
        val borderColor = longPreferencesKey("library.borderColor")
        val selectedCategory = longPreferencesKey("library.selectedCategory")
        val showHiddenCategories = booleanPreferencesKey("library.showHiddenCategories")
        val categoryBar = stringPreferencesKey("library.categoryBar")
        val showCategoryCounts = booleanPreferencesKey("library.showCategoryCounts")
        val showAllGames = booleanPreferencesKey("library.showAllGames")
        val onlineCovers = booleanPreferencesKey("library.onlineCovers")
        val swipeCategories = booleanPreferencesKey("library.swipeCategories")
        val pinchGrid = booleanPreferencesKey("library.pinchGrid")
        val tapAction = stringPreferencesKey("library.tapAction")

        val reduceMotion = booleanPreferencesKey("app.reduceMotion")
        val webDebugging = booleanPreferencesKey("app.webDebugging")

        fun runtime(id: String) = longPreferencesKey("runtime.$id.verifiedAt")
        fun global(key: ConfigKey) = stringPreferencesKey("config.${key.id}")
        fun engine(engine: Engine, key: ConfigKey) = stringPreferencesKey("engine.${engine.name}.${key.id}")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p -> read(p) }

    suspend fun current(): AppSettings = settings.first()

    /**
     * Mode for settings saved before themes had light and dark versions: Steel with "follow system" kept following it,
     * Steel Light stays light, and every other theme (all dark then) stays dark.
     */
    private fun legacyMode(preset: ThemePreset, followSystem: Boolean): ThemeMode = when {
        preset == ThemePreset.SIGNATURE_LIGHT -> if (followSystem) ThemeMode.SYSTEM else ThemeMode.LIGHT
        preset == ThemePreset.SIGNATURE_DARK -> if (followSystem) ThemeMode.SYSTEM else ThemeMode.DARK
        preset == ThemePreset.DYNAMIC -> ThemeMode.SYSTEM
        else -> ThemeMode.DARK
    }

    private fun read(p: Preferences): AppSettings {
        val d = ThemeSettings()
        val theme = ThemeSettings(
            preset = enumOr(p[K.preset], d.preset),
            followSystem = p[K.followSystem] ?: d.followSystem,
            primary = p[K.primary] ?: d.primary,
            secondary = p[K.secondary] ?: d.secondary,
            accent = p[K.accent] ?: d.accent,
            background = p[K.background] ?: d.background,
            surface = p[K.surface] ?: d.surface,
            glowColor = p[K.glowColor] ?: d.glowColor,
            surfaceOpacity = p[K.surfaceOpacity] ?: d.surfaceOpacity,
            gradientIntensity = p[K.gradient] ?: d.gradientIntensity,
            glowIntensity = p[K.glow] ?: d.glowIntensity,
            borderThickness = p[K.border] ?: d.borderThickness,
            cornerRadius = p[K.corner] ?: d.cornerRadius,
            textScale = p[K.textScale] ?: d.textScale,
            animationSpeed = p[K.animSpeed] ?: d.animationSpeed,
            navStyle = enumOr(p[K.navStyle], d.navStyle),
            mode = p[K.themeMode]?.let { enumOr(it, d.mode) } ?: legacyMode(enumOr(p[K.preset], d.preset), p[K.followSystem] ?: true),
            motion = enumOr(p[K.motion], d.motion),
            deleteEffect = p[K.deleteEffect] ?: d.deleteEffect,
            glass = p[K.glass] ?: d.glass,
        ).let { if (it.preset == ThemePreset.SIGNATURE_LIGHT) it.copy(preset = ThemePreset.SIGNATURE_DARK) else it }
        val l = LibrarySettings()
        val library = LibrarySettings(
            layout = enumOr(p[K.layout], l.layout),
            coverWidth = p[K.coverWidth] ?: l.coverWidth,
            spacing = p[K.spacing] ?: l.spacing,
            showTitles = p[K.showTitles] ?: l.showTitles,
            coverRadius = p[K.coverRadius] ?: l.coverRadius,
            coverBorder = p[K.coverBorder] ?: l.coverBorder,
            coverGlow = p[K.coverGlow] ?: l.coverGlow,
            sort = enumOr(p[K.sort], l.sort),
            showHidden = p[K.showHidden] ?: l.showHidden,
            columns = p[K.columns] ?: l.columns,
            aspect = enumOr(p[K.aspect], l.aspect),
            borderColor = p[K.borderColor] ?: l.borderColor,
            selectedCategory = p[K.selectedCategory] ?: l.selectedCategory,
            showHiddenCategories = p[K.showHiddenCategories] ?: l.showHiddenCategories,
            categoryBar = enumOr(p[K.categoryBar], l.categoryBar),
            showCategoryCounts = p[K.showCategoryCounts] ?: l.showCategoryCounts,
            showAllGames = p[K.showAllGames] ?: l.showAllGames,
            onlineCovers = p[K.onlineCovers] ?: l.onlineCovers,
            swipeCategories = p[K.swipeCategories] ?: l.swipeCategories,
            pinchGrid = p[K.pinchGrid] ?: l.pinchGrid,
            tapAction = enumOr(p[K.tapAction], l.tapAction),
        )
        val global = ConfigKey.entries.mapNotNull { k -> p[K.global(k)]?.let { k.id to it } }.toMap()
        val engines = Engine.entries.associateWith { e ->
            ConfigKey.entries.mapNotNull { k -> p[K.engine(e, k)]?.let { k.id to it } }.toMap()
        }.filterValues { it.isNotEmpty() }
        return AppSettings(
            theme = theme, library = library,
            reduceMotion = p[K.reduceMotion] ?: false,
            webDebugging = p[K.webDebugging] ?: false,
            globalConfig = global, engineConfig = engines,
            runtimeVerifiedAt = p.asMap().entries
                .filter { it.key.name.startsWith("runtime.") && it.key.name.endsWith(".verifiedAt") }
                .mapNotNull { e -> (e.value as? Long)?.let { e.key.name.removePrefix("runtime.").removeSuffix(".verifiedAt") to it } }
                .toMap(),
            runtimeSelfTestAt = p.asMap().entries
                .filter { it.key.name.startsWith("runtime.") && it.key.name.endsWith(".selftestAt") }
                .mapNotNull { e -> (e.value as? Long)?.let { e.key.name.removePrefix("runtime.").removeSuffix(".selftestAt") to it } }
                .toMap(),
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: fallback

    // ------------------------------------------------------------------ writes

    suspend fun updateTheme(transform: (ThemeSettings) -> ThemeSettings) {
        context.dataStore.edit { p ->
            val t = transform(read(p).theme)
            p[K.preset] = t.preset.name; p[K.followSystem] = t.followSystem
            p[K.primary] = t.primary; p[K.secondary] = t.secondary; p[K.accent] = t.accent
            p[K.background] = t.background; p[K.surface] = t.surface; p[K.glowColor] = t.glowColor
            p[K.surfaceOpacity] = t.surfaceOpacity; p[K.gradient] = t.gradientIntensity; p[K.glow] = t.glowIntensity
            p[K.border] = t.borderThickness; p[K.corner] = t.cornerRadius; p[K.textScale] = t.textScale
            p[K.animSpeed] = t.animationSpeed; p[K.navStyle] = t.navStyle.name
            p[K.themeMode] = t.mode.name; p[K.motion] = t.motion.name; p[K.deleteEffect] = t.deleteEffect; p[K.glass] = t.glass
        }
    }

    suspend fun resetTheme() {
        context.dataStore.edit { p -> removeWhere(p) { it.startsWith("theme.") } }
    }

    suspend fun updateLibrary(transform: (LibrarySettings) -> LibrarySettings) {
        context.dataStore.edit { p ->
            val l = transform(read(p).library)
            p[K.layout] = l.layout.name; p[K.coverWidth] = l.coverWidth; p[K.spacing] = l.spacing
            p[K.showTitles] = l.showTitles; p[K.coverRadius] = l.coverRadius; p[K.coverBorder] = l.coverBorder
            p[K.coverGlow] = l.coverGlow; p[K.sort] = l.sort.name; p[K.showHidden] = l.showHidden
            p[K.columns] = l.columns; p[K.aspect] = l.aspect.name; p[K.borderColor] = l.borderColor
            p[K.selectedCategory] = l.selectedCategory; p[K.showHiddenCategories] = l.showHiddenCategories
            p[K.categoryBar] = l.categoryBar.name; p[K.showCategoryCounts] = l.showCategoryCounts
            p[K.showAllGames] = l.showAllGames
            p[K.onlineCovers] = l.onlineCovers
            p[K.swipeCategories] = l.swipeCategories; p[K.pinchGrid] = l.pinchGrid; p[K.tapAction] = l.tapAction.name
        }
    }

    suspend fun markRuntimeSelfTest(id: com.acecore.app.runtime.RuntimeId) {
        context.dataStore.edit { it[androidx.datastore.preferences.core.longPreferencesKey("runtime.${id.name}.selftestAt")] = System.currentTimeMillis() }
    }

    suspend fun markRuntimeVerified(id: com.acecore.app.runtime.RuntimeId) {
        context.dataStore.edit { it[K.runtime(id.name)] = System.currentTimeMillis() }
    }

    suspend fun setReduceMotion(v: Boolean) { context.dataStore.edit { it[K.reduceMotion] = v } }
    suspend fun setWebDebugging(v: Boolean) { context.dataStore.edit { it[K.webDebugging] = v } }

    /** value = null removes the override at that layer (restores inheritance). */
    suspend fun setGlobalConfig(key: ConfigKey, value: String?) {
        context.dataStore.edit { p ->
            if (value == null) { p.remove(K.global(key)) } else { p[K.global(key)] = value }
        }
    }

    suspend fun setEngineConfig(engine: Engine, key: ConfigKey, value: String?) {
        context.dataStore.edit { p ->
            if (value == null) { p.remove(K.engine(engine, key)) } else { p[K.engine(engine, key)] = value }
        }
    }

    suspend fun resetSection(prefix: String) { context.dataStore.edit { p -> removeWhere(p) { it.startsWith(prefix) } } }

    /** Raw export/import for library backup. */
    suspend fun exportRaw(): Map<String, String> = context.dataStore.data.first().asMap()
        .mapKeys { it.key.name }.mapValues { it.value.toString() }

    suspend fun importRaw(values: Map<String, String>) { context.dataStore.edit { p ->
        for ((k, v) in values) {
            when {
                k.startsWith("config.") || k.startsWith("engine.") || k == K.preset.name || k == K.layout.name ||
                    k == K.sort.name || k == K.navStyle.name || k == K.themeMode.name || k == K.motion.name || k == K.aspect.name || k == K.categoryBar.name || k == K.tapAction.name -> p[stringPreferencesKey(k)] = v
                k.startsWith("runtime.") -> Unit // device-specific, never restored onto another device
                k == "library.selectedCategory" -> Unit // category ids differ between databases
                v == "true" || v == "false" -> p[booleanPreferencesKey(k)] = v.toBoolean()
                k in LONG_KEYS -> v.toLongOrNull()?.let { p[longPreferencesKey(k)] = it }
                else -> v.toIntOrNull()?.let { p[intPreferencesKey(k)] = it }
            }
        }
    } }

    private fun removeWhere(p: MutablePreferences, predicate: (String) -> Boolean) {
        p.asMap().keys.filter { predicate(it.name) }.forEach { p.remove(it) }
    }

    private companion object {
        val LONG_KEYS = setOf(
            "theme.primary", "theme.secondary", "theme.accent", "theme.background", "theme.surface", "theme.glowColor",
            "library.borderColor", "library.selectedCategory",
        )
    }
}
