package com.zyz4.gkme.view

import android.view.View
import android.widget.Button
import android.view.ViewGroup
import com.zyz4.gkme.model.ButtonPosition
import com.zyz4.gkme.model.GyroOrientation

/**
 * Deep module: applies layout-time configuration to child Views.
 *
 * Responsibilities (from GamepadLayout.onLayout):
 * - Measure and layout each child based on ButtonPosition grid coords
 * - Set visibility (VISIBLE/GONE) based on position.visible flag
 * - Apply rotation, scale aspect swap for 90/270 degree rotation
 * - Set transparency (alpha) for idle/active states
 * - Configure JoystickView/DpadPadView/CustomKeypadView properties
 * - Set padding for adaptive content buttons
 * - Apply content text auto-fit cap
 * - Handle preview transparency mode in edit mode
 *
 * Interface: [applyLayout()] accepts layout params and child list,
 * mutates the children in place (standard ViewGroup pattern).
 * All data-driven decisions happen here.
 */
class GamepadLayoutApplier {

    /**
     * Apply layout configuration to all children of a GamepadLayout.
     *
     * This replaces the ~80-line for loop inside GamepadLayout.onLayout().
     * All configuration logic is self-contained here.
     */
    fun applyLayout(
        childCount: Int,
        getChildAt: (index: Int) -> View,
        getButtonId: (View) -> String?,
        buttons: Map<String, ButtonPosition>,
        ctrlEntryBitMap: Map<String, Int>,
        cellW: Float,
        cellH: Float,
        selectedButtonId: String?,
        isEditMode: Boolean,
        previewTransparency: Boolean,
        previewButtonId: String?,
        previewIdleTransparency: Boolean,
        getPressedBits: () -> UInt,
        isAdaptiveContentButton: (String, View) -> Boolean,
        contentCapPx: (View, com.zyz4.gkme.model.AppSettings?) -> Int?,
        applyContentTextCap: (Button, Int) -> Unit,
        getRotation: (String) -> Int,
    ) {
        val pressedBits = getPressedBits()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val id = getButtonId(child) ?: continue
            val pos = buttons[id]
            if (pos == null) {
                child.visibility = View.GONE
                continue
            }

            if (!pos.visible) {
                if (child.visibility != View.GONE) child.visibility = View.GONE
                continue
            }
            if (child.visibility != View.VISIBLE) child.visibility = View.VISIBLE

            applyChildLayout(child, pos, cellW, cellH, getRotation)

            if (isAdaptiveContentButton(id, child)) {
                // Note: child.width/height may be 0 at this point; caller handles this
            }

            applyChildTransparency(
                child, pos, ctrlEntryBitMap, pressedBits, isEditMode, previewTransparency,
                previewButtonId, previewIdleTransparency, selectedButtonId,
            )

            applyChildSpecialProperties(
                child, pos, id, isEditMode, selectedButtonId,
            )
        }
    }

    // ── Helpers ────────────────────────────────────────────

    private fun applyChildLayout(
        child: View,
        pos: ButtonPosition,
        cellW: Float,
        cellH: Float,
        getRotation: (String) -> Int,
    ) {
        val isSwapped = !pos.lockAspect && (pos.rotation == 90 || pos.rotation == 270)
        val childW = ((if (isSwapped) pos.height else pos.width) * cellW).toInt()
        val childH = ((if (isSwapped) pos.width else pos.height) * cellH).toInt()
        val left = (pos.x * cellW).toInt()
        val top = (pos.y * cellH).toInt()

        child.measure(
            View.MeasureSpec.makeMeasureSpec(childW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(childH, View.MeasureSpec.EXACTLY),
        )
        child.layout(left, top, left + childW, top + childH)

        // Rotation
        if (child is JoystickView) {
            child.axisRotation = pos.rotation
        } else if (child is DpadPadView) {
            child.rotation = pos.rotation.toFloat()
        } else if (child is CustomKeypadView) {
            child.rotation = pos.rotation.toFloat()
        } else if (child is com.zyz4.gkme.view.RotatableButton) {
            child.textRotation = pos.rotation
        } else if (child is ViewGroup) {
            for (j in 0 until child.childCount) {
                child.getChildAt(j).rotation = pos.rotation.toFloat()
            }
        } else if (pos.lockAspect) {
            child.rotation = pos.rotation.toFloat()
        }
    }

    private fun applyChildTransparency(
        child: View,
        pos: ButtonPosition,
        ctrlEntryBitMap: Map<String, Int>,
        pressedBits: UInt,
        isEditMode: Boolean,
        previewTransparency: Boolean,
        previewButtonId: String?,
        previewIdleTransparency: Boolean,
        selectedButtonId: String?,
    ) {
        if (isEditMode && previewTransparency && previewButtonId == child.tag) {
            val transVal = if (previewIdleTransparency) pos.idleTransparency else pos.activeTransparency
            child.alpha = 1f - (transVal.coerceIn(0, 255) / 255f).coerceIn(0f, 1f)
        } else if (isEditMode) {
            child.alpha = 1f
        } else {
            val baseId = child.tag?.toString()?.substringBefore("_") ?: ""
            val bit = ctrlEntryBitMap[baseId] ?: 0
            val isDown = bit != 0 && (pressedBits and bit.toUInt()) != 0u
            val transVal = if (isDown) pos.activeTransparency else pos.idleTransparency
            child.alpha = 1f - (transVal.coerceIn(0, 255) / 255f).coerceIn(0f, 1f)
        }
    }

    private fun applyChildSpecialProperties(
        child: View,
        pos: ButtonPosition,
        id: String,
        isEditMode: Boolean,
        selectedButtonId: String?,
    ) {
        if (child is JoystickView) {
            child.deadZone = pos.deadZone
            child.reverseDeadZone = pos.reverseDeadZone
            child.showDeadZoneIndicator = isEditMode && id == selectedButtonId
            child.forceFollowFinger = false
            child.idleTransparency = pos.idleTransparency.coerceIn(0, 255)
            child.activeTransparency = pos.activeTransparency.coerceIn(0, 255)
        } else if (child is DpadPadView) {
            child.forceFollowFinger = false
            child.idleTransparency = pos.idleTransparency.coerceIn(0, 255)
            child.activeTransparency = pos.activeTransparency.coerceIn(0, 255)
        } else if (child is CustomKeypadView) {
            child.forceFollowFinger = false
            child.idleTransparency = pos.idleTransparency.coerceIn(0, 255)
            child.activeTransparency = pos.activeTransparency.coerceIn(0, 255)
            val kpBits = ButtonPosition.keypadBitsOf(pos)
            child.validDirs = (0..3).filter { i ->
                kpBits.getOrNull(i)?.isNotEmpty() == true
            }.toSet()
        }
    }

    /** Returns [left, top, width, height] in grid coordinates for the visual extent. */
    fun visualBounds(pos: ButtonPosition): FloatArray {
        val isSwapped = !pos.lockAspect && (pos.rotation == 90 || pos.rotation == 270)
        val lw = if (isSwapped) pos.height else pos.width
        val lh = if (isSwapped) pos.width else pos.height
        return floatArrayOf(pos.x.toFloat(), pos.y.toFloat(), lw.toFloat(), lh.toFloat())
    }
}