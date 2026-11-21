package com.zyz4.gkme.input

import android.view.InputDevice
import android.view.KeyEvent
import com.zyz4.gkme.Kb

/** Android constants are inlined; the translator and held-key lifecycle need no Android runtime. */
internal class ExternalKeyboardPassthrough(
    private val onKeyDown: (Int, Any) -> Unit,
    private val onKeyUp: (Int, Any) -> Unit,
) {
    private data class Key(val deviceId: Int, val keyCode: Int)
    private val held = linkedMapOf<Key, Int>()

    fun handle(
        deviceId: Int,
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        eligible: Boolean,
        enabled: Boolean,
    ): Boolean {
        if (!enabled) {
            releaseAll()
            return false
        }
        val key = Key(deviceId, keyCode)
        // Release a captured key even if its device disappeared or its source changed.
        if (action == KeyEvent.ACTION_UP) {
            held.remove(key)?.let { usage ->
                onKeyUp(usage, key)
                return true
            }
        }
        if (!eligible) return false
        val usage = toUsage(keyCode) ?: return false
        when (action) {
            KeyEvent.ACTION_DOWN -> {
                // A repeat after focus/connection recovery must not resurrect an old hold.
                if (repeatCount == 0 && key !in held) {
                    held[key] = usage
                    onKeyDown(usage, key)
                }
            }
            KeyEvent.ACTION_UP -> Unit // An unmatched release cannot release another source.
            else -> return false
        }
        return true
    }

    fun releaseDevice(deviceId: Int) {
        val keys = held.keys.filter { it.deviceId == deviceId }
        for (key in keys) onKeyUp(held.remove(key)!!, key)
    }

    fun releaseAll() {
        val previous = held.toMap()
        held.clear()
        for ((key, usage) in previous) onKeyUp(usage, key)
    }

    companion object {
        fun isExternalKeyboard(
            eventSource: Int,
            deviceSources: Int,
            external: Boolean,
            virtual: Boolean,
            flags: Int,
            keyboardType: Int = InputDevice.KEYBOARD_TYPE_NONE,
            keyCode: Int = KeyEvent.KEYCODE_UNKNOWN,
        ): Boolean = rejectionReason(eventSource, deviceSources, external, virtual, flags, keyboardType, keyCode) == null

        /** Device capabilities can include controller bits even for a full HID keyboard. */
        fun rejectionReason(eventSource: Int, deviceSources: Int, external: Boolean, virtual: Boolean,
            flags: Int, keyboardType: Int, keyCode: Int): String? {
            if (virtual) return "virtual_device"
            if (!external) return "not_external"
            if (flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0) return "soft_keyboard_event"
            if (eventSource and InputDevice.SOURCE_KEYBOARD != InputDevice.SOURCE_KEYBOARD ||
                deviceSources and InputDevice.SOURCE_KEYBOARD != InputDevice.SOURCE_KEYBOARD) return "not_keyboard_source"
            fun controller(source: Int) = source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            // Android can put the device's aggregate capabilities on every key event,
            // so additional controller bits alone must not reject a full keyboard.
            if (controller(eventSource) || controller(deviceSources)) {
                if (keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                    return if (controller(eventSource)) "controller_event" else "controller_device"
                }
                // A keyboard's arrow keys use Android's DPAD key codes too. Device-wide
                // joystick capability (e.g. Bluetooth F96) must not redirect keyboard-
                // sourced arrows to the gamepad path. Keep actual controller events there.
                if (keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || controller(eventSource))) return "controller_navigation"
                if (toUsage(keyCode) == null) return "controller_key"
            }
            return null
        }

        fun toUsage(keyCode: Int): Int? = when (keyCode) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> Kb.KeyA + keyCode - KeyEvent.KEYCODE_A
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> Kb.Key1 + keyCode - KeyEvent.KEYCODE_1
            KeyEvent.KEYCODE_0 -> Kb.Key0
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> Kb.F1 + keyCode - KeyEvent.KEYCODE_F1
            KeyEvent.KEYCODE_CTRL_LEFT -> Kb.LCtrl
            KeyEvent.KEYCODE_CTRL_RIGHT -> Kb.RCtrl
            KeyEvent.KEYCODE_SHIFT_LEFT -> Kb.LShift
            KeyEvent.KEYCODE_SHIFT_RIGHT -> Kb.RShift
            KeyEvent.KEYCODE_ALT_LEFT -> Kb.LAlt
            KeyEvent.KEYCODE_ALT_RIGHT -> Kb.RAlt
            KeyEvent.KEYCODE_META_LEFT -> Kb.LGui
            KeyEvent.KEYCODE_META_RIGHT -> Kb.RGui
            KeyEvent.KEYCODE_ENTER -> Kb.Enter
            KeyEvent.KEYCODE_ESCAPE -> Kb.Esc
            KeyEvent.KEYCODE_DEL -> Kb.Backspace
            KeyEvent.KEYCODE_FORWARD_DEL -> Kb.Delete
            KeyEvent.KEYCODE_TAB -> Kb.Tab
            KeyEvent.KEYCODE_SPACE -> Kb.Space
            KeyEvent.KEYCODE_MINUS -> Kb.Minus
            KeyEvent.KEYCODE_EQUALS -> Kb.Equal
            KeyEvent.KEYCODE_LEFT_BRACKET -> Kb.LBracket
            KeyEvent.KEYCODE_RIGHT_BRACKET -> Kb.RBracket
            KeyEvent.KEYCODE_BACKSLASH -> Kb.Backslash
            KeyEvent.KEYCODE_SEMICOLON -> Kb.Semicolon
            KeyEvent.KEYCODE_APOSTROPHE -> Kb.Apostrophe
            KeyEvent.KEYCODE_GRAVE -> Kb.Grave
            KeyEvent.KEYCODE_COMMA -> Kb.Comma
            KeyEvent.KEYCODE_PERIOD -> Kb.Dot
            KeyEvent.KEYCODE_SLASH -> Kb.Slash
            KeyEvent.KEYCODE_CAPS_LOCK -> Kb.CapsLock
            KeyEvent.KEYCODE_INSERT -> Kb.Insert
            KeyEvent.KEYCODE_MOVE_HOME -> Kb.Home
            KeyEvent.KEYCODE_MOVE_END -> Kb.End
            KeyEvent.KEYCODE_PAGE_UP -> Kb.PageUp
            KeyEvent.KEYCODE_PAGE_DOWN -> Kb.PageDown
            KeyEvent.KEYCODE_DPAD_RIGHT -> Kb.ArrowRight
            KeyEvent.KEYCODE_DPAD_LEFT -> Kb.ArrowLeft
            KeyEvent.KEYCODE_DPAD_DOWN -> Kb.ArrowDown
            KeyEvent.KEYCODE_DPAD_UP -> Kb.ArrowUp
            KeyEvent.KEYCODE_MENU -> Kb.Menu
            KeyEvent.KEYCODE_SYSRQ -> Kb.PrintScreen
            KeyEvent.KEYCODE_SCROLL_LOCK -> Kb.ScrollLock
            KeyEvent.KEYCODE_BREAK -> Kb.Pause
            KeyEvent.KEYCODE_NUM_LOCK -> Kb.NumLock
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> Kb.NumpadDivide
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> Kb.NumpadMultiply
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> Kb.NumpadSubtract
            KeyEvent.KEYCODE_NUMPAD_ADD -> Kb.NumpadAdd
            KeyEvent.KEYCODE_NUMPAD_ENTER -> Kb.NumpadEnter
            in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_9 ->
                Kb.Numpad1 + keyCode - KeyEvent.KEYCODE_NUMPAD_1
            KeyEvent.KEYCODE_NUMPAD_0 -> Kb.Numpad0
            KeyEvent.KEYCODE_NUMPAD_DOT -> Kb.NumpadDot
            KeyEvent.KEYCODE_NUMPAD_EQUALS -> Kb.NumpadEqual
            // BACK/HOME/volume/media/gamepad/unknown keys keep their existing Android handling.
            else -> null
        }
    }
}
