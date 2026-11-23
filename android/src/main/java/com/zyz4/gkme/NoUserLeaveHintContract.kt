package com.zyz4.gkme

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

/**
 * 标记由本应用主动发起的页面跳转，避免系统回调 [android.app.Activity.onUserLeaveHint]。
 *
 * Android 在本 Activity 启动另一个 Activity（图片选择、文件导入导出、系统设置等）时，
 * 只要目标 Intent 没带 [Intent.FLAG_ACTIVITY_NO_USER_ACTION]，也会调用
 * `onUserLeaveHint()`。本机模式据此判断用户是否按了 Home/最近任务并自动进入悬浮模式，
 * 于是编辑外观选择图片等操作会被误判为最小化应用。
 */
internal fun Intent.noUserLeaveHint(): Intent =
    addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)

/** 为系统 ActivityResult 契约补上 [Intent.FLAG_ACTIVITY_NO_USER_ACTION]。 */
internal class NoUserLeaveHintContract<I, O>(
    private val delegate: ActivityResultContract<I, O>,
) : ActivityResultContract<I, O>() {
    override fun createIntent(context: Context, input: I): Intent =
        delegate.createIntent(context, input).noUserLeaveHint()

    override fun parseResult(resultCode: Int, intent: Intent?): O =
        delegate.parseResult(resultCode, intent)
}
