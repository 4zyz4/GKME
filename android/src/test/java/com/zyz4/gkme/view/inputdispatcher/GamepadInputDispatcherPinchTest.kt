package com.zyz4.gkme.view.inputdispatcher

import android.view.MotionEvent
import com.zyz4.gkme.model.ButtonPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadInputDispatcherPinchTest {

    private val dispatcher = GamepadInputDispatcher(
        layoutEngine = LayoutEngine,
        hitResolver = HitResolver,
        followAreaStrategy = FollowAreaStrategy,
        swipeTriggerStrategy = SwipeTriggerStrategy,
        slotMatcher = SlotMatcher,
        clickDetector = TouchpadClickDetector,
    )

    private fun pinchDown(vararg points: Pointer): RawTouchEvent = RawTouchEvent(
        action = MotionEvent.ACTION_POINTER_DOWN,
        actionIndex = points.lastIndex,
        pointers = points.toList(),
        buttonState = 0,
    )

    private fun move(vararg points: Pointer): RawTouchEvent = RawTouchEvent(
        action = MotionEvent.ACTION_MOVE,
        actionIndex = 0,
        pointers = points.toList(),
        buttonState = 0,
    )

    private fun pointerUp(index: Int, vararg points: Pointer): RawTouchEvent = RawTouchEvent(
        action = MotionEvent.ACTION_POINTER_UP,
        actionIndex = index,
        pointers = points.toList(),
        buttonState = 0,
    )

    private fun dispatch(
        event: RawTouchEvent,
        buttons: List<ButtonPosition>,
        state: EditModeState,
        selected: Set<String>,
    ): EditDispatchResult = dispatcher.dispatchEdit(
        event = event,
        buttons = buttons,
        currentButtonBounds = emptyMap(),
        children = emptyList(),
        currentState = state,
        cellW = 10f,
        cellH = 10f,
        density = 1f,
        selectedButtonId = selected.firstOrNull(),
        selectedButtonIds = selected,
    )

    @Test
    fun `pinch scales aspect-locked control uniformly`() {
        val buttons = listOf(
            ButtonPosition("btnA", x = 10, y = 10, width = 20, height = 20, lockAspect = true),
        )
        val state = EditModeState(childDragStart = ChildDragStart("btnA", 0f, 0f))
        val selected = setOf("btnA")

        val down = dispatch(
            pinchDown(Pointer(0, 100f, 100f), Pointer(1, 200f, 100f)),
            buttons, state, selected,
        )
        val start = down.newState.pinchResizeStart
        assertNotNull(start)
        assertEquals(100f, start!!.startDistance, 0.01f)
        assertEquals("btnA", start.targets.single().buttonId)

        // 指距翻倍 → 尺寸翻倍（20 → 40）。
        val moved = dispatch(
            move(Pointer(0, 100f, 100f), Pointer(1, 300f, 100f)),
            buttons, down.newState, selected,
        )
        val command = moved.commands.single() as EditCommand.ResizeButton
        assertEquals("btnA", command.id)
        assertEquals(40, command.gridWidth)
        assertEquals(40, command.gridHeight)
    }

    @Test
    fun `rectangular control scales width and height independently`() {
        val buttons = listOf(
            ButtonPosition("btnCustomRect", x = 10, y = 10, width = 20, height = 10),
        )
        val state = EditModeState(childDragStart = ChildDragStart("btnCustomRect", 0f, 0f))
        val selected = setOf("btnCustomRect")

        val down = dispatch(
            pinchDown(Pointer(0, 100f, 100f), Pointer(1, 300f, 200f)),
            buttons, state, selected,
        )
        val start = down.newState.pinchResizeStart!!
        assertEquals(200f, start.startSpanX, 0.01f)
        assertEquals(100f, start.startSpanY, 0.01f)

        // 只拉开 y 轴指距（100 → 300）：高度 10 → 30，宽度保持 20。
        val moved = dispatch(
            move(Pointer(0, 100f, 100f), Pointer(1, 300f, 400f)),
            buttons, down.newState, selected,
        )
        val command = moved.commands.single() as EditCommand.ResizeButton
        assertEquals(20, command.gridWidth)
        assertEquals(30, command.gridHeight)
    }

    @Test
    fun `rectangular control maps screen axes through rotation`() {
        // 旋转 90° 后屏幕宽度来自存储高度，屏幕高度来自存储宽度。
        val buttons = listOf(
            ButtonPosition(
                "touchpad", x = 10, y = 10, width = 20, height = 10,
                rotation = 90, lockAspect = false,
            ),
        )
        val state = EditModeState(childDragStart = ChildDragStart("touchpad", 0f, 0f))
        val selected = setOf("touchpad")

        val down = dispatch(
            pinchDown(Pointer(0, 100f, 100f), Pointer(1, 100f, 300f)),
            buttons, state, selected,
        )
        // 只拉开 y 轴指距（200 → 400）：屏幕高度翻倍，对应存储宽度 20 → 40。
        val moved = dispatch(
            move(Pointer(0, 100f, 100f), Pointer(1, 100f, 500f)),
            buttons, down.newState, selected,
        )
        val command = moved.commands.single() as EditCommand.ResizeButton
        assertEquals(40, command.gridWidth)
        assertEquals(10, command.gridHeight)
    }

    @Test
    fun `pinch moves the control with the finger focal point`() {
        val buttons = listOf(
            ButtonPosition("btnCustomRect", x = 10, y = 10, width = 20, height = 10),
        )
        val state = EditModeState(childDragStart = ChildDragStart("btnCustomRect", 0f, 0f))
        val selected = setOf("btnCustomRect")

        // 两指保持间距整体右移 100px：控件应跟着右移 10 格，尺寸不变。
        val down = dispatch(
            pinchDown(Pointer(0, 150f, 150f), Pointer(1, 250f, 150f)),
            buttons, state, selected,
        )
        val moved = dispatch(
            move(Pointer(0, 250f, 150f), Pointer(1, 350f, 150f)),
            buttons, down.newState, selected,
        )
        val move = moved.commands.filterIsInstance<EditCommand.MoveButton>().single()
        assertEquals(20, move.gridX)
        assertEquals(10, move.gridY)
        assertTrue(moved.commands.none { it is EditCommand.ResizeButton })
    }

    @Test
    fun `pinch keeps the control edges under the fingers`() {
        val buttons = listOf(
            ButtonPosition("btnCustomRect", x = 10, y = 10, width = 20, height = 10),
        )
        val state = EditModeState(childDragStart = ChildDragStart("btnCustomRect", 0f, 0f))
        val selected = setOf("btnCustomRect")

        // 左指按左边缘、右指按右边缘；右指拉开到 500px：
        // 左边缘应留在 100px（x=10），右边缘跟到 500px（宽 40 格）。
        val down = dispatch(
            pinchDown(Pointer(0, 100f, 150f), Pointer(1, 300f, 150f)),
            buttons, state, selected,
        )
        val moved = dispatch(
            move(Pointer(0, 100f, 150f), Pointer(1, 500f, 150f)),
            buttons, down.newState, selected,
        )
        assertTrue(moved.commands.none { it is EditCommand.MoveButton })
        val resize = moved.commands.filterIsInstance<EditCommand.ResizeButton>().single()
        assertEquals(40, resize.gridWidth)
        assertEquals(10, resize.gridHeight)
    }

    @Test
    fun `pinch on blank resizes current selection`() {
        val buttons = listOf(ButtonPosition("btnA", x = 10, y = 10, width = 20, height = 20))
        val selected = setOf("btnA")

        val down = dispatch(
            pinchDown(Pointer(0, 900f, 900f), Pointer(1, 1000f, 900f)),
            buttons, EditModeState(), selected,
        )
        assertEquals("btnA", down.newState.pinchResizeStart?.targets?.single()?.buttonId)
    }

    @Test
    fun `pinch without selection or hit does nothing`() {
        val buttons = listOf(ButtonPosition("btnA", x = 10, y = 10, width = 20, height = 20))
        val down = dispatch(
            pinchDown(Pointer(0, 900f, 900f), Pointer(1, 1000f, 900f)),
            buttons, EditModeState(), emptySet(),
        )
        assertNull(down.newState.pinchResizeStart)
        assertTrue(down.commands.isEmpty())
    }

    @Test
    fun `lifting one of three fingers reanchors the pinch`() {
        val buttons = listOf(ButtonPosition("btnA", x = 10, y = 10, width = 20, height = 20))
        val state = EditModeState(childDragStart = ChildDragStart("btnA", 0f, 0f))
        val selected = setOf("btnA")

        val down = dispatch(
            pinchDown(Pointer(0, 100f, 100f), Pointer(1, 200f, 100f), Pointer(2, 300f, 100f)),
            buttons, state, selected,
        )
        val up = dispatch(
            pointerUp(2, Pointer(0, 100f, 100f), Pointer(1, 200f, 100f), Pointer(2, 300f, 100f)),
            buttons, down.newState, selected,
        )
        assertEquals(100f, up.newState.pinchResizeStart?.startDistance ?: -1f, 0.01f)
    }

    @Test
    fun `lifting down to one finger ends the pinch`() {
        val buttons = listOf(ButtonPosition("btnA", x = 10, y = 10, width = 20, height = 20))
        val state = EditModeState(childDragStart = ChildDragStart("btnA", 0f, 0f))
        val selected = setOf("btnA")

        val down = dispatch(
            pinchDown(Pointer(0, 100f, 100f), Pointer(1, 200f, 100f)),
            buttons, state, selected,
        )
        val up = dispatch(
            pointerUp(1, Pointer(0, 100f, 100f), Pointer(1, 200f, 100f)),
            buttons, down.newState, selected,
        )
        assertNull(up.newState.pinchResizeStart)
    }
}
