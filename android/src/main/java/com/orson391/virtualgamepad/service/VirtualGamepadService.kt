package com.orson391.virtualgamepad.service

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
import com.orson391.virtualgamepad.DeviceStatus
import com.orson391.virtualgamepad.MainActivity
import com.orson391.virtualgamepad.VirtualGamepadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class VirtualGamepadService : Service() {
    companion object {
        const val ACTION_START = "com.orson391.virtualgamepad.action.START_GAMEPAD"
        const val ACTION_STOP = "com.orson391.virtualgamepad.action.STOP_GAMEPAD"
        private const val STATUS_CHANNEL_ID = "virtual_gamepad_status"
        private const val EVENTS_CHANNEL_ID = "virtual_gamepad_events"
        private const val NOTIFICATION_ID = 2026
        private const val EVENT_NOTIFICATION_ID = 2027

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, VirtualGamepadService::class.java).setAction(ACTION_START))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wasRunning = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIFICATION_ID, statusNotification("Virtual Gamepad - Starting..."))
        serviceScope.launch { VirtualGamepadManager.status.collect(::handleStatus) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) VirtualGamepadManager.stop(this)
        return START_NOT_STICKY
    }

    private fun handleStatus(status: DeviceStatus) {
        when (status) {
            DeviceStatus.Starting -> updateForeground("Virtual Gamepad - Starting...")
            DeviceStatus.Running -> {
                updateForeground("Virtual Gamepad is running")
                if (!wasRunning) { wasRunning = true; postEvent("Virtual gamepad started") }
            }
            DeviceStatus.Error -> {
                val message = VirtualGamepadManager.errorMessage.value ?: "Virtual gamepad failed to start"
                notificationManager().notify(NOTIFICATION_ID, statusNotification(message, false))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            DeviceStatus.Stopped -> {
                if (wasRunning) postEvent("Virtual gamepad stopped")
                wasRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            DeviceStatus.Stopping -> updateForeground("Virtual Gamepad - Stopping...")
        }
    }

    private fun updateForeground(text: String) { notificationManager().notify(NOTIFICATION_ID, statusNotification(text)) }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager().apply {
            createNotificationChannel(NotificationChannel(STATUS_CHANNEL_ID, "Virtual Gamepad", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows whether the virtual gamepad is active"
                setShowBadge(false)
            })
            createNotificationChannel(NotificationChannel(EVENTS_CHANNEL_ID, "Virtual Gamepad events", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Reports virtual gamepad start and stop events"
            })
        }
    }

    private fun statusNotification(text: String, ongoing: Boolean = true): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }, flags)
        val stopIntent = PendingIntent.getService(this, 1, Intent(this, VirtualGamepadService::class.java).setAction(ACTION_STOP), flags)
        return NotificationCompat.Builder(this, STATUS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Virtual Gamepad")
            .setContentText(text)
            .setOngoing(ongoing)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply { if (ongoing) addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent) }
            .build()
    }

    private fun postEvent(text: String) {
        notificationManager().notify(EVENT_NOTIFICATION_ID, NotificationCompat.Builder(this, EVENTS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Virtual Gamepad")
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build())
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)
    override fun onDestroy() { serviceScope.cancel(); super.onDestroy() }
}
