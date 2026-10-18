package com.zyz4.gkme.service

import com.zyz4.gkme.i18n.AppLocale
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
import com.zyz4.gkme.MainActivity
import com.zyz4.gkme.R

/**
 * Foreground service that keeps the process alive while the app is minimized in
 * floating mode. The actual overlay window is owned by [MainActivity] /
 * com.zyz4.gkme.FloatingModeController; this service only provides the persistent
 * notification and stops when the user leaves floating mode.
 */
class FloatingOverlayService : Service() {

    companion object {
        const val EXTRA_EXIT_FLOATING = "exit_floating"
        private const val CHANNEL_ID = "floating_mode"
        private const val NOTIFICATION_ID = 4101
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.i18n_floating_mode),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.i18n_persistent_notification_while_floating_m)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val exitIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_EXIT_FLOATING, true)
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val contentIntent = PendingIntent.getActivity(this, 0, exitIntent, piFlags)
        val exitPendingIntent = PendingIntent.getActivity(this, 1, exitIntent, piFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.i18n_floating_mode_is_running))
            .setContentText(getString(R.string.i18n_open_the_app_to_exit_floating_mode))
            .setSmallIcon(R.drawable.ic_eye)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.i18n_exit_floating_mode), exitPendingIntent)
            .build()
    }
}

private const val ACTION_STOP = "com.zyz4.gkme.action.STOP_FLOATING"
