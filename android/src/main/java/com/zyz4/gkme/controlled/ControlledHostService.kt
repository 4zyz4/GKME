package com.zyz4.gkme.controlled

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zyz4.gkme.R
import com.zyz4.gkme.service.ConnectionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 被控端前台服务：以常驻通知持有 [ControlledHostManager] 的生命周期，避免熄屏或
 * 切到后台后被系统杀进程。[ControlledActivity] 只负责界面展示，离开页面后服务
 * 继续监听控制端；用户可通过通知的“停止”操作结束。
 *
 * 当开关开启「悬浮窗保活」时，服务会持有一个 1×1 透明悬浮窗（见 [FloatingKeepAlive]），
 * 让系统把本进程视为可见窗口，降低后台/熄屏被回收的概率。
 */
@AndroidEntryPoint
class ControlledHostService : Service() {

    @Inject
    lateinit var connectionManager: ConnectionManager

    companion object {
        const val ACTION_STOP = "com.zyz4.gkme.action.STOP_CONTROLLED"
        private const val CHANNEL_ID = "controlled_mode"
        private const val NOTIFICATION_ID = 4102

        /** 启动（或确认）被控端前台服务。 */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ControlledHostService::class.java),
            )
        }

        /** 停止前台服务并结束被控端主机。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, ControlledHostService::class.java))
        }
    }

    private var scope: CoroutineScope? = null
    private var notifyJob: Job? = null
    private var keepAliveJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(ControlledHostManager.state.value.statusText))
        // 进程被系统重建时 Activity 可能尚未创建，这里兜底初始化 Shizuku 环境。
        GamepadInjector.init(this)
        GamepadInjector.ensureBound()
        ControlledHostManager.start()
        startNotificationUpdates()
        startFloatingKeepAlive()
        return START_STICKY
    }

    override fun onDestroy() {
        notifyJob?.cancel()
        notifyJob = null
        keepAliveJob?.cancel()
        keepAliveJob = null
        scope?.cancel()
        scope = null
        FloatingKeepAlive.stop()
        ControlledHostManager.stop()
        super.onDestroy()
    }

    /** 跟随「悬浮窗保活」开关启停：设置变化时立即生效，进程重建后也能恢复。 */
    private fun startFloatingKeepAlive() {
        if (keepAliveJob != null) return
        val s = scope ?: return
        keepAliveJob = s.launch {
            connectionManager.settings.collect { settings ->
                if (settings.floatingKeepAlive) {
                    FloatingKeepAlive.start(this@ControlledHostService)
                } else {
                    FloatingKeepAlive.stop()
                }
            }
        }
    }

    private fun startNotificationUpdates() {
        if (notifyJob != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        notifyJob = s.launch {
            ControlledHostManager.state.collect { st ->
                val manager = getSystemService(NotificationManager::class.java) ?: return@collect
                manager.notify(NOTIFICATION_ID, buildNotification(st.statusText))
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "被控端",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "被控端运行期间的常驻通知，用于防止后台被系统清理"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ControlledActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            piFlags,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ControlledHostService::class.java).setAction(ACTION_STOP),
            piFlags,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GKME 被控端运行中")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_home)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "停止", stopIntent)
            .build()
    }
}
