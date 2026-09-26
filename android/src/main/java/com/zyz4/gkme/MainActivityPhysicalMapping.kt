package com.zyz4.gkme

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.zyz4.gkme.model.PhysicalInput
import com.zyz4.gkme.model.PhysicalInputKind
import com.zyz4.gkme.model.PhysicalInputMapping
import com.zyz4.gkme.model.PhysicalInputs
import com.zyz4.gkme.view.FlowLayout

private const val COLOR_TEXT_PRIMARY = 0xFFFFFFFF.toInt()
private const val COLOR_TEXT_SECONDARY = 0xFF888888.toInt()
private const val COLOR_BG_SURFACE = 0xFF121212.toInt()

/** Buttons the selected (or first connected) controller exposes; falls back to the standard
 *  set so the page stays configurable while no controller is attached. */
internal fun MainActivity.currentSupportedPhysicalButtons(): Int {
    val controllers = physicalControllerHandler.connectedControllers.value
    val index = viewModel.settings.value.inputControllerIndex
    val info = controllers.getOrNull(index) ?: controllers.firstOrNull()
    val mask = info?.supportedButtons ?: 0
    return if (mask != 0) mask else PhysicalInputs.STANDARD_BUTTON_MASK
}

/** Persists the physical-controller remapping into the layout being edited. */
internal fun MainActivity.writePhysicalMapping(key: String, outputs: List<Int>, gyroActivate: Boolean) {
    val updated = gamepadLayout.currentPhysicalInputMappings.toMutableMap()
    val isDefault = outputs == PhysicalInputs.defaultOutputsFor(key) && !gyroActivate
    if (isDefault) updated.remove(key)
    else updated[key] = PhysicalInputMapping(outputs = outputs, gyroActivate = gyroActivate)
    gamepadLayout.setPhysicalInputMappings(updated)
    viewModel.updatePresetButtons(gamepadLayout.getPreset())
}

internal fun MainActivity.updatePhysicalMappingOutputs(key: String, outputs: List<Int>) {
    val gyro = gamepadLayout.currentPhysicalInputMappings[key]?.gyroActivate ?: false
    writePhysicalMapping(key, outputs, gyro)
}

internal fun MainActivity.updatePhysicalMappingGyro(key: String, enabled: Boolean) {
    val outputs = gamepadLayout.currentPhysicalInputMappings[key]?.outputs
        ?: PhysicalInputs.defaultOutputsFor(key)
    writePhysicalMapping(key, outputs, enabled)
}

internal fun MainActivity.resetPhysicalMapping(key: String) {
    val gyro = gamepadLayout.currentPhysicalInputMappings[key]?.gyroActivate ?: false
    writePhysicalMapping(key, PhysicalInputs.defaultOutputsFor(key), gyro)
}

/**
 * Rebuilds the physical-controller mapping list into [container]: one card per input, each with a
 * volume-key-style output row plus a separate "用于激活陀螺仪" checkbox row. Joysticks and the
 * touchpad only get the checkbox.
 */
internal fun MainActivity.populatePhysicalMapping(container: LinearLayout) {
    val a = this
    val density = a.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()
    container.removeAllViews()

    val supported = a.currentSupportedPhysicalButtons()
    val mappings = a.gamepadLayout.currentPhysicalInputMappings
    fun refresh() = a.populatePhysicalMapping(container)

    for (input in PhysicalInputs.ALL) {
        val isButton = input.kind == PhysicalInputKind.BUTTON
        if (isButton && (input.bitMask and supported) == 0) continue

        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG_SURFACE)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        container.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) })

        val gyroChecked = mappings[input.key]?.gyroActivate == true

        if (isButton) {
            val outputs = mappings[input.key]?.outputs ?: input.defaultOutputs
            card.addView(
                a.buildPhysicalMappingRow(input, outputs, density, ::refresh),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            card.addView(
                a.buildPhysicalGyroCheckbox(input, gyroChecked, density, ::refresh),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(2) },
            )
        } else {
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                TextView(a).apply {
                    text = input.label
                    setTextColor(COLOR_TEXT_PRIMARY)
                    textSize = 13f
                },
                LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            row.addView(
                a.buildPhysicalGyroCheckbox(input, gyroChecked, density, ::refresh),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            card.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
    }
}

private fun MainActivity.buildPhysicalMappingRow(
    input: PhysicalInput,
    outputs: List<Int>,
    density: Float,
    onChanged: () -> Unit,
): View {
    val a = this
    fun dp(v: Int) = (v * density).toInt()
    val row = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    row.addView(
        TextView(a).apply {
            text = input.label
            setTextColor(COLOR_TEXT_PRIMARY)
            textSize = 13f
        },
        LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT),
    )

    val chips = FlowLayout(a)
    row.addView(
        chips,
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) },
    )
    a.fillPhysicalMappingChips(chips, outputs, density) { removed ->
        a.updatePhysicalMappingOutputs(input.key, outputs - removed)
        onChanged()
    }

    row.addView(
        Button(a, null, 0, R.style.GamepadChip).apply {
            text = "添加"
            setOnClickListener {
                a.showOutputValuePicker(outputs) { newBits ->
                    a.updatePhysicalMappingOutputs(input.key, newBits)
                    onChanged()
                }
            }
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)),
    )

    row.addView(
        Button(a, null, 0, R.style.GamepadChip).apply {
            text = "清空"
            setOnClickListener {
                a.updatePhysicalMappingOutputs(input.key, emptyList())
                onChanged()
            }
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { leftMargin = dp(4) },
    )

    row.addView(
        Button(a, null, 0, R.style.GamepadChip).apply {
            text = "重置"
            setOnClickListener {
                a.resetPhysicalMapping(input.key)
                onChanged()
            }
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { leftMargin = dp(4) },
    )

    return row
}

private fun MainActivity.buildPhysicalGyroCheckbox(
    input: PhysicalInput,
    checked: Boolean,
    density: Float,
    onChanged: () -> Unit,
): CheckBox = CheckBox(this).apply {
    text = "用于激活陀螺仪"
    setTextColor(COLOR_TEXT_SECONDARY)
    textSize = 13f
    isChecked = checked
    setPadding(0, 0, 0, 0)
    setOnCheckedChangeListener { _, value ->
        updatePhysicalMappingGyro(input.key, value)
        onChanged()
    }
}

private fun MainActivity.fillPhysicalMappingChips(
    container: FlowLayout,
    outputs: List<Int>,
    density: Float,
    onRemove: (Int) -> Unit,
) {
    container.removeAllViews()
    if (outputs.isEmpty()) {
        container.addView(
            TextView(this).apply {
                text = "未映射"
                setTextColor(COLOR_TEXT_SECONDARY)
                textSize = 12f
            },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        return
    }
    for (bit in outputs) {
        val chip = TextView(this).apply {
            text = BitNameMapper.getBitName(bit)
            setTextColor(COLOR_TEXT_PRIMARY)
            textSize = 11f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_chip)
            val h = (4f * density).toInt()
            val w = (6f * density).toInt()
            setPadding(w, h, w, h)
            setOnClickListener { onRemove(bit) }
        }
        container.addView(
            chip,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }
}
