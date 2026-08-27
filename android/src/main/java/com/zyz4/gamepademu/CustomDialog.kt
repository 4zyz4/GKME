package com.zyz4.gamepademu

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

object CustomDialog {

    private fun dp(c: Context, v: Float) = (v * c.resources.displayMetrics.density).toInt()

    private fun bg(color: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(color); cornerRadius = radius
    }

    fun showConfirm(
        context: Context, title: String, message: String,
        positiveText: String = "确定", negativeText: String? = "取消",
        onPositive: () -> Unit = {}, onNegative: (() -> Unit)? = null,
    ): Dialog {
        val dialog = baseDialog(context)
        val root = rootLayout(context)
        root.addView(tv(context, title, 17f, -0x1, true, Gravity.CENTER))
        root.addView(tv(context, message, 14f, -0x777778, false, Gravity.CENTER).apply {
            setPadding(0, dp(context, 16f), 0, dp(context, 20f))
        })
        root.addView(btnRow(context, dialog, positiveText, negativeText, onPositive, onNegative))
        dialog.setContentView(wrapScroll(context, root))
        dialog.show()
        return dialog
    }

    fun showInput(
        context: Context, title: String, hint: String = "", prefill: String = "",
        positiveText: String = "确定", negativeText: String? = "取消",
        onPositive: (String) -> Unit = {}, onNegative: (() -> Unit)? = null,
    ): Dialog {
        val dialog = baseDialog(context)
        val root = rootLayout(context)
        root.addView(tv(context, title, 17f, -0x1, true, Gravity.CENTER))
        val input = EditText(context).apply {
            setHint(hint)
            setText(prefill)
            setTextColor(-0x1)
            setHintTextColor(-0x777778)
            textSize = 14f
            setPadding(dp(context, 10f), dp(context, 8f), dp(context, 10f), dp(context, 8f))
            background = bg(0xFF2A2A2A.toInt(), 0f)
            setSelectAllOnFocus(true)
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(context, 16f), 0, dp(context, 20f)) }
        }
        root.addView(input)
        root.addView(btnRow(context, dialog, positiveText, negativeText,
            onPositive = { onPositive(input.text.toString().trim()) }, onNegative = onNegative))
        dialog.setContentView(wrapScroll(context, root))
        dialog.show()
        return dialog
    }

    fun showCustomView(
        context: Context, title: String? = null, contentView: View,
        dialogWidth: Int = -1,
        negativeText: String? = null, positiveText: String? = null,
        onPositive: (() -> Unit)? = null, onNegative: (() -> Unit)? = null,
        cancelable: Boolean = true, scrollable: Boolean = false,
    ): Dialog {
        val dialog = baseDialog(context).apply { setCancelable(cancelable); setCanceledOnTouchOutside(cancelable) }
        if (scrollable) {
            val dm = context.resources.displayMetrics
            val w = if (dialogWidth > 0) dialogWidth else (dm.widthPixels * 0.88).toInt()
            dialog.window?.setLayout(
                w,
                (dm.heightPixels * 0.88).toInt()
            )
        }
        val root = rootLayout(context)
        if (title != null) {
            root.addView(tv(context, title, 17f, -0x1, true, Gravity.CENTER).apply {
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(context, 12f) }
            })
        }
        root.addView(contentView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (scrollable) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
            if (scrollable) 1f else 0f
        ))
        if (positiveText != null || negativeText != null) {
            val bRow = btnRow(context, dialog, positiveText, negativeText, onPositive, onNegative)
            bRow.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 12f) }
            root.addView(bRow)
        }
        dialog.setContentView(root)
        dialog.show()
        return dialog
    }

    fun showToast(context: Context, message: String) {
        try {
            val tv = TextView(context).apply {
                text = message
                setTextColor(-0x1)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(context, 16f), dp(context, 10f), dp(context, 16f), dp(context, 10f))
                background = bg(0xFF333333.toInt(), 0f)
            }
            Toast(context).apply {
                duration = Toast.LENGTH_SHORT
                view = tv
                setGravity(Gravity.BOTTOM, 0, dp(context, 64f))
                show()
            }
        } catch (_: Exception) {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun baseDialog(context: Context) = Dialog(context).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val lp = attributes
            lp.width = (context.resources.displayMetrics.widthPixels * 0.88).toInt()
            lp.gravity = Gravity.CENTER
            attributes = lp
        }
    }

    private fun rootLayout(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 24f), dp(context, 20f), dp(context, 24f), dp(context, 16f))
        background = bg(-0xe1e1e2, 0f)
    }

    private fun wrapScroll(context: Context, root: View): ScrollView = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun tv(c: Context, text: String, size: Float, color: Int, bold: Boolean, gravity: Int) =
        TextView(c).apply { this.text = text; textSize = size; setTextColor(color); this.gravity = gravity; if (bold) typeface = Typeface.DEFAULT_BOLD }

    private fun btnRow(
        context: Context, dialog: Dialog,
        positiveText: String?, negativeText: String?,
        onPositive: (() -> Unit)?, onNegative: (() -> Unit)?,
    ): LinearLayout {
        val density = context.resources.displayMetrics.density
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        if (negativeText != null) {
            row.addView(Button(context).apply {
                text = negativeText; textSize = 14f; setTextColor(-0x777778)
                setAllCaps(false); stateListAnimator = null; minimumHeight = 0; minimumWidth = 0
                setPadding(dp(context, 16f), 0, dp(context, 16f), 0)
                setOnClickListener { onNegative?.invoke(); dialog.dismiss() }
                background = bg(0xFF333333.toInt(), 0f)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 40f)).apply { rightMargin = dp(context, 8f) }
            })
        }
        if (positiveText != null) {
            row.addView(Button(context).apply {
                text = positiveText; textSize = 14f; setTextColor(-0x1)
                setAllCaps(false); stateListAnimator = null; minimumHeight = 0; minimumWidth = 0
                setPadding(dp(context, 16f), 0, dp(context, 16f), 0)
                setOnClickListener { onPositive?.invoke(); dialog.dismiss() }
                background = bg(-0x99999a, 0f)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 40f))
            })
        }
        return row
    }
}
