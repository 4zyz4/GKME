package com.zyz4.gkme.controlled

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
    private lateinit var tvShizukuStatus: TextView
    private lateinit var etManualIp: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_controlled)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        deviceList = findViewById(R.id.deviceListContainer)
        tvStatus = findViewById(R.id.tvControlledStatus)
        tvShizukuStatus = findViewById(R.id.tvShizukuStatus)
        etManualIp = findViewById(R.id.etManualIp)

        GamepadInjector.init(this)
        GamepadInjector.ensureBound()

        findViewById<Button>(R.id.btnControlledBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnRefreshDevices).setOnClickListener {
            ControlledHostManager.refresh()
            showToast("正在扫描…")
        }
        findViewById<Button>(R.id.btnManualConnect).setOnClickListener {
            ControlledHostManager.connectManual(etManualIp.text.toString())
        }
        findViewById<Button>(R.id.btnShizukuDownload).setOnClickListener {
            if (GamepadInjector.isShizukuInstalled(this)) {
                GamepadInjector.openShizuku(this)
            } else {
                GamepadInjector.openDownloadPage(this)
            }
        }
        findViewById<Button>(R.id.btnShizukuStart).setOnClickListener {
            if (GamepadInjector.isShizukuInstalled(this)) {
                GamepadInjector.openShizuku(this)
            } else {
                showToast("未安装 Shizuku，请先下载")
                GamepadInjector.openDownloadPage(this)
            }
        }
        findViewById<Button>(R.id.btnShizukuPermission).setOnClickListener {
            GamepadInjector.requestPermission()
            showToast("正在申请 Shizuku 权限…")
        }

        ControlledHostManager.start()
        tvShizukuStatus.text = if (GamepadInjector.isShizukuInstalled(this)) {
            GamepadInjector.statusText()
        } else {
            "未安装 Shizuku，请点击下方“下载 Shizuku”"
        }
        observe()
    }

    override fun onDestroy() {
        ControlledHostManager.stop()
        super.onDestroy()
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
                    }
                }
                launch {
                    ControlledHostManager.shizukuStatus.collect { status ->
                        tvShizukuStatus.text = if (GamepadInjector.isShizukuInstalled(this@ControlledActivity)) {
                            status
                        } else {
                            "未安装 Shizuku，请点击下方“下载 Shizuku”"
                        }
                    }
                }
                launch {
                    ControlledHostManager.session.collect { renderDevices(ControlledHostManager.devices.value) }
                }
            }
        }
    }

    private fun renderDevices(devices: List<ControlledDevice>) {
        val active = ControlledHostManager.session.value
        deviceList.removeAllViews()
        if (devices.isEmpty()) return
        val inflater = LayoutInflater.from(this)
        for (device in devices) {
            val item = inflater.inflate(R.layout.item_controlled_device, deviceList, false)
            item.findViewById<TextView>(R.id.tvDeviceName).text = device.displayName
            item.findViewById<TextView>(R.id.tvDeviceIp).text = device.subtitle
            val isActive = active?.ip == device.ip
            item.findViewById<TextView>(R.id.tvDeviceStatus).text =
                if (isActive) "已连接" else "已发现"
            val btnConnect = item.findViewById<Button>(R.id.btnDeviceConnect)
            val btnDisconnect = item.findViewById<Button>(R.id.btnDeviceDisconnect)
            btnConnect.visibility = if (isActive) View.GONE else View.VISIBLE
            btnDisconnect.visibility = if (isActive) View.VISIBLE else View.GONE
            btnConnect.setOnClickListener { ControlledHostManager.connect(device) }
            btnDisconnect.setOnClickListener { ControlledHostManager.disconnect() }
            deviceList.addView(item)
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
