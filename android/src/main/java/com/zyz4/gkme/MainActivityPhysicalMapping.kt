package com.zyz4.gkme

import com.zyz4.gkme.R
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import com.zyz4.gkme.model.ComboTiming
import com.zyz4.gkme.model.PhysicalInput
import com.zyz4.gkme.model.PhysicalInputKind
import com.zyz4.gkme.model.PhysicalInputMapping
import com.zyz4.gkme.model.PhysicalInputs
import com.zyz4.gkme.view.FlowLayout
import com.zyz4.gkme.view.addComboTimingControls

private const val COLOR_TEXT_PRIMARY = 0xFFFFFFFF.toInt()
private const val COLOR_TEXT_SECONDARY = 0xFF888888.toInt()
private const val COLOR_BG_SURFACE = 0xFF121212.toInt()

/** A collapsible group of physical inputs on the mapping page. */
private data class PhysicalMappingGroup(
    val key: String,
    @param:StringRes val titleRes: Int,
    val inputKeys: Set<String>,
)

/**
 * Groups are ordered by how often they are edited, so the common face buttons sit at the top.
 * The final "other" group is a catch-all for inputs added to [PhysicalInputs] later.
 */
private val PHYSICAL_MAPPING_GROUPS = listOf(
    PhysicalMappingGroup(
        "face",
        R.string.i18n_group_face_buttons,
        setOf(
            PhysicalInputs.KEY_A,
            PhysicalInputs.KEY_B,
            PhysicalInputs.KEY_X,
            PhysicalInputs.KEY_Y,
        ),
    ),
    PhysicalMappingGroup(
        "shoulder",
        R.string.i18n_group_shoulder_buttons,
        setOf(
            PhysicalInputs.KEY_LB,
            PhysicalInputs.KEY_RB,
            PhysicalInputs.KEY_LT,
            PhysicalInputs.KEY_RT,
        ),
    ),
    PhysicalMappingGroup(
        "stick_dpad",
        R.string.i18n_group_stick_dpad,
        setOf(
            PhysicalInputs.KEY_L3,
            PhysicalInputs.KEY_R3,
            PhysicalInputs.KEY_DPAD_UP,
            PhysicalInputs.KEY_DPAD_DOWN,
            PhysicalInputs.KEY_DPAD_LEFT,
            PhysicalInputs.KEY_DPAD_RIGHT,
        ),
    ),
    PhysicalMappingGroup(
        "function",
        R.string.i18n_group_function_buttons,
        setOf(
            PhysicalInputs.KEY_SELECT,
            PhysicalInputs.KEY_START,
            PhysicalInputs.KEY_HOME,
            PhysicalInputs.KEY_SHARE,
            PhysicalInputs.KEY_MIC_MUTE,
        ),
    ),
    PhysicalMappingGroup(
        "paddles",
        R.string.i18n_group_paddles,
        setOf(
            PhysicalInputs.KEY_PADDLE_R1,
            PhysicalInputs.KEY_PADDLE_R2,
            PhysicalInputs.KEY_PADDLE_L1,
            PhysicalInputs.KEY_PADDLE_L2,
        ),
    ),
    PhysicalMappingGroup(
        "analog",
        R.string.i18n_group_sticks_touchpad,
        setOf(
            PhysicalInputs.KEY_LEFT_JOYSTICK,
            PhysicalInputs.KEY_RIGHT_JOYSTICK,
            PhysicalInputs.KEY_TOUCHPAD,
        ),
    ),
    PhysicalMappingGroup("other", R.string.i18n_other, emptySet()),
)

/** The views of one group; [cards] drives search filtering without rebuilding the list. */
private class PhysicalMappingGroupBinding(
    val group: PhysicalMappingGroup,
    val body: LinearLayout,
    val chevron: ImageView,
    val count: TextView,
) {
    val cards = mutableListOf<Pair<PhysicalInput, View>>()
}

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
internal fun MainActivity.writePhysicalMapping(
    key: String,
    outputs: List<Int>,
    gyroActivate: Boolean,
    timing: ComboTiming = ComboTiming.SIMULTANEOUS,
    intervalMs: Int = ComboTiming.DEFAULT_INTERVAL_MS,
) {
    val updated = gamepadLayout.currentPhysicalInputMappings.toMutableMap()
    val isDefault = outputs == PhysicalInputs.defaultOutputsFor(key) && !gyroActivate &&
        timing == ComboTiming.SIMULTANEOUS && intervalMs == ComboTiming.DEFAULT_INTERVAL_MS
    if (isDefault) updated.remove(key)
    else updated[key] = PhysicalInputMapping(
        outputs = outputs,
        gyroActivate = gyroActivate,
        comboTiming = timing,
        comboIntervalMs = intervalMs,
    )
    gamepadLayout.setPhysicalInputMappings(updated)
    viewModel.updatePresetButtons(gamepadLayout.getPreset())
}

