package com.zyz4.gkme.input

import android.view.InputDevice
import android.view.KeyEvent
import com.zyz4.gkme.Kb
import org.junit.Assert.*
import org.junit.Test

class ExternalKeyboardPassthroughTest {
    private val state = KeyboardState()
    private val passthrough = ExternalKeyboardPassthrough(
        { code, owner -> state.keyDown(code, owner) },
        { code, owner -> state.keyUp(code, owner) },
    )

    private fun event(
        code: Int,
        action: Int = KeyEvent.ACTION_DOWN,
        device: Int = 10,
        repeats: Int = 0,
        eligible: Boolean = true,
        enabled: Boolean = true,
    ) = passthrough.handle(device, code, action, repeats, eligible, enabled)

    @Test fun mapsLettersDigitsFunctionNavigationAndPunctuationToHidUsages() {
        val examples = mapOf(
            KeyEvent.KEYCODE_A to Kb.KeyA, KeyEvent.KEYCODE_Z to Kb.KeyZ,
            KeyEvent.KEYCODE_0 to Kb.Key0, KeyEvent.KEYCODE_1 to Kb.Key1,
            KeyEvent.KEYCODE_9 to Kb.Key9, KeyEvent.KEYCODE_F1 to Kb.F1,
            KeyEvent.KEYCODE_F12 to Kb.F12, KeyEvent.KEYCODE_DEL to Kb.Backspace,
            KeyEvent.KEYCODE_FORWARD_DEL to Kb.Delete, KeyEvent.KEYCODE_MOVE_HOME to Kb.Home,
            KeyEvent.KEYCODE_DPAD_LEFT to Kb.ArrowLeft, KeyEvent.KEYCODE_ESCAPE to Kb.Esc,
            KeyEvent.KEYCODE_ENTER to Kb.Enter, KeyEvent.KEYCODE_APOSTROPHE to Kb.Apostrophe,
            KeyEvent.KEYCODE_NUMPAD_ENTER to Kb.NumpadEnter, KeyEvent.KEYCODE_NUMPAD_9 to 0x61,
            KeyEvent.KEYCODE_NUMPAD_0 to Kb.Numpad0, KeyEvent.KEYCODE_SYSRQ to Kb.PrintScreen,
        )
        for ((androidCode, usage) in examples) {
            assertEquals("Android key $androidCode", usage, ExternalKeyboardPassthrough.toUsage(androidCode))
        }
    }

