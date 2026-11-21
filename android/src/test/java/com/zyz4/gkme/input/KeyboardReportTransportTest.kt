package com.zyz4.gkme.input

import android.view.InputDevice
import android.view.KeyEvent
import com.zyz4.gkme.Kb
import com.zyz4.gkme.proto.GamepadInput
import org.junit.Assert.*
import org.junit.Test

class KeyboardReportTransportTest {
    @Test fun usbAndBluetoothKeyboardDirectionsProduceIdenticalHeldAndReleaseFrames() {
        fun frames(eventSource: Int, deviceSources: Int): List<GamepadInput> {
            val state = KeyboardState()
            val passthrough = ExternalKeyboardPassthrough(
                { code, owner -> state.keyDown(code, owner) },
                { code, owner -> state.keyUp(code, owner) })
            fun key(code: Int, action: Int, repeats: Int = 0) {
                val eligible = ExternalKeyboardPassthrough.isExternalKeyboard(eventSource,
                    deviceSources, true, false, 0x8, InputDevice.KEYBOARD_TYPE_ALPHABETIC, code)
                assertTrue(passthrough.handle(10, code, action, repeats, eligible, true))
            }
            key(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN)
            key(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN)
            key(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.ACTION_DOWN)
            repeat(5) { key(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN, it + 1) }
            key(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_UP)
            key(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.ACTION_UP)
            val pending = state.drainReports().toMutableList()
            // Continued polling while UP is held must keep it down without repeat events.
            pending += state.drainReports()
            key(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_UP)
            pending += state.drainReports()
            val input = GamepadInput.getDefaultInstance()
            return pending.map { GamepadInput.parseFrom(input.withKeyboard(it, true).toByteArray()) }
        }
        val usb = frames(InputDevice.SOURCE_KEYBOARD, InputDevice.SOURCE_KEYBOARD)
        val bluetooth = frames(0x301, 0x1002313)
        assertEquals(usb, bluetooth)
        assertEquals(listOf(listOf(Kb.ArrowUp), listOf(Kb.ArrowUp, Kb.ArrowLeft),
            listOf(Kb.ArrowUp, Kb.ArrowLeft), listOf(Kb.ArrowUp), listOf(Kb.ArrowUp),
            listOf(Kb.ArrowUp), emptyList<Int>()), bluetooth.map { it.pressedScanCodesList })
        assertEquals(listOf(0, 0, 2, 2, 0, 0, 0), bluetooth.map { it.keyboardModifiers })
        assertTrue(bluetooth.all { it.buttons == 0 && it.dpad == 0 })
    }

    @Test fun shortTapSerializesBothTransitionsAndKeepsExistingGamepadAndMouseLevels() {
        val state = KeyboardState()
        val owner = Any()
        state.keyDown(0xE0, owner)
        state.keyDown(0x06, owner)
        state.keyUp(0x06, owner)
        state.keyUp(0xE0, owner)
        val input = GamepadInput.newBuilder().setButtons(0x1000).setDpad(3)
            .setLeftStickX(15000).setRightTrigger(200).setMouseButtons(1)
            .setMouseDx(25).setMouseDy(-8).setMouseWheel(2).setMousePan(-1).build()
        val frames = state.drainReports().mapIndexed { index, report ->
            // Exercise the protobuf encoding used by both WiFi and USB.
            GamepadInput.parseFrom(input.withKeyboard(report, index == 0).toByteArray())
        }
        assertEquals(listOf(1, 1, 1, 0), frames.map { it.keyboardModifiers })
        assertEquals(listOf(emptyList<Int>(), listOf(0x06), emptyList(), emptyList()),
            frames.map { it.pressedScanCodesList })
        for (frame in frames) {
            assertEquals(0x1000, frame.buttons)
            assertEquals(3, frame.dpad)
            assertEquals(15000, frame.leftStickX)
            assertEquals(200, frame.rightTrigger)
            assertEquals(1, frame.mouseButtons)
        }
        assertEquals(25, frames.sumOf { it.mouseDx })
        assertEquals(-8, frames.sumOf { it.mouseDy })
        assertEquals(2, frames.sumOf { it.mouseWheel })
        assertEquals(-1, frames.sumOf { it.mousePan })
        assertEquals(0, frames.last().pressedScanCodesCount)
        assertEquals(0, frames.last().keyboardModifiers)
    }
}
