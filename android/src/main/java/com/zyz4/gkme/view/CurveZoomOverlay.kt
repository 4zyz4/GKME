package com.zyz4.gkme.view

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.zyz4.gkme.R

/**
 * Full-screen enlarged curve editor with its control buttons.
 *
 * The curve is drawn as a square sized to the short screen edge and shares the vertical space
 * with the buttons, so the whole column fills the screen height. Add this as the last child of a
 * full-screen container (the layout global settings page or the activity content view); the
 * top-left 返回 button mirrors the sidebar 返回 button.
 */
class CurveZoomOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val curveEditor: CurveEditorView
    private var onCurveChanged: ((List<Float>?) -> Unit)? = null

    init {
        visibility = View.GONE
        background = ColorDrawable(ColorUtils.setAlphaComponent(Color.BLACK, 235))
        isClickable = true
        isFocusable = true

        val density = resources.displayMetrics.density
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // Leave room at the top for the top-left 返回 button (same slot as the sidebar one).
            setPadding((16f * density).toInt(), (68f * density).toInt(), (16f * density).toInt(), (12f * density).toInt())
        }

        column.addView(TextView(context).apply {
            text = "灵敏度曲线"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = (8f * density).toInt()
        })

        curveEditor = CurveEditorView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        curveEditor.onPointsChanged = { list -> onCurveChanged?.invoke(list) }
        column.addView(curveEditor)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8f * density).toInt()
            }
        }
        val btnHeight = (48f * density).toInt()
        btnRow.addView(Button(context).apply {
            text = "删除选中点"
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundResource(R.drawable.button_flat)
            setOnClickListener { curveEditor.deleteSelected() }
        }, LinearLayout.LayoutParams(0, btnHeight, 1f).apply { rightMargin = (6f * density).toInt() })
        btnRow.addView(Button(context).apply {
            text = "重置为直线"
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundResource(R.drawable.button_flat)
            setOnClickListener {
                curveEditor.setFromFlatList(null)
                onCurveChanged?.invoke(null)
            }
        }, LinearLayout.LayoutParams(0, btnHeight, 1f))
        column.addView(btnRow)

        addView(column)

        // Added last so it sits on top and reliably receives the tap in its slot.
        addView(Button(context).apply {
            text = "返回"
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 13f
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_back, 0, 0, 0)
            compoundDrawablePadding = (4f * density).toInt()
            background = ColorDrawable(ContextCompat.getColor(context, R.color.bg_surface))
            stateListAnimator = null
            setOnClickListener { hide() }
        }, LayoutParams(
            (120f * density).toInt(),
            (44f * density).toInt(),
            Gravity.TOP or Gravity.START,
        ).apply { topMargin = (16f * density).toInt() })
    }

    /** Shows the overlay seeded with [curve]; [onChanged] receives every edit including reset. */
    fun show(curve: List<Float>?, onChanged: (List<Float>?) -> Unit) {
        this.onCurveChanged = onChanged
        curveEditor.setFromFlatList(curve)
        visibility = View.VISIBLE
    }

    /** Updates the displayed curve without changing visibility (mirrors the inline editor). */
    fun setCurve(curve: List<Float>?) {
        curveEditor.setFromFlatList(curve)
    }

    fun hide() {
        visibility = View.GONE
    }

    val isOverlayVisible: Boolean get() = visibility == View.VISIBLE
}
