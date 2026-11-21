package com.zyz4.gkme.view

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.zyz4.gkme.R

/**
 * 编辑模式下重叠控件的澄清选择器。
 *
 * 在触点附近弹出一列候选控件（自顶向下），点选后回调对应 id。
 * 点击外部或返回键关闭，且不改变当前选择——与建模软件里
 * “点击重叠对象后从列表里澄清选择”的交互一致。
 */
class OverlapSelectionPopup(private val anchor: View) {

    data class Item(val id: String, val label: String)

    private var popup: PopupWindow? = null

    val isShowing: Boolean
        get() = popup?.isShowing == true

    fun show(items: List<Item>, x: Float, y: Float, onPick: (String) -> Unit) {
        if (items.isEmpty()) return
        dismiss()
        val context = anchor.context
        val density = context.resources.displayMetrics.density
        fun dp(value: Float) = (value * density).toInt()

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            // 与浮动编辑面板一致：直角、半透明深色底 + 1px 描边，不使用圆角。
            background = GradientDrawable().apply {
                setColor(-0x33E5E5E6)
                setStroke(dp(1f).coerceAtLeast(1), -0x666667)
            }
        }

        val title = TextView(context).apply {
            text = context.getString(R.string.i18n_overlap_selection_title)
            setTextColor(0xFFE5E5E6.toInt())
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        }
        content.addView(title)

        val hint = TextView(context).apply {
            text = context.getString(R.string.i18n_overlap_selection_hint)
            setTextColor(0xFF9A9A9B.toInt())
            textSize = 10f
        }
        content.addView(hint, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8f) })

        for (item in items) {
            val row = TextView(context).apply {
                text = item.label
                setTextColor(0xFFE5E5E6.toInt())
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
                background = GradientDrawable().apply {
                    setColor(0xFF2A2A2A.toInt())
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dismiss()
                    onPick(item.id)
                }
            }
            content.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(4f) })
        }

        val window = PopupWindow(
            content,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 8f * density
        }
        popup = window
        window.setOnDismissListener { popup = null }

        // 触点定位到弹窗左上角；下方空间不足时翻到触点上方，并夹紧在屏幕内。
        val metrics = context.resources.displayMetrics
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val anchorX = location[0] + x.toInt()
        val anchorY = location[1] + y.toInt()
        val estimatedW = dp(200f)
        val estimatedH = dp(40f) * (items.size + 2)
        val popupX = anchorX.coerceIn(dp(8f), (metrics.widthPixels - estimatedW).coerceAtLeast(dp(8f)))
        val popupY = if (anchorY + estimatedH > metrics.heightPixels - dp(8f)) {
            (anchorY - estimatedH).coerceAtLeast(dp(8f))
        } else {
            anchorY
        }
        window.showAtLocation(anchor, Gravity.TOP or Gravity.START, popupX, popupY)
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }
}
