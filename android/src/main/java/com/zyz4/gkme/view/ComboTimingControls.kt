package com.zyz4.gkme.view

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import com.zyz4.gkme.R
import com.zyz4.gkme.model.ComboTiming

private const val COLOR_TEXT_PRIMARY = 0xFFFFFFFF.toInt()
private const val COLOR_TEXT_SECONDARY = 0xFF888888.toInt()

/**
 * Adds the common "组合键时序" selector to [this] vertical container.
 *
 * The seekbar is only visible for [ComboTiming.CUSTOM_INTERVAL]; [onChange] is called whenever the
 * mode or the 1-100 ms interval changes.
 */
internal fun LinearLayout.addComboTimingControls(
    timing: ComboTiming,
    intervalMs: Int,
    onChange: (ComboTiming, Int) -> Unit,
) {
    val density = resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()

    var currentTiming = timing
    var lastReportedTiming = timing
    var currentInterval = intervalMs.coerceIn(
        ComboTiming.MIN_INTERVAL_MS, ComboTiming.MAX_INTERVAL_MS
    )

    addView(
        TextView(context).apply {
            text = context.getString(R.string.i18n_combo_timing)
            setTextColor(COLOR_TEXT_PRIMARY)
            textSize = 14f
        },
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) },
    )

    val intervalRow = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = if (currentTiming == ComboTiming.CUSTOM_INTERVAL) View.VISIBLE else View.GONE
    }

    val spinner = Spinner(context).apply {
        adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            ComboTiming.entries.map { context.getString(it.labelRes) },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        setSelection(ComboTiming.entries.indexOf(currentTiming).coerceAtLeast(0))
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val selected = ComboTiming.entries.getOrNull(pos) ?: return
                currentTiming = selected
                intervalRow.visibility =
                    if (selected == ComboTiming.CUSTOM_INTERVAL) View.VISIBLE else View.GONE
                // Spinner fires once with its initial value when the listener is attached;
                // don't mark the layout dirty in that case.
                if (selected == lastReportedTiming) return
                lastReportedTiming = selected
                onChange(selected, currentInterval)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    addView(
        spinner,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) },
    )

    val intervalHeader = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    intervalHeader.addView(
        TextView(context).apply {
            text = context.getString(R.string.i18n_combo_interval)
            setTextColor(COLOR_TEXT_SECONDARY)
            textSize = 13f
        },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
    val intervalValue = TextView(context).apply {
        text = "$currentInterval ms"
        setTextColor(COLOR_TEXT_SECONDARY)
        textSize = 13f
    }
    intervalHeader.addView(
        intervalValue,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    intervalRow.addView(
        intervalHeader,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) },
    )

    val seekBar = SeekBar(context).apply {
        max = ComboTiming.MAX_INTERVAL_MS - ComboTiming.MIN_INTERVAL_MS
        progress = currentInterval - ComboTiming.MIN_INTERVAL_MS
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                currentInterval = (progress + ComboTiming.MIN_INTERVAL_MS)
                    .coerceIn(ComboTiming.MIN_INTERVAL_MS, ComboTiming.MAX_INTERVAL_MS)
                intervalValue.text = "$currentInterval ms"
                onChange(currentTiming, currentInterval)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}

            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
    }
    intervalRow.addView(
        seekBar,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ),
    )

    addView(
        intervalRow,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) },
    )
}
