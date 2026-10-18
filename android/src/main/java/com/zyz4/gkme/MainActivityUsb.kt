package com.zyz4.gkme

import com.zyz4.gkme.R
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
        text = getString(R.string.i18n_1_open_settings_about_phone_and_tap_buil) +
            getString(R.string.i18n_2_open_developer_options_and_enable_usb) +
            getString(R.string.i18n_if_enabled_simply_tap_start_service)
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
        a, getString(R.string.i18n_enable_developer_options_and_usb_debuggi), content,
        positiveText = getString(R.string.i18n_open_settings), negativeText = getString(R.string.i18n_cancel),
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
    showToast(getString(R.string.i18n_failed_to_open_settings_open_developer_o))
}
