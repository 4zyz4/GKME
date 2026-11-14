package com.zyz4.gkme.input

import com.zyz4.gkme.model.MouseGestureAction
import com.zyz4.gkme.model.TouchPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for [PhysicalTouchpadGestureRecognizer]. */
class PhysicalTouchpadGestureTest {

    private val recognizer = PhysicalTouchpadGestureRecognizer()

    private fun touch(id: Int, x: Int, y: Int) = TouchPoint(id = id, x = x, y = y, active = true)

    private fun events(
        touches: List<TouchPoint>,
        nowMs: Long,
        config: PhysicalTouchpadGestureRecognizer.Config =
            PhysicalTouchpadGestureRecognizer.Config(),
    ) = recognizer.update(touches, nowMs, config)

    @Test
    fun singleTap_emitsLeftClick() {
        assertTrue(events(listOf(touch(0, 100, 100)), 1000).isEmpty())
        val result = events(emptyList(), 1050)
        assertEquals(
            listOf(PhysicalTouchpadGestureRecognizer.Event.Tap(MouseGestureAction.LEFT_CLICK)),
            result,
        )
    }

    @Test
    fun twoFingerTap_emitsRightClick() {
        events(listOf(touch(0, 100, 100), touch(1, 300, 100)), 1000)
        val result = events(emptyList(), 1080)
        assertEquals(
            listOf(PhysicalTouchpadGestureRecognizer.Event.Tap(MouseGestureAction.RIGHT_CLICK)),
            result,
        )
    }

    @Test
    fun oneFingerSwipe_movesCursor() {
        events(listOf(touch(0, 100, 100)), 1000)
        val move = events(listOf(touch(0, 220, 160)), 1010)
        assertEquals(
            listOf(PhysicalTouchpadGestureRecognizer.Event.Move(120, 60)),
            move,
        )
        val release = events(emptyList(), 1020)
        assertTrue(release.none { it is PhysicalTouchpadGestureRecognizer.Event.Tap })
    }

    @Test
    fun twoFingerSwipe_scrollsByCentroid() {
        events(listOf(touch(0, 100, 100), touch(1, 300, 100)), 1000)
        val result = events(
            listOf(touch(0, 140, 100), touch(1, 340, 100)),
            1010,
        )
        // 平均位移 40，滚轮 40 × 0.5 = 20；横向默认自然滚动为负值。
        assertEquals(
            listOf(PhysicalTouchpadGestureRecognizer.Event.Scroll(0, -20)),
            result,
        )
    }

    @Test
    fun twoFingerSwipe_respectsInvertAndSensitivity() {
        val config = PhysicalTouchpadGestureRecognizer.Config(
            scrollSensitivity = 2f,
            invertScrollV = true,
            invertScrollH = true,
        )
        events(listOf(touch(0, 100, 100), touch(1, 300, 100)), 1000, config)
        val result = events(
            listOf(touch(0, 140, 120), touch(1, 340, 120)),
            1010,
            config,
        )
        assertEquals(
            listOf(PhysicalTouchpadGestureRecognizer.Event.Scroll(-20, 40)),
            result,
        )
    }

    @Test
    fun smallMovement_stillCountsAsTap() {
        events(listOf(touch(0, 100, 100)), 1000)
        events(listOf(touch(0, 104, 100)), 1010)
        val result = events(emptyList(), 1060)
        assertEquals(
            listOf(PhysicalTouchpadGestureRecognizer.Event.Tap(MouseGestureAction.LEFT_CLICK)),
            result,
        )
    }

    @Test
    fun longPress_isNotATap() {
        events(listOf(touch(0, 100, 100)), 1000)
        val result = events(emptyList(), 1400)
        assertTrue(result.isEmpty())
    }

    @Test
    fun dragSwipe_pressesAndReleasesTheButton() {
        val config = PhysicalTouchpadGestureRecognizer.Config(
            oneFingerSwipeAction = MouseGestureAction.LEFT_DRAG,
        )
        events(listOf(touch(0, 100, 100)), 1000, config)
        val move = events(listOf(touch(0, 220, 100)), 1010, config)
        assertEquals(
            listOf(
                PhysicalTouchpadGestureRecognizer.Event.DragStart(MouseGestureAction.LEFT_DRAG),
                PhysicalTouchpadGestureRecognizer.Event.Move(120, 0),
            ),
            move,
        )
        assertEquals(
            listOf(PhysicalTouchpadGestureRecognizer.Event.DragEnd(MouseGestureAction.LEFT_DRAG)),
            events(emptyList(), 1020, config),
        )
    }

    @Test
    fun doubleTapDrag_armsOnSecondDownAndDrags() {
        val config = PhysicalTouchpadGestureRecognizer.Config(
            doubleTapDragAction = MouseGestureAction.LEFT_DRAG,
        )
        events(listOf(touch(0, 100, 100)), 1000, config)
        events(emptyList(), 1050, config)
        val secondDown = events(listOf(touch(0, 100, 100)), 1200, config)
        assertEquals(
            listOf(PhysicalTouchpadGestureRecognizer.Event.DragStart(MouseGestureAction.LEFT_DRAG)),
            secondDown,
        )
        val move = events(listOf(touch(0, 160, 100)), 1210, config)
        assertEquals(listOf(PhysicalTouchpadGestureRecognizer.Event.Move(60, 0)), move)
        assertEquals(
            listOf(PhysicalTouchpadGestureRecognizer.Event.DragEnd(MouseGestureAction.LEFT_DRAG)),
            events(emptyList(), 1300, config),
        )
    }

    @Test
    fun doubleTapDrag_disabledWhenActionIsNone() {
        val config = PhysicalTouchpadGestureRecognizer.Config(
            doubleTapDragAction = MouseGestureAction.NONE,
        )
        events(listOf(touch(0, 100, 100)), 1000, config)
        events(emptyList(), 1050, config)
        val secondDown = events(listOf(touch(0, 100, 100)), 1200, config)
        assertTrue(secondDown.isEmpty())
    }

    @Test
    fun reset_releasesActiveDrag() {
        val config = PhysicalTouchpadGestureRecognizer.Config(
            oneFingerSwipeAction = MouseGestureAction.RIGHT_DRAG,
        )
        events(listOf(touch(0, 100, 100)), 1000, config)
        events(listOf(touch(0, 200, 100)), 1010, config)
        assertEquals(
            listOf(PhysicalTouchpadGestureRecognizer.Event.DragEnd(MouseGestureAction.RIGHT_DRAG)),
            recognizer.reset(),
        )
    }
}
