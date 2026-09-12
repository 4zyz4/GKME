package com.zyz4.gkme

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zyz4.gkme.model.ButtonPosition
import com.zyz4.gkme.model.ConnectionMode
import com.zyz4.gkme.model.GamepadState
import com.zyz4.gkme.model.GyroActivateMode
import com.zyz4.gkme.service.BluetoothTransportType
import com.zyz4.gkme.service.ConnectionPhase
import kotlinx.coroutines.launch

// ── State Observation ────────────────────────────────────

internal fun MainActivity.observeState() {
    val a = this
    a.lifecycleScope.launch {
        a.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                var lastRestartToken = 0
                a.viewModel.connectionState.collect { st ->
                    for (label in a.touchpadLabels) label.text = st.statusText
                    for (label in a.mousepadLabels) label.text = st.statusText
                    if (a.settingsInflated) {
                        a.findViewById<TextView>(R.id.tvConnectionStatus).text = st.statusText
                        val btn = a.findViewById<Button>(R.id.btnConnectAction)
                        btn.text = if (st.phase != ConnectionPhase.IDLE) "停止服务" else "启动服务"
                        val ip = if (a.viewModel.settings.value.connectionMode == ConnectionMode.WIFI &&
                            st.statusText != "未启动"
                        ) {
                            "本机 IP: ${a.viewModel.getServerIp()}"
                        } else ""
                        a.findViewById<TextView>(R.id.tvServerIp).text = ip
                    }
                    if (st.restartToken != lastRestartToken) {
                        lastRestartToken = st.restartToken
                        a.discoverableRequested = false
                    }

                    val transportType = st.transportType
                    val isClassicBt = transportType == BluetoothTransportType.CLASSIC

                    if (st.phase == ConnectionPhase.DISCOVERABLE
                        && !a.discoverableRequested
                        && a.viewModel.settings.value.connectionMode == ConnectionMode.BLUETOOTH
                        && isClassicBt
                    ) {
                        a.discoverableRequested = true
                        val hasSavedDevice = a.viewModel.pairedDeviceName.value != null
                        if (!hasSavedDevice) {
                            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                            }
                            a.discoverableLauncher.launch(intent)
                        }
                    }
                    if (st.phase == ConnectionPhase.IDLE) {
                        a.discoverableRequested = false
                    }
                }
            }
            launch {
                a.viewModel.displayMode.collect { mode ->
                    a.updateButtonLabels(mode)
                }
            }
            launch {
                a.viewModel.settings.collect { s ->
                    a.controlViews["touchpad"]?.visibility = View.VISIBLE
                    if (a.settingsInflated) {
                        a.updatePairedDeviceVisibility(a.viewModel.pairedDeviceName.value)
                        listOf(
                            R.id.btnGyroOriLandscape,
                            R.id.btnGyroOriPortrait,
                            R.id.btnGyroOriPortraitInverted,
                        ).forEach { id ->
                            a.findViewById<Button>(id).alpha = 1.0f
                        }
                    }
                    if (s.keepScreenOn) {
                        a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        a.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    a.physicalControllerHandler.swapPhoneMotors = s.swapPhoneMotors
                    a.physicalControllerHandler.swapControllerMotors = s.swapControllerMotors
                    a.physicalControllerHandler.inputControllerIndex = s.inputControllerIndex
                    a.physicalControllerHandler.setDriver(s.controllerDriver)
                    a.applyEffectivePhysicalControllerSettings()
                    a.applyAppearanceIfChanged(s)
                }
            }
            launch {
                a.viewModel.currentPreset.collect { preset ->
                    a.viewModel.applyLayoutGyroSettings(preset)
                    if (!a.gamepadLayout.isEditModeActive()) {
                        a.applyPreset(preset)
                    }
                    if (a.settingsInflated) a.updateGyroChipsLockState(preset.gyroOrientation)
                }
            }
            launch {
                a.viewModel._gamepadState.collect { state ->
                    a.gamepadLayout._gamepadButtons = state.buttons
                    a.gamepadLayout.ctrlEntryBitMap = com.zyz4.gkme.ctrlEntryBitMap
                }
            }
            launch {
                a.viewModel.ledState.collect { led ->
                    // 透传 PC 端模拟手柄的 LED 颜色与玩家指示灯到实体手柄
                    a.physicalControllerHandler.setLedColor(led.color, led.playerLed)
                    // 外观颜色绑定到 LED 时实时更新
                    a.viewModel.applyLedColorToAppearance(led.color)
                    if (a.settingsInflated) a.syncAppearanceUI()
                }
            }
            launch {
                a.viewModel.presetInfos.collect { _ ->
                    if (a.inSettings) a.refreshPresetList()
                }
            }
            launch {
                a.viewModel.pairedDeviceName.collect { name ->
                    a.updatePairedDeviceVisibility(name)
                }
            }
            launch {
                a.viewModel.gyroDisplay.collect { (x, y, z) ->
                    if (!a.settingsInflated) return@collect
                    a.findViewById<android.widget.SeekBar>(R.id.seekGyroSensitivityX).progress = (x * 100).toInt().coerceIn(-3000, 3000)
                    a.findViewById<android.widget.SeekBar>(R.id.seekGyroSensitivityY).progress = (y * 100).toInt().coerceIn(-3000, 3000)
                    a.findViewById<android.widget.SeekBar>(R.id.seekGyroSensitivityZ).progress = (z * 100).toInt().coerceIn(-3000, 3000)
                    a.findViewById<TextView>(R.id.tvGyroSensitivityX).text = "X: %.2f".format(x)
                    a.findViewById<TextView>(R.id.tvGyroSensitivityY).text = "Y: %.2f".format(y)
                    a.findViewById<TextView>(R.id.tvGyroSensitivityZ).text = "Z: %.2f".format(z)
                }
            }
            launch {
                a.physicalControllerHandler.isConnected.collect { connected ->
                    a.viewModel.setPhysicalControllerConnected(connected)
                    a.viewModel.connectionManager.setPhysicalControllerConnected(connected)
                    a.applyEffectivePhysicalControllerSettings()

                    if (!a.settingsInflated) return@collect
                    val s = a.viewModel.settings.value

                    a.syncPhysicalControllerUI()
                    a.syncGyroSourceUI()

                    a.syncVoiceCoilUI()
                    a.audioControllerOutputEntries = a.controllerAudioEntries()
                    a.updateControllerAudioAdapter(a.findViewById(R.id.spinnerControllerAudio))
                    val ctrlPos = a.audioControllerOutputEntries.indexOf(s.controllerAudioOutput)
                        .let { if (it >= 0) it else 0 }
                    a.findViewById<Spinner>(R.id.spinnerControllerAudio).setSelection(ctrlPos)
                    a.syncGameVibrationUI()
                }
            }
            launch {
                a.physicalControllerHandler.connectedControllers.collect {
                    if (a.settingsInflated) {
                        a.syncGameVibrationUI()
                        a.syncPhysicalControllerUI()
                        a.syncVoiceCoilUI()
                        a.syncGyroSourceUI()
                    }
                    a.syncPhysicalControllerState()
                }
            }
            launch {
                a.physicalControllerHandler.controllerState.collect {
                    a.syncPhysicalControllerState()
                }
            }
            launch {
                a.physicalControllerHandler.gyroData.collect { gyro ->
                    val x = gyro[0]; val y = gyro[1]; val z = gyro[2]
                    if (a.settingsInflated) {
                        a.findViewById<TextView>(R.id.tvControllerGyroX).text = "X: %.2f".format(x)
                        a.findViewById<TextView>(R.id.tvControllerGyroY).text = "Y: %.2f".format(y)
                        a.findViewById<TextView>(R.id.tvControllerGyroZ).text = "Z: %.2f".format(z)
                        a.findViewById<android.widget.SeekBar>(R.id.seekControllerGyroX).progress = (x * 100).toInt().coerceIn(-3000, 3000)
                        a.findViewById<android.widget.SeekBar>(R.id.seekControllerGyroY).progress = (y * 100).toInt().coerceIn(-3000, 3000)
                        a.findViewById<android.widget.SeekBar>(R.id.seekControllerGyroZ).progress = (z * 100).toInt().coerceIn(-3000, 3000)
                    }
                    val s = a.viewModel.settings.value
                    val gyroEnabled = if (a.physicalControllerHandler.isConnected.value) s.controllerGyroEnabledConnected else s.controllerGyroEnabled
                    val activateMode = s.gyroActivateMode
                    val actualGyroEnabled = if (activateMode == GyroActivateMode.BUTTON) {
                        a.viewModel.gyroOverrideEnabled.value
                    } else {
                        gyroEnabled
                    }
                    if (actualGyroEnabled && a.physicalControllerHandler.controllerHasGyro) {
                        val accel = a.physicalControllerHandler.accelData.value
                        a.viewModel.onPhysicalControllerGyro(x, y, z, accel[0], accel[1], accel[2])
                    }
                }
            }
        }
    }
}