internal fun MainActivity.updatePhysicalMappingOutputs(key: String, outputs: List<Int>) {
    val mapping = gamepadLayout.currentPhysicalInputMappings[key]
    writePhysicalMapping(
        key,
        outputs,
        mapping?.gyroActivate ?: false,
        mapping?.comboTiming ?: ComboTiming.SIMULTANEOUS,
        mapping?.comboIntervalMs ?: ComboTiming.DEFAULT_INTERVAL_MS,
    )
}

internal fun MainActivity.updatePhysicalMappingGyro(key: String, enabled: Boolean) {
    val mapping = gamepadLayout.currentPhysicalInputMappings[key]
    val outputs = mapping?.outputs ?: PhysicalInputs.defaultOutputsFor(key)
    writePhysicalMapping(
        key,
        outputs,
        enabled,
        mapping?.comboTiming ?: ComboTiming.SIMULTANEOUS,
        mapping?.comboIntervalMs ?: ComboTiming.DEFAULT_INTERVAL_MS,
    )
}

internal fun MainActivity.updatePhysicalMappingTiming(
    key: String,
    timing: ComboTiming,
    intervalMs: Int,
) {
    val mapping = gamepadLayout.currentPhysicalInputMappings[key]
    writePhysicalMapping(
        key,
        mapping?.outputs ?: PhysicalInputs.defaultOutputsFor(key),
        mapping?.gyroActivate ?: false,
        timing,
        intervalMs,
    )
}

internal fun MainActivity.resetPhysicalMapping(key: String) {
    val gyro = gamepadLayout.currentPhysicalInputMappings[key]?.gyroActivate ?: false
    writePhysicalMapping(key, PhysicalInputs.defaultOutputsFor(key), gyro)
}

/**
 * Rebuilds the physical-controller mapping page:
 *
 * - inputs are grouped into collapsible sections instead of one long list (all collapsed
 *   by default so the categories are visible at a glance);
 * - every row only shows the label and current chips, and lazily expands its advanced
 *   controls (clear/reset, combo timing, gyro activation, stick/touchpad tuning).
 */
internal fun MainActivity.populatePhysicalMapping(container: LinearLayout) {
    val a = this
    val density = a.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()
    val panel = a.layoutGlobalSettingsPanel
    val scrollView = container.parent as? ScrollView
    val previousScroll = scrollView?.scrollY ?: 0
    container.removeAllViews()

    val supported = a.currentSupportedPhysicalButtons()
    val mappings = a.gamepadLayout.currentPhysicalInputMappings
    fun refresh() = a.populatePhysicalMapping(container)

    val available = PhysicalInputs.ALL.filter { input ->
        // 背键（paddle）不一定出现在手柄上报的能力位里，但用户始终应能配置它们。
        input.kind != PhysicalInputKind.BUTTON ||
            PhysicalInputs.isPaddle(input.key) ||
            (input.bitMask and supported) != 0
    }
    val otherGroup = PHYSICAL_MAPPING_GROUPS.last()
    val groupedInputs = LinkedHashMap<PhysicalMappingGroup, MutableList<PhysicalInput>>()
    PHYSICAL_MAPPING_GROUPS.forEach { groupedInputs[it] = mutableListOf() }
    for (input in available) {
        val group = PHYSICAL_MAPPING_GROUPS.firstOrNull { input.key in it.inputKeys } ?: otherGroup
        groupedInputs.getValue(group).add(input)
    }

    var applyGroupState: () -> Unit = {}
    val bindings = mutableListOf<PhysicalMappingGroupBinding>()
    for ((group, inputs) in groupedInputs) {
        if (inputs.isEmpty()) continue

        val header = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(2))
            isClickable = true
            isFocusable = true
        }
        val chevron = ImageView(a).apply {
            setImageResource(R.drawable.ic_arrow_right)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = a.getString(R.string.i18n_expand)
        }
        header.addView(
            chevron,
            LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(2) },
        )
        header.addView(
            TextView(a).apply {
                text = a.getString(group.titleRes)
                setTextColor(COLOR_TEXT_PRIMARY)
                textSize = 13f
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val count = TextView(a).apply {
            setTextColor(COLOR_TEXT_SECONDARY)
            textSize = 12f
        }
        header.addView(
            count,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(6) },
        )
        container.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            // 分组默认折叠，只显示分类概览；展开状态记录在面板中，重建列表时保留。
            visibility = View.GONE
        }
        container.addView(
            body,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val binding = PhysicalMappingGroupBinding(group, body, chevron, count)
        for (input in inputs) {
            val card = a.buildPhysicalMappingCard(input, mappings[input.key], density, ::refresh)
            body.addView(
                card,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(4) },
            )
            binding.cards.add(input to card)
        }
        header.setOnClickListener {
            val expandedGroups = panel?.physicalMappingExpandedGroups
            if (expandedGroups != null) {
                if (!expandedGroups.remove(group.key)) expandedGroups.add(group.key)
            }
            applyGroupState()
        }
        bindings.add(binding)
    }

    applyGroupState = {
        for (binding in bindings) {
            val expanded = panel?.physicalMappingExpandedGroups?.contains(binding.group.key) == true
            binding.body.visibility = if (expanded) View.VISIBLE else View.GONE
            binding.chevron.setImageResource(
                if (expanded) R.drawable.ic_arrow_down else R.drawable.ic_arrow_right,
            )
            binding.chevron.contentDescription = a.getString(
                if (expanded) R.string.i18n_collapse else R.string.i18n_expand,
            )
            binding.count.text = binding.cards.size.toString()
        }
    }
    applyGroupState()

    if (previousScroll > 0) {
        scrollView?.post { scrollView.scrollTo(0, previousScroll) }
    }
}

