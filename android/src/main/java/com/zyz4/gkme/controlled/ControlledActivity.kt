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

    private lateinit var deviceList: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var btnShizukuAction: Button
    private lateinit var etManualIp: EditText
    private var exitDialogShowing = false

    private val vgTypeChipIds = listOf(
        R.id.btnVgXbox, R.id.btnVgDs4, R.id.btnVgDualsense, R.id.btnVgSwitch,
    )

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

        setupVirtualGamepadTypeSelector()
        startHostWithNotificationPermission()
        updateShizukuAction()
        observe()
    }

    /**
     * 本地虚拟手柄类型选择器。与设置页「模拟手柄类型」保持同一组选项和样式；
     * 切换后立即持久化并重建已创建的虚拟手柄，无需重新连接。
     */
    private fun setupVirtualGamepadTypeSelector() {
        vgTypeChipIds.forEachIndexed { idx, id ->
            findViewById<Button>(id).setOnClickListener {
                selectVgTypeChip(idx)
                connectionManager.updateSettings(
                    connectionManager.settings.value.copy(
                        virtualGamepadType = VirtualGamepadType.entries[idx],
                    )
                )
            }
        }
        selectVgTypeChip(currentVgTypeIndex())
    }

    private fun selectVgTypeChip(index: Int) {
        vgTypeChipIds.forEachIndexed { i, id ->
            findViewById<Button>(id).setBackgroundResource(
                if (i == index) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )
        }
    }

    private fun currentVgTypeIndex(): Int =
        VirtualGamepadType.entries
            .indexOf(connectionManager.settings.value.virtualGamepadType)
            .coerceAtLeast(0)

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
                    ControlledHostManager.session.collect { renderDevices(ControlledHostManager.devices.value) }
                }
                launch {
                    ControlledHostManager.connectingIp.collect { renderDevices(ControlledHostManager.devices.value) }
                }
                launch {
                    connectionManager.settings.collect { s ->
                        selectVgTypeChip(
                            VirtualGamepadType.entries.indexOf(s.virtualGamepadType).coerceAtLeast(0)
                        )
                    }
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
            btnConnect.setOnClickListener { ControlledHostManager.connect(endpoint) }
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
        btnConnect.setOnClickListener { ControlledHostManager.connect(device) }
        btnDisconnect.setOnClickListener { ControlledHostManager.disconnect() }
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
