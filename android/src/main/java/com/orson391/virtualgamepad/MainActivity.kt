package com.orson391.virtualgamepad

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private companion object { const val NOTIFICATION_REQUEST_CODE = 0x4E01 }
    private lateinit var statusView: TextView
    private lateinit var errorView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private var notificationWarning: String? = null
    private var managerError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        VirtualGamepadManager.init(this)
        statusView = findViewById(R.id.tvStatus)
        errorView = findViewById(R.id.tvError)
        startButton = findViewById(R.id.btnStart)
        stopButton = findViewById(R.id.btnStop)
        startButton.setOnClickListener { requestNotificationsThenStart() }
        stopButton.setOnClickListener { VirtualGamepadManager.stop(this) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { VirtualGamepadManager.status.collect(::updateUi) }
                launch { VirtualGamepadManager.errorMessage.collect { managerError = it; renderMessage() } }
            }
        }
    }

    private fun requestNotificationsThenStart() {
        VirtualGamepadManager.clearError()
        notificationWarning = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST_CODE)
        } else VirtualGamepadManager.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_REQUEST_CODE) {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                notificationWarning = "Notifications are disabled; the gamepad still runs"
                renderMessage()
            }
            VirtualGamepadManager.start(this)
        }
    }

    private fun updateUi(status: DeviceStatus) {
        statusView.text = if (status == DeviceStatus.Running) {
            "${status.name} (${VirtualGamepadManager.selectedProfile})"
        } else {
            status.name
        }
        startButton.isEnabled = status == DeviceStatus.Stopped || status == DeviceStatus.Error
        stopButton.isEnabled = status == DeviceStatus.Running
    }

    private fun renderMessage() { errorView.text = managerError ?: notificationWarning.orEmpty() }
}
