package com.zyz4.gkme.input

import com.zyz4.gkme.proto.GamepadInput
import org.junit.Assert.*
import org.junit.Test

class InputReportQueueTest {
    @Test fun ctrlClickPreservesModifierAndClickOrderWithinOneTick() {
        val queue = InputReportQueue()
        queue.keyboard(KeyboardReport(1))
        queue.mouse(MouseReport(1))
        queue.mouse(MouseReport())
        queue.keyboard(KeyboardReport())
        val reports = queue.drain()
        assertEquals(listOf(1, 1, 1, 0), reports.map { it.keyboard.modifier })
        assertEquals(listOf(0, 1, 0, 0), reports.map { it.mouse.buttons })
        assertEquals(listOf(InputReport(KeyboardReport(), MouseReport())), queue.drain())
    }

    @Test fun movementIsCoalescedBetweenButtonTransitionsButClicksRemainDistinct() {
        val queue = InputReportQueue()
        repeat(10) { queue.mouse(MouseReport(dx = 3, dy = -2)) }
        queue.mouse(MouseReport(1))
        repeat(10) { queue.mouse(MouseReport(1, dx = 1)) }
        queue.mouse(MouseReport())
        val reports = queue.drain()
        assertEquals(3, reports.size)
        assertEquals(listOf(0, 1, 0), reports.map { it.mouse.buttons })
        assertEquals(40, reports.sumOf { it.mouse.dx })
        assertEquals(-20, reports.sumOf { it.mouse.dy })
    }

    @Test fun mouseDeltasAreNotRepeatedOnIdleFramesAndHeldButtonsAreRepeated() {
        val queue = InputReportQueue()
        queue.mouse(MouseReport(1, 10, 20, 1, -1))
        queue.drain()
        assertEquals(listOf(InputReport(KeyboardReport(), MouseReport(1))), queue.drain())
    }

    @Test fun reconnectResetDropsOldMotionClicksAndModifiers() {
        val queue = InputReportQueue()
        queue.keyboard(KeyboardReport(1, listOf(4)))
        queue.mouse(MouseReport(3, 40, 50, 2, 3))
        queue.reset(KeyboardReport())
        assertEquals(listOf(InputReport(KeyboardReport(), MouseReport())), queue.drain())
    }

    @Test fun serializedFramesKeepVirtualButtonsAndGamepadWhileMouseAndKeyboardRelease() {
        val queue = InputReportQueue()
        queue.keyboard(KeyboardReport(2, listOf(4)))
        queue.mouse(MouseReport(1, 20, -10, 1, -2))
        queue.mouse(MouseReport())
        queue.keyboard(KeyboardReport())
        val input = GamepadInput.newBuilder().setButtons(0x1000).setLeftStickX(5000)
            .setMouseButtons(1 or 4).setMouseDx(7).setMouseDy(9).setMouseWheel(2).build()
        val frames = queue.drain().mapIndexed { index, report ->
            GamepadInput.parseFrom(input.withExternalInput(report, index == 0).toByteArray())
        }
        assertTrue(frames.all { it.buttons == 0x1000 && it.leftStickX == 5000 && it.mouseButtons == 5 })
        assertEquals(27, frames.sumOf { it.mouseDx })
        assertEquals(-1, frames.sumOf { it.mouseDy })
        assertEquals(3, frames.sumOf { it.mouseWheel })
        assertEquals(-2, frames.sumOf { it.mousePan })
        assertEquals(0, frames.last().keyboardModifiers)
        assertEquals(0, frames.last().pressedScanCodesCount)
    }
}
