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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

        private const val STATE_IDLE = 0
        private const val STATE_STARTING = 1
        private const val STATE_RUNNING = 2

        /**
         * RUNNING -> stop 的去抖时间。Activity 生命周期抖动（onDestroy/onResume、
         * 进出悬浮模式）会在几毫秒内 stop 后又 start；如果立刻调用 stopService，
         * 它可能在新的 startForegroundService 之后才生效，把新记录一起带下去，
         * 5 秒后触发 ForegroundServiceDidNotStartInTimeException。
         */
        private const val STOP_DELAY_MS = 500L

        /** 保护 [state] 与 [stopAfterStart] 的锁。 */
        private val stateLock = Any()

        private var state = STATE_IDLE

        private val mainHandler = Handler(Looper.getMainLooper())

        /** 去抖期间保存 application context，避免持有 Activity。 */
        private var pendingStopContext: Context? = null

        private val stopRunnable = Runnable {
            val context = pendingStopContext
            pendingStopContext = null
            val shouldStop = synchronized(stateLock) {
                if (state != STATE_RUNNING) {
                    false
                } else {
                    state = STATE_IDLE
                    true
                }
            }
            if (shouldStop && context != null) {
                try {
                    context.stopService(Intent(context, SessionKeepAliveService::class.java))
                    Log.i(TAG, "请求停止会话保活服务")
                } catch (t: Throwable) {
                    Log.w(TAG, "停止保活服务失败: ${t.message}")
                }
            }
        }

        /**
         * 在 [STATE_STARTING] 期间收到 stop 时置位：等服务进入前台后再自行停止。
         *
         * 不能在 `startForegroundService()` 之后、`startForeground()` 之前调用
         * `stopService()`：系统会销毁 ServiceRecord，但 5 秒后仍抛
         * `ForegroundServiceDidNotStartInTimeException` 让应用崩溃。
         */
        private var stopAfterStart = false

        /** 启动（或确认）会话保活前台服务；重复调用无副作用。 */
        fun start(context: Context) {
            // 取消尚未执行的延迟停止：服务还在，不需要重新 start。
            mainHandler.removeCallbacks(stopRunnable)
            pendingStopContext = null
            synchronized(stateLock) {
                if (state != STATE_IDLE) {
                    // 已经在运行/启动中：取消可能存在的延迟停止请求。
                    stopAfterStart = false
                    return
                }
                state = STATE_STARTING
                stopAfterStart = false
            }
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, SessionKeepAliveService::class.java),
                )
                Log.i(TAG, "请求启动会话保活服务")
            } catch (t: Throwable) {
                // Android 12+ 在后台启动前台服务可能被拒绝；此时保持原状，
                // 等下一次回到前台或 onPause 时再尝试。
                synchronized(stateLock) {
                    state = STATE_IDLE
                    stopAfterStart = false
                }
                Log.w(TAG, "启动保活服务失败: ${t.message}")
            }
        }

        fun stop(context: Context) {
            var delayed = false
            synchronized(stateLock) {
                when (state) {
                    STATE_RUNNING -> {
                        // 去抖：给紧随其后的 start 一个取消机会，避免 stopService
                        // 落在新的 startForegroundService 之后。
                        pendingStopContext = context.applicationContext
                        mainHandler.removeCallbacks(stopRunnable)
                        mainHandler.postDelayed(stopRunnable, STOP_DELAY_MS)
                        Log.i(TAG, "会话保活服务将在 ${STOP_DELAY_MS}ms 后停止")
                    }
                    STATE_STARTING -> {
                        // 关键：启动请求已发出，不能立刻 stopService。
                        stopAfterStart = true
                        delayed = true
                    }
                    else -> Unit
                }
            }
            if (delayed) {
                Log.i(TAG, "会话保活服务启动中，延后停止")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        synchronized(stateLock) {
            // 正常路径下 state 已是 STARTING；系统重建时兜底。
            if (state == STATE_IDLE) state = STATE_STARTING
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (t: Throwable) {
            // 少数 ROM 会在后台拒绝 startForeground；不要留下一个没有通知的服务。
            synchronized(stateLock) {
                state = STATE_IDLE
                stopAfterStart = false
            }
            Log.w(TAG, "进入前台失败: ${t.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        val stopNow = synchronized(stateLock) {
            if (stopAfterStart) {
                stopAfterStart = false
                true
            } else {
                state = STATE_RUNNING
                false
            }
        }
        if (stopNow) {
            Log.i(TAG, "启动后收到停止请求，立即停止")
            stopSelf()
        } else {
            Log.i(TAG, "会话保活服务已进入前台")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(stopRunnable)
        pendingStopContext = null
        synchronized(stateLock) {
            state = STATE_IDLE
            stopAfterStart = false
        }
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
