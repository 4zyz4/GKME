package com.zyz4.gkme

import androidx.lifecycle.lifecycleScope
import com.zyz4.gkme.updater.UpdateCheckResult
import com.zyz4.gkme.updater.UpdateChecker
import kotlinx.coroutines.launch

/** 启动后延迟多久发起自动检查；真正的 3 天节流由 [UpdateChecker.isCheckDue] 负责。 */
internal const val AUTO_UPDATE_CHECK_START_DELAY_MS = 3_000L

/**
 * 启动后延迟几秒静默检查一次更新，仅在距离上次检查满 3 天时真正发起请求。
 *
 * 延迟是为了让连接、手柄初始化等首屏工作先完成；用户已经进入悬浮模式或熄屏时不再打扰。
 */
internal fun MainActivity.maybeCheckForUpdates() {
    if (UpdateChecker.isCheckDue(this)) checkForUpdates(manual = false)
}

/**
 * 检查更新并给出反馈。
 *
 * @param manual 手动检查（关于页按钮）：忽略 3 天节流、忽略用户忽略过的版本，并且无论结果
 * 如何都提示一次；自动检查则只在发现新版本时弹窗。
 */
internal fun MainActivity.checkForUpdates(manual: Boolean) {
    val a = this
    if (!manual && !UpdateChecker.isCheckDue(a)) return
    if (a.updateCheckRunning) {
        if (manual) CustomDialog.showToast(a, a.getString(R.string.i18n_update_checking))
        return
    }
    a.updateCheckRunning = true
    if (manual) CustomDialog.showToast(a, a.getString(R.string.i18n_update_checking))
    a.lifecycleScope.launch {
        val result = UpdateChecker.check(a)
        a.updateCheckRunning = false
        a.handleUpdateCheckResult(result, manual)
    }
}

/** 把检查结果转成弹窗/提示；自动检查时静默处理「无更新」与「失败」。 */
private fun MainActivity.handleUpdateCheckResult(result: UpdateCheckResult, manual: Boolean) {
    if (isFinishing || isDestroyed) return
    when (result) {
        is UpdateCheckResult.Available -> {
            // 用户关闭过的版本不再自动弹出，但手动检查时仍然提示。
            if (!manual && UpdateChecker.ignoredVersion(this) == result.version) return
            showUpdateAvailableDialog(result)
        }

        UpdateCheckResult.UpToDate -> if (manual) {
            CustomDialog.showToast(this, getString(R.string.i18n_update_up_to_date))
        }

        is UpdateCheckResult.Failed -> if (manual) {
            CustomDialog.showToast(this, getString(R.string.i18n_update_check_failed))
        }
    }
}

/** 新版本提示：去官网下载，或关闭该版本的提示。 */
private fun MainActivity.showUpdateAvailableDialog(update: UpdateCheckResult.Available) {
    val a = this
    CustomDialog.showConfirm(
        context = a,
        title = a.getString(R.string.i18n_update_available_title),
        message = a.getString(
            R.string.i18n_update_available_message,
            update.version,
            BuildConfig.VERSION_NAME,
        ),
        positiveText = a.getString(R.string.i18n_update_go_download),
        negativeText = a.getString(R.string.i18n_close),
        onPositive = { a.openExternalUrl(UpdateChecker.OFFICIAL_WEBSITE_URL) },
        onNegative = { UpdateChecker.ignoreVersion(a, update.version) },
    )
}
