package com.acecore.app.runtime.handheld

import android.content.Context
import android.util.Log
import com.acecore.app.core.handheld.HandheldPlatform
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The emulator: AceArcade's libretro frontend (libacehandheld.so, built from ci/handheld/frontend) hosting mGBA
 * (Game Boy, Game Boy Color, Game Boy Advance) or melonDS DS (Nintendo DS). One per process: libretro cores keep
 * global state. Every call that touches the emulator is carried out on the frontend's emulation thread between
 * frames, so these methods are safe from any thread (and block until done).
 */
@androidx.annotation.Keep
class HandheldCore(private val onFrameReady: (rumble: Int) -> Unit, private val onLogLine: (Int, String) -> Unit) {

    /** Picture of the latest frame: RGBA bytes, filled by [copyFrame]. Large enough for melonDS DS's largest layout. */
    val frame: ByteBuffer = ByteBuffer.allocateDirect(1024 * 1024 * 4).order(ByteOrder.nativeOrder())

    init { nCreate() }

    data class AvInfo(val fps: Double, val sampleRate: Double, val width: Int, val height: Int, val aspect: Float)

    /** Loads the core for [platform]. Returns an error message, or null on success. */
    fun open(context: Context, platform: HandheldPlatform, systemDir: File, saveDir: File): String? {
        val lib = File(context.applicationInfo.nativeLibraryDir, coreLibrary(platform))
        if (!lib.isFile) return "This build of AceArcade doesn't include the ${platform.displayName} emulator."
        systemDir.mkdirs(); saveDir.mkdirs()
        return nOpen(lib.absolutePath, systemDir.absolutePath, saveDir.absolutePath)
    }

    fun setOption(key: String, value: String) = nSetOption(key, value)
    fun load(romPath: String): String? = nLoad(romPath)
    fun avInfo(): AvInfo = nAvInfo().let { AvInfo(it[0], it[1], it[2].toInt(), it[3].toInt(), it[4].toFloat()) }
    fun setRunning(running: Boolean) = nSetRunning(running)
    fun setSpeed(speed: Float) = nSetSpeed(speed)
    fun setVolume(v: Float) = nSetVolume(v)
    fun setButtons(mask: Int) = nButtons(mask)
    /** Pointer in libretro coordinates (-0x7FFF..0x7FFF over the whole picture). */
    fun setPointer(x: Int, y: Int, pressed: Boolean) = nPointer(x, y, pressed)

    /** Copies the latest frame into [frame]. Returns (serial, width, height), or null before the first frame. */
    fun copyFrame(): Triple<Long, Int, Int>? {
        val r = nCopyFrame(frame)
        if (r == 0L) return null
        return Triple(r ushr 32, ((r ushr 16) and 0xFFFF).toInt(), (r and 0xFFFF).toInt())
    }

    fun readBatterySave(): ByteArray? = nSramRead()
    fun writeBatterySave(data: ByteArray): Int = nSramWrite(data)
    fun saveState(): ByteArray? = nStateSave()
    fun loadState(data: ByteArray): Boolean = nStateLoad(data)
    /** Replaces the active cheats (codes in the libretro "+"-joined form). */
    fun setCheats(codes: List<String>): Int = nSetCheats(codes.toTypedArray())
    fun readMemory(address: Long, length: Int): ByteArray? = nMemRead(address, length)
    fun writeMemory(address: Long, data: ByteArray): Int = nMemWrite(address, data)
    fun memoryMaps(): String = nMemMaps()
    fun reset() = nReset()
    fun lastMessage(): String = nMessage()
    fun stoppedByError(): Boolean = nShutdownRequested()
    fun microphoneRate(): Int = nMicRate()
    fun microphoneWanted(): Boolean = nMicWanted()
    fun pushMicrophone(samples: ShortArray, count: Int) = nMicPush(samples, count)
    fun destroy() = nDestroy()

    // Called from the emulation thread.
    @Suppress("unused") private fun onFrame(rumble: Int) = onFrameReady(rumble)
    @Suppress("unused") private fun onLog(level: Int, message: String) = onLogLine(level, message)

