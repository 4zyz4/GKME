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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
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
import com.zyz4.gkme.data.ControlledDeviceModeRepository
import com.zyz4.gkme.model.VirtualGamepadType
import com.zyz4.gkme.service.ConnectionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * “作为被控端”的连接页面：布局与操作逻辑参考 GKME-Windows 主机端
 * （手动输入 IP、刷新、关于、设备列表、连接/断开），并在本地通过 Shizuku
 * 创建 uinput 虚拟手柄。
 */
@AndroidEntryPoint
class ControlledActivity : ComponentActivity() {

    @Inject
    lateinit var connectionManager: ConnectionManager

    @Inject
    lateinit var deviceModeRepository: ControlledDeviceModeRepository

    private lateinit var deviceList: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvKeepAliveStatus: TextView
    private lateinit var btnShizukuAction: Button
    private lateinit var etManualIp: EditText
    private var exitDialogShowing = false

    /** 每台已发现控制端（按 MAC/IP）各自记忆的模拟手柄类型。 */
    private var deviceModes: Map<String, VirtualGamepadType> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_controlled)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        deviceList = findViewById(R.id.deviceListContainer)
        tvStatus = findViewById(R.id.tvControlledStatus)
        tvKeepAliveStatus = findViewById(R.id.tvKeepAliveStatus)
        btnShizukuAction = findViewById(R.id.btnShizukuAction)
        etManualIp = findViewById(R.id.etManualIp)

        GamepadInjector.init(this)
        GamepadInjector.ensureBound()
        // 保活由前台服务负责启用；这里初始化以便实时展示状态。
        KeepAliveInjector.init(this)
        tvKeepAliveStatus.text = KeepAliveInjector.statusText()

        findViewById<Button>(R.id.btnControlledBack).setOnClickListener { exitControlled() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = exitControlled()
        })
        findViewById<Button>(R.id.btnRefreshDevices).setOnClickListener {
            ControlledHostManager.refresh()
            showToast("正在扫描…")
        }
        findViewById<Button>(R.id.btnManualConnect).setOnClickListener {
            val ip = etManualIp.text.toString().trim()
            val type = deviceModes[ip]
            if (type != null && connectionManager.settings.value.virtualGamepadType != type) {
                connectionManager.updateSettings(
                    connectionManager.settings.value.copy(virtualGamepadType = type)
                )
            }
            ControlledHostManager.connectManual(ip)
        }
        btnShizukuAction.setOnClickListener { onShizukuAction() }

        startHostWithNotificationPermission()
        updateShizukuAction()
        observe()
        loadDeviceModes()
    }

    /** 读取每台控制端各自记忆的手柄类型，读完刷新列表以恢复已连接卡片的下拉框。 */
    private fun loadDeviceModes() {
        lifecycleScope.launch {
            deviceModes = deviceModeRepository.getModes()
            renderDevices(ControlledHostManager.devices.value)
        }
    }

    /** 返回某个物理设备（MAC/IP）当前应使用的手柄类型，未记录时沿用全局设置作为默认。 */
    private fun modeFor(groupKey: String): VirtualGamepadType =
        deviceModes[groupKey] ?: connectionManager.settings.value.virtualGamepadType

    /** 连接前先把该设备记忆的手柄类型同步给虚拟手柄后端，确保按设备生效。 */
    private fun connectDevice(device: ControlledDevice, groupKey: String) {
        val type = modeFor(groupKey)
        if (connectionManager.settings.value.virtualGamepadType != type) {
            connectionManager.updateSettings(
                connectionManager.settings.value.copy(virtualGamepadType = type)
            )
        }
        ControlledHostManager.connect(device)
    }

    /** 绑定已连接设备卡片上的类型下拉框：切换后按设备持久化并立即重建虚拟手柄。 */
    private fun bindDeviceModeSpinner(spinner: Spinner, groupKey: String) {
        val names = VirtualGamepadType.entries.map { it.displayName }.toTypedArray()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(
            VirtualGamepadType.entries.indexOf(modeFor(groupKey)).coerceAtLeast(0),
            false,
        )
        var userSelecting = false
        spinner.setOnTouchListener { _, _ ->
            userSelecting = true
            false
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (!userSelecting) return
                userSelecting = false
                val type = VirtualGamepadType.entries.getOrNull(pos) ?: return
                onDeviceModeSelected(groupKey, type)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                userSelecting = false
            }
        }
    }

    private fun onDeviceModeSelected(groupKey: String, type: VirtualGamepadType) {
        deviceModes = deviceModes + (groupKey to type)
        lifecycleScope.launch { deviceModeRepository.setMode(groupKey, type) }
        if (ControlledHostManager.session.value?.groupKey == groupKey) {
            connectionManager.updateSettings(
                connectionManager.settings.value.copy(virtualGamepadType = type)
            )
        }
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

    /** 返回：先弹确认框（返回后将停止服务），确认后再结束被控端并关闭页面。 */
    private fun exitControlled() {
        if (exitDialogShowing) return
        exitDialogShowing = true
        CustomDialog.showConfirm(
            this,
            "退出被控端",
            "返回后将停止服务，确认返回吗？",
            positiveText = "确认返回",
            negativeText = "取消",
            onPositive = {
                ControlledHostService.stop(this)
                finish()
            },
        ).setOnDismissListener { exitDialogShowing = false }
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
                    ControlledHostManager.keepAliveStatus.collect {
                        tvKeepAliveStatus.text = it.ifBlank { KeepAliveInjector.statusText() }
                    }
                }
                launch {
                    ControlledHostManager.session.collect { renderDevices(ControlledHostManager.devices.value) }
                }
                launch {
                    ControlledHostManager.connectingIp.collect { renderDevices(ControlledHostManager.devices.value) }
                }
            }
        }
    }

    private fun renderDevices(cards: List<ControlledDeviceCard>) {
        val active = ControlledHostManager.session.value
        val connectingIp = ControlledHostManager.connectingIp.value
        val reconnecting =
            ControlledHostManager.state.value.phase == ControlledHostManager.Phase.RECONNECTING
        deviceList.removeAllViews()
        if (cards.isEmpty()) return
        val inflater = LayoutInflater.from(this)
        for (card in cards) {
            val activeEndpoint = card.endpoints.firstOrNull { it.ip == active?.ip }
            // 同一 MAC 有多个可用 IP 且均未连接时展开，逐条列出每个 IP。
            val expanded = card.hasMultipleEndpoints && activeEndpoint == null
            if (expanded) {
                renderExpandedCard(inflater, card, connectingIp)
            } else {
                renderCollapsedCard(inflater, card, activeEndpoint ?: card.endpoints.first(), active, connectingIp, reconnecting)
            }
        }
    }

    private fun renderExpandedCard(
        inflater: LayoutInflater,
        card: ControlledDeviceCard,
        connectingIp: String?,
    ) {
        val item = inflater.inflate(R.layout.item_controlled_device_group, deviceList, false)
        item.findViewById<TextView>(R.id.tvDeviceName).text = card.title
        item.findViewById<TextView>(R.id.tvGroupSubtitle).text = "${card.endpoints.size} 个可用连接"
        val endpointList = item.findViewById<LinearLayout>(R.id.endpointList)
        for (endpoint in card.endpoints) {
            val row = inflater.inflate(R.layout.item_controlled_endpoint, endpointList, false)
            row.findViewById<TextView>(R.id.tvEndpointIp).text = endpoint.ip
            val btnConnect = row.findViewById<Button>(R.id.btnEndpointConnect)
            if (endpoint.ip == connectingIp) {
                btnConnect.text = "连接中…"
                btnConnect.isEnabled = false
            }
            btnConnect.setOnClickListener { connectDevice(endpoint, card.groupKey) }
            endpointList.addView(row)
        }
        deviceList.addView(item)
    }

    private fun renderCollapsedCard(
        inflater: LayoutInflater,
        card: ControlledDeviceCard,
        device: ControlledDevice,
        active: ControlledDevice?,
        connectingIp: String?,
        reconnecting: Boolean,
    ) {
        val item = inflater.inflate(R.layout.item_controlled_device, deviceList, false)
        item.findViewById<TextView>(R.id.tvDeviceName).text = card.name.ifBlank { device.ip }
        item.findViewById<TextView>(R.id.tvDeviceIp).text = device.subtitle
        val isActive = active?.ip == device.ip
        item.findViewById<TextView>(R.id.tvDeviceStatus).text = when {
            isActive && reconnecting -> "尝试连接"
            isActive -> "已连接"
            device.ip == connectingIp -> "尝试连接"
            else -> "可连接"
        }
        val btnConnect = item.findViewById<Button>(R.id.btnDeviceConnect)
        val btnDisconnect = item.findViewById<Button>(R.id.btnDeviceDisconnect)
        btnConnect.visibility = if (isActive) View.GONE else View.VISIBLE
        btnDisconnect.visibility = if (isActive) View.VISIBLE else View.GONE
        btnConnect.setOnClickListener { connectDevice(device, card.groupKey) }
        btnDisconnect.setOnClickListener { ControlledHostManager.disconnect() }
        val spinner = item.findViewById<Spinner>(R.id.spinnerDeviceMode)
        if (isActive) {
            spinner.visibility = View.VISIBLE
            bindDeviceModeSpinner(spinner, card.groupKey)
        } else {
            spinner.visibility = View.GONE
        }
        deviceList.addView(item)
    }

    private fun onShizukuAction() {
        when (GamepadInjector.requiredAction(this)) {
            GamepadInjector.Action.DOWNLOAD -> GamepadInjector.openDownloadPage(this)
            GamepadInjector.Action.OPEN -> GamepadInjector.openShizuku(this)
            GamepadInjector.Action.REQUEST_PERMISSION -> {
                GamepadInjector.requestPermission(force = true)
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
