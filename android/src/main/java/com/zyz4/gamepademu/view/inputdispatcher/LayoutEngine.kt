package com.zyz4.gamepademu.view.inputdispatcher

import com.zyz4.gamepademu.model.ButtonPosition

/**
 * Pure grid layout computation. Zero Android dependencies.
 *
 * Given a list of ButtonPosition and grid dimensions, computes:
 * - Pixel bounding rects for each button
 * - Visibility flags (visible vs gone)
 *
 * What it does NOT include:
 * - normaliseTouchpadArea() follow-area auto-expand (edit-mode domain)
 * - AppearanceApplier text-cap logic (appearance module domain)
 * - MeasureSpec construction (caller builds from bounds)
 * - Alpha/rotation/child-specific transforms (onLayout concerns)
 */
object LayoutEngine {

    const val GRID_COLS = 120

    /**
     * Compute layout geometry for the given button positions.
     *
     * @param buttons current list of button positions
     * @param gridRows number of rows at the current aspect ratio (height / cellSize)
     * @param cellSizePx size of one grid cell in pixels (cellW == cellH)
     * @return layout result with pixel bounds and visibility
     */
    fun layout(
        buttons: List<ButtonPosition>,
        gridRows: Int,
        cellSizePx: Float,
    ): LayoutResult {
        val bounds = mutableMapOf<String, android.graphics.Rect>()
        val visible = mutableSetOf<String>()
        val gone = mutableSetOf<String>()

        for (pos in buttons) {
            val isSwapped = !pos.lockAspect && (pos.rotation == 90 || pos.rotation == 270)
            val gridW = if (isSwapped) pos.height else pos.width
            val gridH = if (isSwapped) pos.width else pos.height

            val left = (pos.x * cellSizePx).toInt()
            val top = (pos.y * cellSizePx).toInt()
            val right = ((pos.x + gridW) * cellSizePx).toInt()
            val bottom = ((pos.y + gridH) * cellSizePx).toInt()

            bounds[pos.id] = android.graphics.Rect(left, top, right, bottom)

            if (!pos.visible) {
                gone.add(pos.id)
            } else {
                visible.add(pos.id)
            }
        }

        return LayoutResult(
            bounds = bounds,
            visibleButtons = visible,
            goneButtons = gone,
        )
    }

    /** Calculate gridRows from container height and cell size. */
    fun computeGridRows(containerHeight: Int, cellSizePx: Float): Int {
        return if (cellSizePx > 0f) (containerHeight / cellSizePx).toInt() + 1 else GRID_COLS
    }

    /** Calculate visual screen size in grid units accounting for rotation swap. */
    fun screenGridSize(pos: ButtonPosition): Pair<Int, Int> {
        val isSwapped = !pos.lockAspect && (pos.rotation == 90 || pos.rotation == 270)
        return if (isSwapped) pos.height to pos.width else pos.width to pos.height
    }

    /** Get the visual bounds [x, y, w, h] in grid coordinates. */
    fun visualBoundsGrid(pos: ButtonPosition): FloatArray {
        val isSwapped = !pos.lockAspect && (pos.rotation == 90 || pos.rotation == 270)
        val lw = if (isSwapped) pos.height else pos.width
        val lh = if (isSwapped) pos.width else pos.height
        return floatArrayOf(pos.x.toFloat(), pos.y.toFloat(), lw.toFloat(), lh.toFloat())
    }

    /** Check if a point is within a button's visual bounds (grid coordinates). */
    fun isInVisualBounds(x: Float, y: Float, pos: ButtonPosition, cellSizePx: Float): Boolean {
        val vb = visualBoundsGrid(pos)
        return x >= vb[0] * cellSizePx && x <= (vb[0] + vb[2]) * cellSizePx &&
            y >= vb[1] * cellSizePx && y <= (vb[1] + vb[3]) * cellSizePx
    }
}