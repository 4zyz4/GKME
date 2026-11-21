package com.zyz4.gkme.service

import com.zyz4.gkme.i18n.AppLocale
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zyz4.gkme.MainActivity
import com.zyz4.gkme.R

/**
 * 会话保活前台服务。
 *
 * 只要本机模式 / 控制端会话仍在运行，就持有一个 `specialUse` 前台服务：
 *
 * - 后台（切到游戏、按 Home）时进程保持 `PROCESS_STATE_FOREGROUND_SERVICE` 优先级，
 *   不会因为没有任何窗口而被当成 cached 进程回收；
 * - 不依赖悬浮窗，因此关闭「自动进入悬浮模式」后实体手柄输入仍能持续转发；
 * - 悬浮模式与被控端各有自己的前台服务，此时由 [MainActivity] 做互斥，避免同时
 *   出现两条常驻通知。
 *
 * 服务本身不持有输入逻辑，输入仍由 `PhysicalControllerHandler` 与 Shizuku 用户服务
 * 负责；进程被杀后由 Android 重新拉起 Activity 才能恢复，这里使用
 * `START_NOT_STICKY`，避免只恢复一条没有实际功能的通知。
 */
class SessionKeepAliveService : Service() {

    companion object {
        private const val TAG = "GKME_KeepAlive"
        private const val CHANNEL_ID = "session_keepalive"
        private const val NOTIFICATION_ID = 4103

        @Volatile
        var isRunning: Boolean = false
            private set

        /** 启动（或确认）会话保活前台服务；重复调用无副作用。 */
        fun start(context: Context) {
            if (isRunning) return
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, SessionKeepAliveService::class.java),
                )
                Log.i(TAG, "请求启动会话保活服务")
            } catch (t: Throwable) {
                // Android 12+ 在后台启动前台服务可能被拒绝；此时保持原状，
                // 等下一次回到前台或 onPause 时再尝试。
                Log.w(TAG, "启动保活服务失败: ${t.message}")
            }
        }

        fun stop(context: Context) {
            // 不能因为 isRunning=false 就提前返回：onPause 启动后如果立刻 onResume，
            // stopService 可能早于 Service.onCreate 执行，跳过会留下一个前台服务。
            try {
                context.stopService(Intent(context, SessionKeepAliveService::class.java))
                Log.i(TAG, "请求停止会话保活服务")
            } catch (t: Throwable) {
                Log.w(TAG, "停止保活服务失败: ${t.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            Log.i(TAG, "会话保活服务已进入前台")
            START_NOT_STICKY
        } catch (t: Throwable) {
            // 少数 ROM 会在后台拒绝 startForeground；不要留下一个没有通知的服务。
            Log.w(TAG, "进入前台失败: ${t.message}")
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.i18n_background_mode),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.i18n_persistent_notification_while_running_in_t)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.i18n_gkme_is_running_in_the_background))
        .setContentText(getString(R.string.i18n_input_forwarding_continues_tap_to_retur))
        .setSmallIcon(R.drawable.ic_home)
        .setOngoing(true)
        .setShowWhen(false)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()
}
