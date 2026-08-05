package com.zyz4.gamepademu.view

import android.graphics.Canvas
import android.graphics.Paint
import com.zyz4.gamepademu.model.ButtonPosition
import com.zyz4.gamepademu.view.inputdispatcher.isTouchpadId

/**
 * Deep module: renders overlay elements on top of the gamepad layout.
 *
 * Responsibilities:
 * - Grid overlay (edit mode, fade-in/out)
 * - Follow-area / touchpad-area / dpadPad-area rectangles
 * - Selected button highlight (stroke + resize handle)
 * - Rotation direction marker ("上")
 *
 * Interface is small: one `render()` method + appearance setters.
 * Implementation owns all Paint objects and canvas drawing logic.
 */
class GamepadRenderer(
    private val gridCols: Int = 120,
    private val handleSizeDp: Float = 8f,
    private val gridBaseAlpha: Int = 170,
    private val markerDistDp: Float = 4f,
    private val density: Float = 1f,
) {

    // ── Paint factory (owned by implementation) ──────────────

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(120, 120, 120)
        strokeWidth = 1f
    }

    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x3300ff00
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x1
        style = Paint.Style.FILL
    }

    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x10000
        textAlign = Paint.Align.CENTER
    }

    private val followAreaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x666667
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val touchpadAreaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x666667
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val dpadPadAreaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -0x666667
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    // ── Public API ──────────────────────────────────────────

    fun setFollowAreaAppearance(color: Int, strokeWidth: Float) {
        followAreaPaint.color = color
        followAreaPaint.strokeWidth = strokeWidth
    }

    fun setTouchpadAreaAppearance(color: Int, strokeWidth: Float) {
        touchpadAreaPaint.color = color
        touchpadAreaPaint.strokeWidth = strokeWidth
    }

    fun setDpadPadTriggerAreaAppearance(color: Int, strokeWidth: Float) {
        dpadPadAreaPaint.color = color
        dpadPadAreaPaint.strokeWidth = strokeWidth
    }

    /** Grid overlay animation progress [0f, 1f]. */
    var gridAlpha: Float = 0f
        set(value) {
            field = value
        }

    /** The JOYSTICK_IDS set used to identify joystick controls. */
    var joystickIds: Set<String> = setOf("leftJoystick", "rightJoystick")
        set(value) { field = value }

    /**
     * Draw all overlay elements on the canvas.
     *
     * @param canvas target canvas
     * @param bounds layout bounds (width/height)
     * @param cellW cell width in px
     * @param cellH cell height in px
     * @param buttons all button positions
     * @param selectedButtonId currently selected button (edit mode)
     * @param isEditMode whether edit mode is active
     * @param adjustingFollowAreaId button whose follow-area is being adjusted
     * @param isAdjustingFollowArea whether currently adjusting a follow-area
     */
    fun render(
        canvas: Canvas,
        width: Int,
        height: Int,
        cellW: Float,
        cellH: Float,
        buttons: List<ButtonPosition>,
        selectedButtonId: String?,
        isEditMode: Boolean,
        adjustingFollowAreaId: String?,
        isAdjustingFollowArea: Boolean,
    ) {
        if (cellW <= 0 || cellH <= 0) return

        // ── Draw follow-area / touchpad-area / dpadPad-area rectangles ──

        for (pos in buttons) {
            if (!pos.followAreaEnabled) continue

            val isJoyArea = pos.id.substringBefore("_") in joystickIds
            val isTpArea = isTouchpadId(pos.id)
            val isDpadPadArea = pos.id == "dpadPad"
            val isKeypadArea = pos.id.substringBefore("_") == "customKeypad"

            if (!isJoyArea && !isTpArea && !isDpadPadArea && !isKeypadArea) continue

            val areaPaint = when {
                isTpArea -> touchpadAreaPaint
                isDpadPadArea || isKeypadArea -> dpadPadAreaPaint
                else -> followAreaPaint
            }

            val fLeft = pos.followAreaX * cellW
            val fTop = pos.followAreaY * cellH
            val fRight = (pos.followAreaX + pos.followAreaW) * cellW
            val fBottom = (pos.followAreaY + pos.followAreaH) * cellH

            if (areaPaint.strokeWidth > 0f) {
                areaPaint.alpha = (255 - pos.followAreaTransparency.coerceIn(0, 255)).coerceIn(0, 255)
                canvas.drawRect(fLeft, fTop, fRight, fBottom, areaPaint)
                areaPaint.alpha = 255
            }

            // Draw handle when adjusting this follow-area
            if (pos.id == adjustingFollowAreaId && isAdjustingFollowArea) {
                val handleDpPx = handleSizeDp * density
                canvas.drawRect(fRight - handleDpPx, fBottom - handleDpPx, fRight, fBottom, handlePaint)
            }
        }

        // ── Edit mode overlays ──
        if (!isEditMode) return

        drawGrid(canvas, height, cellW, cellH)
        drawSelectionOverlay(canvas, selectedButtonId, buttons, cellW, cellH, adjustingFollowAreaId, isAdjustingFollowArea)
    }

    // ── Internal helpers ─────────────────────────────────────

    private fun drawGrid(canvas: Canvas, height: Int, cellW: Float, cellH: Float) {
        gridPaint.alpha = (gridAlpha * 255).toInt().coerceIn(0, 255)
        val rows = (height / cellH).toInt() + 1
        for (col in 0..gridCols) {
            val x = col * cellW
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
        }
        for (row in 0..rows) {
            val y = row * cellH
            canvas.drawLine(0f, y, canvas.width.toFloat(), y, gridPaint)
        }
    }

    private fun drawSelectionOverlay(
        canvas: Canvas,
        selectedButtonId: String?,
        buttons: List<ButtonPosition>,
        cellW: Float,
        cellH: Float,
        adjustingFollowAreaId: String?,
        isAdjustingFollowArea: Boolean,
    ) {
        val selId = selectedButtonId ?: return
        val pos = buttons.find { it.id == selId } ?: return

        val vb = visualBounds(pos)
        val vl = vb[0] * cellW
        val vt = vb[1] * cellH
        val vbw = vb[2] * cellW
        val vbh = vb[3] * cellH

        if (!isAdjustingFollowArea) {
            selectionPaint.setStrokeWidth(3f * density)
            canvas.drawRect(vl, vt, vl + vbw, vt + vbh, selectionPaint)

            // Resize handle
            val handleDpPx = handleSizeDp * density
            canvas.drawRect(vl + vbw - handleDpPx, vt + vbh - handleDpPx, vl + vbw, vt + vbh, handlePaint)
        }

        // Rotation marker
        val markerDistPx = markerDistDp * density
        val (markX, markY, markAngle) = when (pos.rotation % 360) {
            90 -> Triple(vl + vbw + markerDistPx, vt + vbh / 2f, 90f)
            180 -> Triple(vl + vbw / 2f, vt + vbh + markerDistPx, 180f)
            270 -> Triple(vl - markerDistPx, vt + vbh / 2f, 270f)
            else -> Triple(vl + vbw / 2f, vt - markerDistPx, 0f)
        }

        if (!isAdjustingFollowArea && selId != "btnSettings") {
            markPaint.textSize = 14f * density
            canvas.save()
            canvas.rotate(markAngle, markX, markY)
            canvas.drawText("\u4e0a", markX, markY, markPaint)
            canvas.restore()
        }
    }

    private fun visualBounds(pos: ButtonPosition): FloatArray {
        val isSwapped = !pos.lockAspect && (pos.rotation == 90 || pos.rotation == 270)
        val lw = if (isSwapped) pos.height else pos.width
        val lh = if (isSwapped) pos.width else pos.height
        return floatArrayOf(pos.x.toFloat(), pos.y.toFloat(), lw.toFloat(), lh.toFloat())
    }
}