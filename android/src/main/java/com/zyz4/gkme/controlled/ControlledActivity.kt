package com.zyz4.gkme.controlled

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zyz4.gkme.CustomDialog
import com.zyz4.gkme.R
import kotlinx.coroutines.launch

/**
 * “作为被控端”的连接页面：布局与操作逻辑参考 GKME-Windows 主机端
 * （手动输入 IP、刷新、关于、设备列表、连接/断开），并在本地通过 Shizuku
 * 创建 uinput 虚拟手柄。
 */
class ControlledActivity : ComponentActivity() {

    private lateinit var deviceList: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var btnShizukuAction: Button
    private lateinit var etManualIp: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_controlled)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        deviceList = findViewById(R.id.deviceListContainer)
        tvStatus = findViewById(R.id.tvControlledStatus)
        btnShizukuAction = findViewById(R.id.btnShizukuAction)
        etManualIp = findViewById(R.id.etManualIp)

        GamepadInjector.init(this)
        GamepadInjector.ensureBound()

        findViewById<Button>(R.id.btnControlledBack).setOnClickListener { exitControlled() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = exitControlled()
        })
        findViewById<Button>(R.id.btnRefreshDevices).setOnClickListener {
            ControlledHostManager.refresh()
            showToast("正在扫描…")
        }
        findViewById<Button>(R.id.btnManualConnect).setOnClickListener {
            ControlledHostManager.connectManual(etManualIp.text.toString())
        }
        btnShizukuAction.setOnClickListener { onShizukuAction() }

        startHostWithNotificationPermission()
        updateShizukuAction()
        observe()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // 无论是否授权都启动服务：前台服务本身仍可运行，通知在授权后可见。
        ControlledHostService.start(this)
    }

    /** 被控端以前台服务 + 常驻通知的方式常驻，避免熄屏或切到后台后被系统杀进程。 */
    private fun startHostWithNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ControlledHostService.start(this)
        }
    }

    /** 返回：结束被控端（移除常驻通知并停止前台服务）后关闭页面。 */
    private fun exitControlled() {
        ControlledHostService.stop(this)
        finish()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ControlledHostManager.devices.collect { renderDevices(it) }
                }
                launch {
                    ControlledHostManager.state.collect { st ->
                        tvStatus.text = st.statusText
                        if (st.phase == ControlledHostManager.Phase.ERROR) {
                            showToast(st.statusText)
                        }
                        renderDevices(ControlledHostManager.devices.value)
                    }
                }
                launch {
                    ControlledHostManager.shizukuStatus.collect { updateShizukuAction() }
                }
                launch {
                    ControlledHostManager.session.collect { renderDevices(ControlledHostManager.devices.value) }
                }
            }
        }
    }

    private fun renderDevices(devices: List<ControlledDevice>) {
        val active = ControlledHostManager.session.value
        val reconnecting =
            ControlledHostManager.state.value.phase == ControlledHostManager.Phase.RECONNECTING
        deviceList.removeAllViews()
        if (devices.isEmpty()) return
        val inflater = LayoutInflater.from(this)
        for (device in devices) {
            val item = inflater.inflate(R.layout.item_controlled_device, deviceList, false)
            item.findViewById<TextView>(R.id.tvDeviceName).text = device.displayName
            item.findViewById<TextView>(R.id.tvDeviceIp).text = device.subtitle
            val isActive = active?.ip == device.ip
            item.findViewById<TextView>(R.id.tvDeviceStatus).text = when {
                isActive && reconnecting -> "尝试连接"
                isActive -> "已连接"
                else -> "可连接"
            }
            val btnConnect = item.findViewById<Button>(R.id.btnDeviceConnect)
            val btnDisconnect = item.findViewById<Button>(R.id.btnDeviceDisconnect)
            btnConnect.visibility = if (isActive) View.GONE else View.VISIBLE
            btnDisconnect.visibility = if (isActive) View.VISIBLE else View.GONE
            btnConnect.setOnClickListener { ControlledHostManager.connect(device) }
            btnDisconnect.setOnClickListener { ControlledHostManager.disconnect() }
            deviceList.addView(item)
        }
    }

    private fun onShizukuAction() {
        when (GamepadInjector.requiredAction(this)) {
            GamepadInjector.Action.DOWNLOAD -> GamepadInjector.openDownloadPage(this)
            GamepadInjector.Action.OPEN -> GamepadInjector.openShizuku(this)
            GamepadInjector.Action.REQUEST_PERMISSION -> {
                GamepadInjector.requestPermission()
                showToast("正在申请 Shizuku 权限…")
            }
            GamepadInjector.Action.NONE -> Unit
        }
    }

    private fun updateShizukuAction() {
        btnShizukuAction.isEnabled = true
        when (GamepadInjector.requiredAction(this)) {
            GamepadInjector.Action.DOWNLOAD -> btnShizukuAction.text = "下载 Shizuku"
            GamepadInjector.Action.OPEN -> btnShizukuAction.text = "打开 Shizuku"
            GamepadInjector.Action.REQUEST_PERMISSION -> btnShizukuAction.text = "申请授权"
            GamepadInjector.Action.NONE -> {
                btnShizukuAction.text = "已授权"
                btnShizukuAction.isEnabled = false
            }
        }
    }

    private fun showToast(message: String) {
        CustomDialog.showToast(this, message)
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.navigationBars())
            window.insetsController?.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }
}