    @Test fun mapsAllEightLeftAndRightModifiers() {
        val codes = listOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_META_RIGHT)
        assertEquals((0xE0..0xE7).toList(), codes.map { ExternalKeyboardPassthrough.toUsage(it) })
    }

    @Test fun ctrlCAndSimultaneousMovementKeysKeepIndependentReleases() {
        assertTrue(event(KeyEvent.KEYCODE_CTRL_LEFT))
        event(KeyEvent.KEYCODE_C)
        event(KeyEvent.KEYCODE_W)
        event(KeyEvent.KEYCODE_A)
        event(KeyEvent.KEYCODE_C, KeyEvent.ACTION_UP)
        assertEquals(KeyboardReport(1, listOf(Kb.KeyW, Kb.KeyA)), state.snapshot())
        event(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_UP)
        assertEquals(KeyboardReport(0, listOf(Kb.KeyW, Kb.KeyA)), state.snapshot())
        event(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP)
        event(KeyEvent.KEYCODE_W, KeyEvent.ACTION_UP)
        assertEquals(KeyboardReport(), state.snapshot())
    }

    @Test fun longPressRepeatsAndDuplicateDownsDoNotStick() {
        event(KeyEvent.KEYCODE_A)
        repeat(15) { event(KeyEvent.KEYCODE_A, repeats = it + 1) }
        event(KeyEvent.KEYCODE_A)
        event(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP)
        assertEquals(listOf(KeyboardReport(0, listOf(Kb.KeyA)), KeyboardReport()), state.drainReports())
    }

    @Test fun deviceRemovalReleasesOnlyThatDeviceAndPreservesVirtualAndGamepadOwners() {
        state.keyDown(Kb.LShift, KeyboardSource.VIRTUAL)
        state.keyDown(Kb.KeyA, KeyboardSource.GAMEPAD)
        event(KeyEvent.KEYCODE_A, device = 10)
        event(KeyEvent.KEYCODE_A, device = 11)
        event(KeyEvent.KEYCODE_SHIFT_LEFT, device = 10)
        passthrough.releaseDevice(10)
        assertEquals(KeyboardReport(2, listOf(Kb.KeyA)), state.snapshot())
        passthrough.releaseDevice(11)
        assertEquals(KeyboardReport(2, listOf(Kb.KeyA)), state.snapshot())
        state.keyUp(Kb.KeyA, KeyboardSource.GAMEPAD)
        assertEquals(KeyboardReport(2), state.snapshot())
    }

    @Test fun focusLossReleasesAllExternalKeysAndIgnoresRepeatsAfterRecovery() {
        event(KeyEvent.KEYCODE_CTRL_RIGHT)
        event(KeyEvent.KEYCODE_A)
        passthrough.releaseAll()
        assertEquals(KeyboardReport(), state.snapshot())
        event(KeyEvent.KEYCODE_A, repeats = 4)
        event(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP)
        assertEquals(KeyboardReport(), state.snapshot())
        event(KeyEvent.KEYCODE_A)
        assertEquals(listOf(Kb.KeyA), state.snapshot().keys)
    }

    @Test fun disabledSessionCleansHoldsAndDoesNotCaptureNewKeys() {
        event(KeyEvent.KEYCODE_A)
        assertFalse(event(KeyEvent.KEYCODE_B, enabled = false))
        assertEquals(KeyboardReport(), state.snapshot())
        event(KeyEvent.KEYCODE_A, repeats = 1)
        assertEquals(KeyboardReport(), state.snapshot())
    }

    @Test fun keyUpStillReleasesCapturedKeyWhenDeviceMetadataIsGone() {
        event(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertTrue(event(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.ACTION_UP, eligible = false))
        assertEquals(KeyboardReport(), state.snapshot())
    }

    @Test fun unmatchedKeyUpDoesNotReleaseAnotherSource() {
        state.keyDown(Kb.KeyA, KeyboardSource.VIRTUAL)
        event(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP)
        assertEquals(listOf(Kb.KeyA), state.snapshot().keys)
    }

    @Test fun backVolumeSystemAndGamepadKeysAreNotCaptured() {
        val reserved = listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_UNKNOWN)
        for (code in reserved) {
            assertFalse(event(code))
            assertFalse(event(code, KeyEvent.ACTION_UP))
        }
        assertFalse(event(KeyEvent.KEYCODE_A, action = KeyEvent.ACTION_MULTIPLE))
        assertEquals(KeyboardReport(), state.snapshot())
    }

    @Test fun onlyExternalRealKeyboardSourcesAreEligible() {
        fun eligible(source: Int = InputDevice.SOURCE_KEYBOARD, deviceSources: Int = source,
            external: Boolean = true, virtual: Boolean = false, flags: Int = 0) =
            ExternalKeyboardPassthrough.isExternalKeyboard(source, deviceSources, external, virtual, flags)
        assertTrue(eligible())
        assertTrue(eligible(deviceSources = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_MOUSE))
        assertFalse(eligible(external = false))
        assertFalse(eligible(virtual = true))
        assertFalse(eligible(flags = KeyEvent.FLAG_SOFT_KEYBOARD))
        assertFalse(eligible(source = InputDevice.SOURCE_DPAD))
        assertFalse(eligible(source = InputDevice.SOURCE_GAMEPAD))
        assertFalse(eligible(source = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_GAMEPAD))
        assertFalse(eligible(deviceSources = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_JOYSTICK))
        assertFalse(event(KeyEvent.KEYCODE_DPAD_LEFT, eligible = false))
    }

    @Test fun fullKeyboardWithExtraControllerCapabilitiesCanStillForwardLettersAndModifiers() {
        val deviceSources = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_DPAD or InputDevice.SOURCE_GAMEPAD
        for (code in listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_F12)) {
            for (eventSource in listOf(InputDevice.SOURCE_KEYBOARD, deviceSources)) {
                val accepted = ExternalKeyboardPassthrough.isExternalKeyboard(eventSource,
                    deviceSources, true, false, 0, InputDevice.KEYBOARD_TYPE_ALPHABETIC, code)
                assertTrue("Full keyboard event should be accepted", accepted)
                assertTrue(event(code, eligible = accepted))
                assertTrue(event(code, KeyEvent.ACTION_UP, eligible = accepted))
            }
        }
        assertEquals(KeyboardReport(), state.snapshot())
    }

    @Test fun f96BluetoothKeyboardArrowsRemainKeyboardInputDespiteDeviceJoystickCapability() {
        // Metadata from the user's Android 10 F96 diagnostics. USB and Bluetooth
        // can expose different device capabilities for the same physical keyboard.
        val arrows = mapOf(KeyEvent.KEYCODE_DPAD_UP to Kb.ArrowUp,
            KeyEvent.KEYCODE_DPAD_DOWN to Kb.ArrowDown,
            KeyEvent.KEYCODE_DPAD_LEFT to Kb.ArrowLeft,
            KeyEvent.KEYCODE_DPAD_RIGHT to Kb.ArrowRight)
        for ((code, usage) in arrows) {
            val accepted = ExternalKeyboardPassthrough.isExternalKeyboard(
                0x301, 0x1002313, true, false, 0x8, InputDevice.KEYBOARD_TYPE_ALPHABETIC, code)
            assertTrue("F96 keyboard arrow $code must not be filtered as controller navigation", accepted)
            assertTrue(event(code, eligible = accepted))
            assertEquals(KeyboardReport(0, listOf(usage)), state.snapshot())
            assertTrue(event(code, KeyEvent.ACTION_UP, eligible = accepted))
            assertEquals(KeyboardReport(), state.snapshot())
        }
    }

    @Test fun compositeKeyboardDoesNotStealControllerSourcedNavigationOrNonAlphabeticGamepads() {
        val sources = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK
        assertFalse(ExternalKeyboardPassthrough.isExternalKeyboard(
            InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_GAMEPAD, sources, true, false, 0,
            InputDevice.KEYBOARD_TYPE_ALPHABETIC, KeyEvent.KEYCODE_BUTTON_A))
        assertFalse(ExternalKeyboardPassthrough.isExternalKeyboard(
            InputDevice.SOURCE_GAMEPAD, sources, true, false, 0,
            InputDevice.KEYBOARD_TYPE_ALPHABETIC, KeyEvent.KEYCODE_A))
        for (code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER) {
            assertFalse(ExternalKeyboardPassthrough.isExternalKeyboard(sources,
                sources, true, false, 0, InputDevice.KEYBOARD_TYPE_ALPHABETIC, code))
            assertFalse(ExternalKeyboardPassthrough.isExternalKeyboard(0x301,
                sources, true, false, 0, InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC, code))
        }
        assertFalse(ExternalKeyboardPassthrough.isExternalKeyboard(InputDevice.SOURCE_KEYBOARD,
            sources, true, false, 0, InputDevice.KEYBOARD_TYPE_ALPHABETIC, KeyEvent.KEYCODE_DPAD_CENTER))
        assertFalse(ExternalKeyboardPassthrough.isExternalKeyboard(InputDevice.SOURCE_KEYBOARD,
            sources, true, false, 0, InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC, KeyEvent.KEYCODE_A))
        assertFalse(ExternalKeyboardPassthrough.isExternalKeyboard(InputDevice.SOURCE_KEYBOARD,
            sources, true, false, KeyEvent.FLAG_SOFT_KEYBOARD, InputDevice.KEYBOARD_TYPE_ALPHABETIC, KeyEvent.KEYCODE_A))
    }

    @Test fun preImeConsumptionAndActivityFallbackCannotDuplicateAHold() {
        // Both entry points share this state machine; consumed pre-IME events normally
        // never reach Activity, but even a duplicate dispatch must be idempotent.
        event(KeyEvent.KEYCODE_CTRL_LEFT)
        event(KeyEvent.KEYCODE_A)
        event(KeyEvent.KEYCODE_A)
        event(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP)
        event(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP)
        assertEquals(KeyboardReport(1), state.snapshot())
        event(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_UP)
        assertEquals(KeyboardReport(), state.snapshot())
        assertEquals(listOf(KeyboardReport(1), KeyboardReport(1, listOf(Kb.KeyA)),
            KeyboardReport(1), KeyboardReport()), state.drainReports())
    }
}
