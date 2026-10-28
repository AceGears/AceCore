package com.acecore.app.runtime.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.acecore.app.core.config.PadButton
import java.util.EnumMap
import kotlin.math.abs

/**
 * Translates Android gamepad events (buttons, D-pad, hats, sticks, analog triggers) into edge-triggered
 * [PadButton] press/release callbacks. Works with any controller Android exposes through InputDevice:
 * Xbox, DualShock/DualSense, Switch Pro, 8BitDo, generic HID and handheld built-in controls.
 */
class ControllerInput(
    private val onButton: (PadButton, Boolean) -> Unit,
    private val onMenu: () -> Unit,
) {
    var deadzone: Float = 0.3f
    private val state = EnumMap<PadButton, Boolean>(PadButton::class.java)

    // Separate direction sources so releasing the stick does not release a held D-pad and vice versa.
    private val keyDirs = EnumMap<PadButton, Boolean>(PadButton::class.java)
    private val stickDirs = EnumMap<PadButton, Boolean>(PadButton::class.java)
    private val hatDirs = EnumMap<PadButton, Boolean>(PadButton::class.java)

    fun handleKey(event: KeyEvent): Boolean {
        if (!isController(event)) return false
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (event.action == KeyEvent.ACTION_UP) onMenu()
            return true
        }
        val button = mapKey(event.keyCode) ?: return false
        if (event.repeatCount > 0) return true
        val pressed = event.action == KeyEvent.ACTION_DOWN
        if (button in DIRECTIONS) { keyDirs[button] = pressed; syncDirection(button) } else set(button, pressed)
        return true
    }

    fun handleMotion(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) && !event.isFromSource(InputDevice.SOURCE_GAMEPAD)) return false
        if (event.action != MotionEvent.ACTION_MOVE) return false
        val device = event.device
        val x = centered(event, device, MotionEvent.AXIS_X)
        val y = centered(event, device, MotionEvent.AXIS_Y)
        stickDirs[PadButton.LEFT] = x < -deadzone
        stickDirs[PadButton.RIGHT] = x > deadzone
        stickDirs[PadButton.UP] = y < -deadzone
        stickDirs[PadButton.DOWN] = y > deadzone
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        hatDirs[PadButton.LEFT] = hx < -0.5f
        hatDirs[PadButton.RIGHT] = hx > 0.5f
        hatDirs[PadButton.UP] = hy < -0.5f
        hatDirs[PadButton.DOWN] = hy > 0.5f
        DIRECTIONS.forEach { syncDirection(it) }
        val lt = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rt = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        // Only drive L2/R2 from axes on controllers that actually report those axes (others send key events).
        if (device?.getMotionRange(MotionEvent.AXIS_LTRIGGER) != null || device?.getMotionRange(MotionEvent.AXIS_BRAKE) != null) set(PadButton.L2, lt > 0.5f)
        if (device?.getMotionRange(MotionEvent.AXIS_RTRIGGER) != null || device?.getMotionRange(MotionEvent.AXIS_GAS) != null) set(PadButton.R2, rt > 0.5f)
        return true
    }

    fun releaseAll() {
        state.keys.toList().forEach { set(it, false) }
        keyDirs.clear(); stickDirs.clear(); hatDirs.clear()
    }

    private fun syncDirection(b: PadButton) =
        set(b, keyDirs[b] == true || stickDirs[b] == true || hatDirs[b] == true)

    private fun set(b: PadButton, pressed: Boolean) {
        if ((state[b] == true) == pressed) return
        state[b] = pressed
        onButton(b, pressed)
    }

    private fun centered(e: MotionEvent, device: InputDevice?, axis: Int): Float {
        val v = e.getAxisValue(axis)
        val flat = device?.getMotionRange(axis, e.source)?.flat ?: 0f
        return if (abs(v) > flat) v else 0f
    }

    companion object {
        private val DIRECTIONS = listOf(PadButton.UP, PadButton.DOWN, PadButton.LEFT, PadButton.RIGHT)

        fun mapKey(keyCode: Int): PadButton? = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> PadButton.A
            KeyEvent.KEYCODE_BUTTON_B -> PadButton.B
            KeyEvent.KEYCODE_BUTTON_X -> PadButton.X
            KeyEvent.KEYCODE_BUTTON_Y -> PadButton.Y
            KeyEvent.KEYCODE_BUTTON_L1 -> PadButton.L1
            KeyEvent.KEYCODE_BUTTON_R1 -> PadButton.R1
            KeyEvent.KEYCODE_BUTTON_L2 -> PadButton.L2
            KeyEvent.KEYCODE_BUTTON_R2 -> PadButton.R2
            KeyEvent.KEYCODE_BUTTON_START -> PadButton.START
            KeyEvent.KEYCODE_BUTTON_SELECT -> PadButton.SELECT
            KeyEvent.KEYCODE_BUTTON_THUMBL -> PadButton.L3
            KeyEvent.KEYCODE_BUTTON_THUMBR -> PadButton.R3
            KeyEvent.KEYCODE_DPAD_UP -> PadButton.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> PadButton.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> PadButton.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> PadButton.RIGHT
            else -> null
        }

        fun isController(event: KeyEvent): Boolean {
            if (KeyEvent.isGamepadButton(event.keyCode)) return true
            val src = event.source
            val fromPad = (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            val isDpadKey = event.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER
            // Arrow keys on a hardware keyboard must reach the WebView untouched.
            val alphabetic = event.device?.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
            return isDpadKey && (fromPad || ((src and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD && !alphabetic))
        }

        /** True when at least one connected device looks like a game controller. */
        fun controllerConnected(): Boolean = InputDevice.getDeviceIds().any { id ->
            val d = InputDevice.getDevice(id) ?: return@any false
            val s = d.sources
            !d.isVirtual && ((s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
        }
    }
}