/**
 * One compact row for [input]. The header always shows the label and current mapping; the
 * advanced controls live in [details] and are only created when the row is expanded.
 */
private fun MainActivity.buildPhysicalMappingCard(
    input: PhysicalInput,
    mapping: PhysicalInputMapping?,
    density: Float,
    onChanged: () -> Unit,
): View {
    val a = this
    fun dp(v: Int) = (v * density).toInt()
    val panel = a.layoutGlobalSettingsPanel
    val isButton = input.kind == PhysicalInputKind.BUTTON
    val outputs = mapping?.outputs ?: input.defaultOutputs
    val expanded = panel?.physicalMappingExpandedInputs?.contains(input.key) == true

    val card = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(COLOR_BG_SURFACE)
        setPadding(dp(8), dp(1), dp(8), dp(1))
    }

    val header = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(44)
        setPadding(0, dp(2), 0, dp(2))
        isClickable = true
        isFocusable = true
    }
    card.addView(
        header,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ),
    )

    header.addView(
        TextView(a).apply {
            text = a.physicalInputLabel(input)
            setTextColor(COLOR_TEXT_PRIMARY)
            textSize = 13f
            maxLines = 2
        },
        LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT),
    )

    val details = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(0, 0, 0, dp(4))
    }
    card.addView(
        details,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ),
    )

    val chevron = ImageView(a).apply {
        setImageResource(R.drawable.ic_arrow_right)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = a.getString(R.string.i18n_expand)
    }
    var summaryView: TextView? = null

    if (isButton) {
        val chips = FlowLayout(a)
        header.addView(
            chips,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = dp(4) },
        )
        a.fillPhysicalMappingChips(chips, outputs, density) { removed ->
            a.updatePhysicalMappingOutputs(input.key, outputs - removed)
            onChanged()
        }
        header.addView(
            Button(a, null, 0, R.style.GamepadChip).apply {
                text = "+"
                textSize = 16f
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
                contentDescription = a.getString(R.string.i18n_add_b58c75)
                setOnClickListener {
                    a.showOutputValuePicker(outputs) { newBits ->
                        a.updatePhysicalMappingOutputs(input.key, newBits)
                        onChanged()
                    }
                }
            },
            LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(2) },
        )
    } else {
        val summary = TextView(a).apply {
            text = a.physicalInputSummary(input)
            setTextColor(COLOR_TEXT_SECONDARY)
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        summaryView = summary
        header.addView(
            summary,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = dp(4) },
        )
    }
    header.addView(
        chevron,
        LinearLayout.LayoutParams(dp(20), dp(20)).apply { leftMargin = dp(2) },
    )

    fun buildDetails() {
        if (details.childCount > 0) return
        if (isButton) {
            val current = a.gamepadLayout.currentPhysicalInputMappings[input.key]
            val currentOutputs = current?.outputs ?: input.defaultOutputs
            val actions = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            actions.addView(
                a.buildMappingActionChip(R.string.i18n_clear) {
                    a.updatePhysicalMappingOutputs(input.key, emptyList())
                    onChanged()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)),
            )
            actions.addView(
                a.buildMappingActionChip(R.string.i18n_reset) {
                    a.resetPhysicalMapping(input.key)
                    onChanged()
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(36),
                ).apply { leftMargin = dp(4) },
            )
            details.addView(
                actions,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) },
            )

            details.addView(
                a.buildPhysicalGyroCheckbox(input, density),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            if (currentOutputs.isNotEmpty()) {
                details.addComboTimingControls(
                    current?.comboTiming ?: ComboTiming.SIMULTANEOUS,
                    current?.comboIntervalMs ?: ComboTiming.DEFAULT_INTERVAL_MS,
                ) { timing, intervalMs ->
                    a.updatePhysicalMappingTiming(input.key, timing, intervalMs)
                }
            }
        } else {
            details.addView(
                a.buildPhysicalGyroCheckbox(input, density),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            // The analog shaping (dead zone / anti dead zone / curve) and the stick/touchpad
            // mapping mode live in the same expandable panel.
            if (input.kind == PhysicalInputKind.JOYSTICK) {
                panel?.appendStickTuning(details, input.key) {
                    summaryView?.text = a.physicalInputSummary(input)
                }
            } else if (input.kind == PhysicalInputKind.TOUCHPAD) {
                panel?.appendTouchpadTuning(details, input.key) {
                    summaryView?.text = a.physicalInputSummary(input)
                }
            }
        }
    }

    fun setExpanded(value: Boolean) {
        if (value) {
            buildDetails()
            panel?.physicalMappingExpandedInputs?.add(input.key)
        } else {
            panel?.physicalMappingExpandedInputs?.remove(input.key)
        }
        details.visibility = if (value) View.VISIBLE else View.GONE
        chevron.setImageResource(
            if (value) R.drawable.ic_arrow_down else R.drawable.ic_arrow_right,
        )
        chevron.contentDescription = a.getString(
            if (value) R.string.i18n_collapse else R.string.i18n_expand,
        )
    }

    setExpanded(expanded)
    header.setOnClickListener { setExpanded(details.visibility != View.VISIBLE) }
    return card
}

