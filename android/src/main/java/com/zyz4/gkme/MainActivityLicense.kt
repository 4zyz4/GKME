package com.zyz4.gkme

import android.graphics.Typeface
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import com.zyz4.gkme.data.LicenseAgreementStore

/**
 * 首次启动的 GPLv3 同意门槛。
 *
 * 在构建主界面之前检查同意状态；未同意时只显示全屏协议页，不初始化手柄布局、
 * ViewModel 以及任何连接服务。这样用户拒绝后不会留下任何后台服务。
 *
 * @return 已同意时返回 true（调用方继续启动主界面）；首次展示协议页时返回 false。
 */
internal fun MainActivity.ensureLicenseAgreement(onAccepted: () -> Unit): Boolean {
    val a = this
    if (LicenseAgreementStore.isAccepted(a)) return true

    val root = FrameLayout(a).apply {
        setBackgroundColor(ContextCompat.getColor(a, R.color.bg_dark))
    }
    a.setContentView(root)

    var textOverlay: View? = null
    val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val overlay = textOverlay
            if (overlay != null) {
                root.removeView(overlay)
                textOverlay = null
            } else {
                a.exitApplication()
            }
        }
    }
    a.onBackPressedDispatcher.addCallback(a, backCallback)

    var accepting = false
    root.addView(
        a.buildLicenseGate(
            onViewFull = {
                if (textOverlay == null) {
                    val overlay = a.buildGplTextOverlay { view ->
                        root.removeView(view)
                        textOverlay = null
                    }
                    textOverlay = overlay
                    root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                }
            },
            onAccepted = {
                if (!accepting) {
                    accepting = true
                    backCallback.remove()
                    LicenseAgreementStore.accept(a)
                    onAccepted()
                }
            },
            onRejected = { a.exitApplication() },
        ),
        FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
    )
    return false
}

/** 全屏协议页：标题 + 可滚动正文 + “查看全文” + 同意/退出。 */
private fun MainActivity.buildLicenseGate(
    onViewFull: () -> Unit,
    onAccepted: () -> Unit,
    onRejected: () -> Unit,
): View {
    val a = this
    val title = TextView(a).apply {
        text = a.getString(R.string.i18n_gpl3_agreement_title)
        textSize = 22f
        setTextColor(ContextCompat.getColor(a, R.color.text_primary))
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    val message = TextView(a).apply {
        text = a.getString(R.string.i18n_gpl3_agreement_message)
        textSize = 14f
        setTextColor(ContextCompat.getColor(a, R.color.text_secondary))
        gravity = Gravity.CENTER
        setLineSpacing(a.licenseDp(3).toFloat(), 1.15f)
        // 摘要较长时允许在正文区域内滚动（ScrollView 会把子 TextView 钳制到视口高度）。
        movementMethod = ScrollingMovementMethod()
        isVerticalScrollBarEnabled = false
    }
    val viewFull = a.licenseButton(
        text = a.getString(R.string.i18n_gpl3_view_full),
        primary = false,
        onClick = onViewFull,
    )
    val buttons = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        addView(
            a.licenseButton(
                text = a.getString(R.string.i18n_exit_app),
                primary = false,
                onClick = onRejected,
            )
        )
        addView(
            a.licenseButton(
                text = a.getString(R.string.i18n_agree_and_continue),
                primary = true,
                onClick = onAccepted,
            ),
            LinearLayout.LayoutParams(WRAP_CONTENT, a.licenseDp(44)).apply {
                leftMargin = a.licenseDp(12)
            },
        )
    }
    val column = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(a.licenseDp(40), a.licenseDp(20), a.licenseDp(40), a.licenseDp(20))
        addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(message, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply {
            topMargin = a.licenseDp(12)
        })
        addView(viewFull, LinearLayout.LayoutParams(WRAP_CONTENT, a.licenseDp(40)).apply {
            topMargin = a.licenseDp(12)
        })
        addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = a.licenseDp(12)
        })
    }
    return FrameLayout(a).apply {
        addView(column, FrameLayout.LayoutParams(
            minOf(a.resources.displayMetrics.widthPixels - a.licenseDp(32), a.licenseDp(640)),
            MATCH_PARENT,
            Gravity.CENTER,
        ))
    }
}

/** 全屏 GPLv3 全文页；关闭后回到协议页。 */
private fun MainActivity.buildGplTextOverlay(onClose: (View) -> Unit): View {
    val a = this
    val overlay = FrameLayout(a).apply {
        setBackgroundColor(ContextCompat.getColor(a, R.color.bg_dark))
    }
    val title = TextView(a).apply {
        text = a.getString(R.string.i18n_gpl3_full_title)
        textSize = 20f
        setTextColor(ContextCompat.getColor(a, R.color.text_primary))
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    // TextView 自己负责滚动：ScrollView 会把子 TextView 的高度钳制到视口，导致后半部分
    // 许可证文本无法滚动查看。
    val text = TextView(a).apply {
        text = a.readGplLicenseText()
        textSize = 13f
        setTextColor(ContextCompat.getColor(a, R.color.text_secondary))
        typeface = Typeface.MONOSPACE
        movementMethod = ScrollingMovementMethod()
        isVerticalScrollBarEnabled = true
    }
    val closeRow = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        addView(
            a.licenseButton(
                text = a.getString(R.string.i18n_close),
                primary = true,
                onClick = { onClose(overlay) },
            )
        )
    }
    val column = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(a.licenseDp(40), a.licenseDp(24), a.licenseDp(40), a.licenseDp(24))
        addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(text, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply {
            topMargin = a.licenseDp(16)
        })
        addView(closeRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = a.licenseDp(16)
        })
    }
    overlay.addView(column, FrameLayout.LayoutParams(
        minOf(a.resources.displayMetrics.widthPixels - a.licenseDp(32), a.licenseDp(760)),
        MATCH_PARENT,
        Gravity.CENTER,
    ))
    return overlay
}

private fun MainActivity.licenseButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
): Button {
    val a = this
    val color = if (primary) {
        ContextCompat.getColor(a, R.color.text_primary)
    } else {
        ContextCompat.getColor(a, R.color.text_secondary)
    }
    return Button(a).apply {
        this.text = text
        textSize = 14f
        setTextColor(color)
        isAllCaps = false
        stateListAnimator = null
        minimumHeight = 0
        minimumWidth = 0
        setPadding(a.licenseDp(20), 0, a.licenseDp(20), 0)
        background = ContextCompat.getDrawable(
            a,
            if (primary) R.drawable.bg_chip_selected else R.drawable.bg_chip,
        )
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, a.licenseDp(44))
    }
}

internal fun MainActivity.readGplLicenseText(): String = runCatching {
    resources.openRawResource(R.raw.gpl_3_0).bufferedReader().use { it.readText() }
}.getOrElse {
    getString(R.string.i18n_gpl3_text_unavailable)
}

private fun MainActivity.licenseDp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

/** 拒绝协议时退出整个任务，不留下空 Activity。 */
internal fun MainActivity.exitApplication() {
    finishAndRemoveTask()
}
