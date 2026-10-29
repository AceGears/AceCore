package com.acecore.app.runtime

import android.content.Context
import android.content.Intent
import com.acecore.app.core.config.ButtonMap
import com.acecore.app.core.config.ConfigKey
import com.acecore.app.core.install.GameStorage
import com.acecore.app.core.model.Engine
import com.acecore.app.runtime.godot.GodotLaunchSpec
import com.acecore.app.runtime.rgss.RgssActivity
import com.acecore.app.runtime.rgss.RgssLaunchSpec
import com.acecore.app.runtime.rgss.RgssRuntime
import com.acecore.app.runtime.web.GameActivity
import com.acecore.app.runtime.web.GameLaunchSpec
import org.godotengine.godot.GodotActivity
import java.io.File

/**
 * "Test this engine" (Settings › Game engines): runs a small test game built into AceArcade with the real engine on
 * this device and asks the player to hold Down on the on-screen D-pad. The test game writes `selftest_ok` only after
 * the engine has started, drawn frames and the held direction has moved its character six steps, so a pass means the
 * runtime, rendering and input really work here. The test games are AceArcade's own (app/src/main/assets/selftest).
 */
object SelfTest {
    private const val ASSETS = "selftest"

    /** Game ids for the test sessions (never used by library games). */
    private fun gameId(id: RuntimeId) = 9_100_000L + id.ordinal

    fun dir(context: Context, id: RuntimeId) = File(context.filesDir, "selftest/${id.name.lowercase()}")
    private fun okFile(context: Context, id: RuntimeId) = File(dir(context, id), "selftest_ok")

    private fun assetFolder(id: RuntimeId): String? = when (id) {
        RuntimeId.WEB -> "web"
        RuntimeId.RGSS_MKXPZ -> "rgss"
        RuntimeId.GODOT -> "godot"
        else -> null
    }

    /** Whether this build can test [id]: the runtime is bundled and its test game is inside the APK. */
    fun available(context: Context, id: RuntimeId): Boolean {
        val folder = assetFolder(id) ?: return false
        if (!RuntimeRegistry.info(id).bundled) return false
        return runCatching { context.assets.list("$ASSETS/$folder").orEmpty().isNotEmpty() }.getOrDefault(false)
    }

    /** True when the last test of [id] passed (and removes the marker so it is counted once). */
    fun consumePass(context: Context, id: RuntimeId): Boolean {
        val f = okFile(context, id)
        if (!f.exists()) return false
        f.delete()
        return true
    }

    /** Copies the test game to a fresh folder and returns the Intent that starts it, or null if unavailable. */
    fun intent(context: Context, id: RuntimeId, accent: Long): Intent? {
        val folder = assetFolder(id) ?: return null
        if (!available(context, id)) return null
        val target = dir(context, id).apply { deleteRecursively(); mkdirs() }
        copyAssets(context, "$ASSETS/$folder", target)
        val config = mapOf(ConfigKey.GAMEPAD_MODE.id to "always")
        val now = System.currentTimeMillis()
        return when (id) {
            RuntimeId.WEB -> {
                val spec = GameLaunchSpec(
                    gameId = gameId(id), title = "Engine test · Web runtime", engine = Engine.HTML5.name,
                    treeUri = "file://" + target.absolutePath, rootDocId = target.absolutePath, contentRoot = "", entry = "index.html",
                    config = config + (ConfigKey.BUTTON_MAP.id to ButtonMap.DEFAULT_ENCODED), accentArgb = accent, forceReindex = true,
                    launchRequestedAt = now,
                )
                Intent(context, GameActivity::class.java).putExtra(GameLaunchSpec.EXTRA, spec.toJson())
                    .putExtra(GameActivity.EXTRA_SELFTEST_DIR, target.absolutePath)
            }
            RuntimeId.RGSS_MKXPZ -> {
                RgssRuntime.killStaleProcess(context)
                val dataDir = GameStorage.dataDir(context, gameId(id)).apply { deleteRecursively() }
                RgssRuntime.writeLaunchFiles(context, dataDir, Engine.RPG_MAKER_XP, config, helper = true, cheatsEnabled = false,
                    cheatFlags = emptyMap(), launchRequestedAt = now)
                val spec = RgssLaunchSpec(
                    gameId = gameId(id), title = "Engine test · RGSS", engine = Engine.RPG_MAKER_XP.name, gamePath = target.absolutePath,
                    dataPath = dataDir.absolutePath, config = config, accentArgb = accent, direct = true, helper = true,
                    cheatsEnabled = false, launchRequestedAt = now,
                )
                Intent(context, RgssActivity::class.java).putExtra(RgssLaunchSpec.EXTRA, spec.toJson())
            }
            RuntimeId.GODOT -> {
                val pack = target.listFiles().orEmpty().firstOrNull { it.name.endsWith(".pck") } ?: return null
                val helper = RgssRuntime.helperDir(GameStorage.dataDir(context, gameId(id))).apply { mkdirs() }
                val spec = GodotLaunchSpec(gameId(id), "Engine test · Godot", helper.absolutePath, ButtonMap.GODOT_DEFAULT_ENCODED, accent, gamepadMode = "always")
                val intent = GodotLaunchSpec.intent(context, spec, pack, File(helper, "godot.log"))
                val args = intent.getStringArrayExtra(GodotActivity.EXTRA_COMMAND_LINE_PARAMS).orEmpty() +
                    arrayOf("--", "--selftest=" + target.absolutePath)
                intent.putExtra(GodotActivity.EXTRA_COMMAND_LINE_PARAMS, args)
            }
            else -> null
        }
    }

    private fun copyAssets(context: Context, path: String, target: File) {
        val am = context.assets
        val children = am.list(path).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            am.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
        } else {
            target.mkdirs()
            children.forEach { copyAssets(context, "$path/$it", File(target, it)) }
        }
    }
}
