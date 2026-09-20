package com.zyz4.gkme

import android.content.Intent
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** True when the system reports USB debugging (adb) as enabled. The app cannot
 *  turn it on itself — it can only guide the user to the developer options. */
internal fun MainActivity.isUsbDebuggingEnabled(): Boolean {
    return try {
        Settings.Global.getInt(
            contentResolver, Settings.Global.ADB_ENABLED, 0
        ) == 1
    } catch (_: Exception) {
        false
    }
}

/** Entry point for USB mode: if adb is enabled start the TCP server, otherwise
 *  show the setup guide. */
internal fun MainActivity.checkUsbAdbAndStart() {
    if (isUsbDebuggingEnabled()) {
        viewModel.startServer()
    } else {
        showUsbAdbGuideDialog()
    }
}

internal fun MainActivity.showUsbAdbGuideDialog() {
    val a = this
    val text = TextView(a).apply {
        text = "1. 打开「设置 → 关于手机」，连续点击「版本号」7 次，开启开发者选项。\n\n" +
            "2. 进入「开发者选项」，打开「USB 调试」。\n\n" +
            "若已开启，请直接点击「启动服务」。"
        textSize = 13f
        setTextColor(0xFFCCCCCC.toInt())
        setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
    val content = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        addView(text)
    }
    CustomDialog.showCustomView(
        a, "开启开发者选项与 USB 调试", content,
        positiveText = "去开启", negativeText = "取消",
        onPositive = { a.openDeveloperOptions() },
    )
}

/** Opens the Developer Options screen (where the USB debugging switch lives).
 *  Android has no public API to deep-link to the switch itself. */
internal fun MainActivity.openDeveloperOptions() {
    val targets = listOf(
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
    )
    for (intent in targets) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return
        } catch (_: Exception) {
        }
    }
    showToast("无法打开设置，请手动进入「开发者选项」")
}
