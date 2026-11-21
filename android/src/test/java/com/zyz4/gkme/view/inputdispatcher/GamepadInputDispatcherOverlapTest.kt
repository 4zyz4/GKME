package com.zyz4.gkme.view.inputdispatcher

import android.graphics.Rect
import android.view.MotionEvent
import com.zyz4.gkme.model.ButtonPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑模式下的重叠控件澄清选择：
 * - 轻点重叠区域 → 记录全部候选（自顶向下），抬手请求澄清；
 * - 直接拖拽 → 已选中的控件优先，其次最顶层，且不再弹出选择器。
 */
class GamepadInputDispatcherOverlapTest {

    private val dispatcher = GamepadInputDispatcher(
        layoutEngine = LayoutEngine,
        hitResolver = HitResolver,
        followAreaStrategy = FollowAreaStrategy,
        swipeTriggerStrategy = SwipeTriggerStrategy,
        slotMatcher = SlotMatcher,
        clickDetector = TouchpadClickDetector,
    )

    private val top = ButtonPosition("top", x = 5, y = 5, width = 10, height = 10)
    private val bottom = ButtonPosition("bottom", x = 0, y = 0, width = 20, height = 20)

    /** 本地单测里 android.graphics.Rect 的构造器是 stub，直接写字段保证几何有效。 */
    private fun rect(left: Int, top: Int, right: Int, bottom: Int): Rect =
        Rect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }

    /** bounds 的迭代顺序 = z 轴顺序，最顶层在前。 */
    private val topFirstBounds = linkedMapOf(
        "top" to rect(50, 50, 150, 150),
        "bottom" to rect(0, 0, 200, 200),
    )

    private fun down(x: Float = 100f, y: Float = 100f): RawTouchEvent = RawTouchEvent(
        action = MotionEvent.ACTION_DOWN,
        actionIndex = 0,
        pointers = listOf(Pointer(0, x, y)),
        buttonState = 0,
    )

    private fun move(x: Float, y: Float): RawTouchEvent = RawTouchEvent(
        action = MotionEvent.ACTION_MOVE,
        actionIndex = 0,
        pointers = listOf(Pointer(0, x, y)),
        buttonState = 0,
    )

    private fun up(x: Float = 100f, y: Float = 100f): RawTouchEvent = RawTouchEvent(
        action = MotionEvent.ACTION_UP,
        actionIndex = 0,
        pointers = listOf(Pointer(0, x, y)),
        buttonState = 0,
    )

    private fun dispatch(
        event: RawTouchEvent,
        buttons: List<ButtonPosition> = listOf(top, bottom),
        bounds: Map<String, Rect> = topFirstBounds,
        state: EditModeState = EditModeState(),
        selected: Set<String> = emptySet(),
    ): EditDispatchResult = dispatcher.dispatchEdit(
        event = event,
        buttons = buttons,
        currentButtonBounds = bounds,
        children = emptyList(),
        currentState = state,
        cellW = 10f,
        cellH = 10f,
        density = 1f,
        selectedButtonId = selected.firstOrNull(),
        selectedButtonIds = selected,
    )

    @Test
    fun `down on overlap records all candidates topmost first`() {
        val result = dispatch(down())

        assertEquals(listOf("top", "bottom"), result.newState.clarifyCandidates)
        // 未选中任何控件时，拖拽目标是视觉最上层的控件。
        assertEquals("top", result.newState.childDragStart?.buttonId)
        assertNull(result.clarifyRequest)
    }

    @Test
    fun `down on overlap prefers the already selected control`() {
        val result = dispatch(down(), selected = setOf("bottom"))

        assertEquals("bottom", result.newState.childDragStart?.buttonId)
        assertEquals(listOf("top", "bottom"), result.newState.clarifyCandidates)
    }

    @Test
    fun `tap on overlap requests clarification and keeps selection untouched`() {
        val downResult = dispatch(down())

        val upResult = dispatch(up(), state = downResult.newState, selected = setOf("bottom"))

        val request = upResult.clarifyRequest
        assertNotNull(request)
        assertEquals(listOf("top", "bottom"), request!!.candidateIds)
        assertEquals(100f, request.x, 0.01f)
        assertEquals(100f, request.y, 0.01f)
        assertTrue(upResult.commands.isEmpty())
        assertTrue(upResult.newState.clarifyCandidates.isEmpty())
        assertNull(upResult.newState.childDragStart)
    }

    @Test
    fun `small jitter below slop keeps clarification pending`() {
        val downResult = dispatch(down())

        val moveResult = dispatch(move(104f, 104f), state = downResult.newState)

        assertTrue(moveResult.commands.isEmpty())
        assertEquals(listOf("top", "bottom"), moveResult.newState.clarifyCandidates)
    }

    @Test
    fun `dragging past slop commits the topmost target`() {
        val downResult = dispatch(down())

        val moveResult = dispatch(move(130f, 130f), state = downResult.newState)

        val command = moveResult.commands.filterIsInstance<EditCommand.MoveButton>().single()
        assertEquals("top", command.id)
        assertEquals(8, command.gridX)
        assertEquals(8, command.gridY)
        assertTrue(moveResult.newState.clarifyCandidates.isEmpty())

        val upResult = dispatch(up(130f, 130f), state = moveResult.newState)
        assertNull(upResult.clarifyRequest)
    }

    @Test
    fun `dragging past slop commits the selected control when it is underneath`() {
        val downResult = dispatch(down(), selected = setOf("bottom"))

        val moveResult = dispatch(
            move(130f, 130f),
            state = downResult.newState,
            selected = setOf("bottom"),
        )

        val command = moveResult.commands.filterIsInstance<EditCommand.MoveButton>().single()
        assertEquals("bottom", command.id)
        assertTrue(moveResult.newState.clarifyCandidates.isEmpty())
    }

    @Test
    fun `tap on a single control does not request clarification`() {
        val single = listOf(ButtonPosition("btnA", x = 5, y = 5, width = 10, height = 10))
        val bounds = linkedMapOf("btnA" to rect(50, 50, 150, 150))

        val downResult = dispatch(down(), buttons = single, bounds = bounds)
        val upResult = dispatch(up(), buttons = single, bounds = bounds, state = downResult.newState)

        assertTrue(downResult.newState.clarifyCandidates.isEmpty())
        assertNull(upResult.clarifyRequest)
    }

    @Test
    fun `overlapTrigger false control is still offered in clarification`() {
        val noOverlap = bottom.copy(overlapTrigger = false)

        val downResult = dispatch(down(), buttons = listOf(top, noOverlap))

        // 参与澄清选择的是视觉上重叠的全部控件。
        assertEquals(listOf("top", "bottom"), downResult.newState.clarifyCandidates)
        // 拖拽目标仍遵循 overlapTrigger 过滤：最上层的 top 优先。
        assertEquals("top", downResult.newState.childDragStart?.buttonId)
    }
}