    private external fun nCreate()
    private external fun nOpen(core: String, system: String, save: String): String?
    private external fun nSetOption(key: String, value: String)
    private external fun nLoad(path: String): String?
    private external fun nAvInfo(): DoubleArray
    private external fun nSetRunning(running: Boolean)
    private external fun nSetSpeed(speed: Float)
    private external fun nSetVolume(volume: Float)
    private external fun nButtons(mask: Int)
    private external fun nPointer(x: Int, y: Int, pressed: Boolean)
    private external fun nAnalog(index: Int, id: Int, value: Int)
    private external fun nCopyFrame(buffer: ByteBuffer): Long
    private external fun nSramRead(): ByteArray?
    private external fun nSramWrite(data: ByteArray): Int
    private external fun nStateSave(): ByteArray?
    private external fun nStateLoad(data: ByteArray): Boolean
    private external fun nSetCheats(codes: Array<String>): Int
    private external fun nMemRead(address: Long, length: Int): ByteArray?
    private external fun nMemWrite(address: Long, data: ByteArray): Int
    private external fun nMemMaps(): String
    private external fun nReset()
    private external fun nMessage(): String
    private external fun nShutdownRequested(): Boolean
    private external fun nMicRate(): Int
    private external fun nMicWanted(): Boolean
    private external fun nMicPush(data: ShortArray, count: Int)
    private external fun nDestroy()

    companion object {
        private const val TAG = "AceArcadeHandheld"

        fun coreLibrary(platform: HandheldPlatform) =
            if (platform == HandheldPlatform.NDS) "libmelondsds_libretro.so" else "libmgba_libretro.so"

        /** True when this APK contains the frontend and the core for [platform]. */
        fun isPresent(context: Context, platform: HandheldPlatform): Boolean {
            val dir = context.applicationInfo.nativeLibraryDir
            return File(dir, "libacehandheld.so").isFile && File(dir, coreLibrary(platform)).isFile
        }

        @Volatile private var loaded = false
        fun loadLibrary(): Boolean {
            if (loaded) return true
            return runCatching { System.loadLibrary("acehandheld"); loaded = true; true }
                .onFailure { Log.e(TAG, "libacehandheld.so", it) }.getOrDefault(false)
        }

        /** libretro joypad bits (RETRO_DEVICE_ID_JOYPAD_*). */
        const val B = 0; const val Y = 1; const val SELECT = 2; const val START = 3
        const val UP = 4; const val DOWN = 5; const val LEFT = 6; const val RIGHT = 7
        const val A = 8; const val X = 9; const val L = 10; const val R = 11

        /** Emulator settings AceArcade always uses (the frontend lays out DS screens itself; no Nintendo files). */
        fun baseOptions(platform: HandheldPlatform, gbPalette: String): Map<String, String> = when (platform) {
            HandheldPlatform.NDS -> mapOf(
                "melonds_console_mode" to "ds", "melonds_sysfile_mode" to "builtin", "melonds_boot_mode" to "direct",
                "melonds_render_mode" to "software", "melonds_number_of_screen_layouts" to "1",
                "melonds_screen_layout1" to "top-bottom", "melonds_screen_gap" to "0", "melonds_secondary_screen_scale" to "100",
                "melonds_touch_mode" to "touch", "melonds_show_cursor" to "disabled", "melonds_mic_input" to "microphone",
                "melonds_mic_input_active" to "always", "melonds_opengl_filtering" to "nearest", "melonds_jit_enable" to "enabled",
                // Fast memory relies on catching memory faults, which the Android runtime also uses; off is the safe choice.
                "melonds_jit_fast_memory" to "disabled",
                "melonds_show_unsupported_features" to "disabled", "melonds_show_bios_warnings" to "disabled",
                "melonds_show_current_layout" to "disabled", "melonds_show_mic_state" to "disabled",
                "melonds_show_camera_state" to "disabled", "melonds_show_lid_state" to "disabled",
                "melonds_show_sensor_reading" to "disabled",
            )
            else -> mapOf(
                "mgba_use_bios" to "OFF", "mgba_skip_bios" to "OFF", "mgba_sgb_borders" to "OFF",
                "mgba_gb_model" to "Autodetect", "mgba_frameskip" to "0", "mgba_idle_optimization" to "Remove Known",
                "mgba_gb_colors" to gbPalette, "mgba_gb_colors_preset" to "0",
            )
        }
    }
}