private fun MainActivity.physicalInputLabel(input: PhysicalInput): String =
    if (input.labelRes != 0) getString(input.labelRes) else input.label

/** Short right-hand summary for stick / touchpad rows, whose main content is a mode selector. */
private fun MainActivity.physicalInputSummary(input: PhysicalInput): String = when (input.key) {
    PhysicalInputs.KEY_LEFT_JOYSTICK -> gamepadLayout.currentLeftStickSettings?.mode
        ?.let { getString(it.labelRes) } ?: getString(R.string.i18n_native_mapping)

    PhysicalInputs.KEY_RIGHT_JOYSTICK -> gamepadLayout.currentRightStickSettings?.mode
        ?.let { getString(it.labelRes) } ?: getString(R.string.i18n_native_mapping)

    PhysicalInputs.KEY_TOUCHPAD -> gamepadLayout.currentTouchpadSettings?.mode
        ?.let { getString(it.labelRes) } ?: getString(R.string.i18n_native_mapping)

    else -> ""
}

private fun MainActivity.buildMappingActionChip(
    @StringRes textRes: Int,
    onClick: () -> Unit,
): Button = Button(this, null, 0, R.style.GamepadChip).apply {
    text = getString(textRes)
    setOnClickListener { onClick() }
}

private fun MainActivity.buildPhysicalGyroCheckbox(
    input: PhysicalInput,
    density: Float,
): CheckBox = CheckBox(this).apply {
    text = getString(R.string.i18n_used_to_activate_the_gyro)
    setTextColor(COLOR_TEXT_SECONDARY)
    textSize = 13f
    isChecked = gamepadLayout.currentPhysicalInputMappings[input.key]?.gyroActivate == true
    setPadding(0, (2f * density).toInt(), 0, 0)
    setOnCheckedChangeListener { _, value ->
        updatePhysicalMappingGyro(input.key, value)
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
                text = getString(R.string.i18n_unmapped)
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
