package com.zyz4.gkme.input

import com.zyz4.gkme.model.GamepadState
import com.zyz4.gkme.model.PrecisionTouchpadContact
import com.zyz4.gkme.model.PrecisionTouchpadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadInputProcessorPrecisionTouchpadTest {

    @Test
    fun toProto_omitsPrecisionTouchpadWhenInactive() {
        val proto = GamepadState().toProto()
        assertFalse(proto.hasPrecisionTouchpad())
    }

    @Test
    fun toProto_carriesContactsAndButtons() {
        val proto = GamepadState(
            precisionTouchpad = PrecisionTouchpadState(
                contacts = listOf(
                    PrecisionTouchpadContact(0, 100, 200),
                    PrecisionTouchpadContact(1, 4095, 0),
                ),
                buttons = PrecisionTouchpadState.BUTTON_LEFT,
            ),
        ).toProto()

        assertTrue(proto.hasPrecisionTouchpad())
        assertEquals(1, proto.precisionTouchpad.buttons)
        assertEquals(2, proto.precisionTouchpad.contactsCount)
        assertEquals(100, proto.precisionTouchpad.getContacts(0).x)
        assertEquals(200, proto.precisionTouchpad.getContacts(0).y)
        assertEquals(1, proto.precisionTouchpad.getContacts(1).id)
        assertEquals(4095, proto.precisionTouchpad.getContacts(1).x)
    }
}
