package com.zyz4.gkme

import android.app.Dialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.zyz4.gkme.view.PresetPreviewView

/** 每行显示的布局数量。 */
private const val LAYOUT_PICKER_COLUMNS = 3

/** 超过这个数量的布局才让对话框内部滚动，少量布局时保持紧凑高度。 */
private const val LAYOUT_PICKER_SCROLL_THRESHOLD = LAYOUT_PICKER_COLUMNS * 2

/**
 * 显示布局选择器对话框：每行 3 个布局卡片，卡片复用设置页的布局预设外观
 * （[R.layout.item_preset_card] + [PresetPreviewView]，显示名称与预览）。
 * 点击卡片关闭对话框并加载对应布局；点击取消或对话框外部区域则取消操作。
 */
internal fun MainActivity.showLayoutPickerDialog() {
    val a = this
    val infos = a.viewModel.presetInfos.value
    if (infos.isEmpty()) return
    val current = a.viewModel.settings.value.currentPresetName
    val density = a.resources.displayMetrics.density
    fun dp(value: Int): Int = (value * density).toInt()

    var dialog: Dialog? = null

    val grid = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
    }
    infos.chunked(LAYOUT_PICKER_COLUMNS).forEach { rowInfos ->
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        rowInfos.forEach { info ->
            val card = LayoutInflater.from(a).inflate(R.layout.item_preset_card, row, false)
            card.findViewById<PresetPreviewView>(R.id.presetPreview).setButtons(info.buttons)
            card.findViewById<TextView>(R.id.presetName).text = a.localizedPresetName(info.name)
            card.findViewById<View>(R.id.cardBackground).setBackgroundResource(
                if (info.name == current) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )
            card.setOnClickListener {
                dialog?.dismiss()
                a.loadPresetByName(info.name)
            }
            row.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(6)
            })
        }
        // 补占位视图，让最后一行不足 3 个时卡片仍保持 1/3 宽度。
        repeat(LAYOUT_PICKER_COLUMNS - rowInfos.size) {
            row.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f).apply {
                rightMargin = dp(6)
            })
        }
        grid.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dp(6)
        })
    }

    val content = ScrollView(a).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(grid, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
    }

    dialog = CustomDialog.showCustomView(
        a,
        getString(R.string.i18n_select_layout),
        content,
        negativeText = getString(R.string.i18n_cancel),
        scrollable = infos.size > LAYOUT_PICKER_SCROLL_THRESHOLD,
    )
}
