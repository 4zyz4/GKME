package com.zyz4.gamepademu

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zyz4.gamepademu.model.ConnectionMode
import com.zyz4.gamepademu.model.VibrationMotor
import com.zyz4.gamepademu.service.BluetoothTransportType
import com.zyz4.gamepademu.service.ConnectionPhase
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
                    a.findViewById<TextView>(R.id.tvConnectionStatus).text = st.statusText
                    val btn = a.findViewById<Button>(R.id.btnConnectAction)
                    btn.text = if (st.phase != ConnectionPhase.IDLE) "停止服务" else "启动服务"
                    if (st.restartToken != lastRestartToken) {
                        lastRestartToken = st.restartToken
                        a.discoverableRequested = false
                    }
                    val ip = if (a.viewModel.settings.value.connectionMode == ConnectionMode.WIFI &&
                        st.statusText != "未启动"
                    ) {
                        "本机 IP: ${a.viewModel.getServerIp()}"
                    } else ""
                    a.findViewById<TextView>(R.id.tvServerIp).text = ip

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
                    a.updatePairedDeviceVisibility(a.viewModel.pairedDeviceName.value)
                    if (s.keepScreenOn) {
                        a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        a.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    listOf(
                        R.id.btnGyroOriLandscape,
                        R.id.btnGyroOriPortrait,
                        R.id.btnGyroOriPortraitInverted,
                    ).forEach { id ->
                        a.findViewById<Button>(id).alpha = 1.0f
                    }
                    a.gamepadLayout.applyAppearance(s)
                }
            }
            launch {
                a.viewModel.currentPreset.collect { preset ->
                    a.viewModel.currentPresetGyroOrientation = preset.gyroOrientation
                    if (!a.gamepadLayout.isEditModeActive()) {
                        a.applyPreset(preset)
                    }
                    a.updateGyroChipsLockState(preset.gyroOrientation)
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
                    val s = a.viewModel.settings.value
                    val strongMapping = if (connected) s.strongVibrationMappingConnected else s.strongVibrationMapping
                    val weakMapping = if (connected) s.weakVibrationMappingConnected else s.weakVibrationMapping
                    val gyroEnabled = if (connected) s.controllerGyroEnabledConnected else s.controllerGyroEnabled

                    a.physicalControllerHandler.strongVibrationMapping = strongMapping
                    a.physicalControllerHandler.weakVibrationMapping = weakMapping
                    a.physicalControllerHandler.onControllerGyroSettingChanged(gyroEnabled)

                    a.findViewById<TextView>(R.id.tvPhysicalControllerStatus).text =
                        if (connected) "已连接: ${a.physicalControllerHandler.controllerName.value}"
                        else "未连接手柄"

                    a.findViewById<Switch>(R.id.switchControllerGyro).isChecked =
                        connected && gyroEnabled
                    a.findViewById<Switch>(R.id.switchControllerGyro).isEnabled = connected
                    a.findViewById<TextView>(R.id.tvControllerGyroNote).visibility =
                        if (gyroEnabled && connected) View.VISIBLE else View.GONE

                    val motorCount = a.physicalControllerHandler.controllerMotorCount
                    a.vibrationMappingEntries = if (connected) VibrationMotor.entries.take(motorCount) + VibrationMotor.PHONE_MOTOR + VibrationMotor.NONE
                        else listOf(VibrationMotor.PHONE_MOTOR, VibrationMotor.NONE)
                    fun sel(m: VibrationMotor) = a.vibrationMappingEntries.indexOf(m).let { if (it >= 0) it else a.vibrationMappingEntries.indexOf(VibrationMotor.PHONE_MOTOR) }
                    a.updateMappingAdapter(a.findViewById(R.id.spinnerStrongVibration))
                    a.findViewById<Spinner>(R.id.spinnerStrongVibration).setSelection(sel(strongMapping))
                    a.updateMappingAdapter(a.findViewById(R.id.spinnerWeakVibration))
                    a.findViewById<Spinner>(R.id.spinnerWeakVibration).setSelection(sel(weakMapping))
                }
            }
            launch {
                a.physicalControllerHandler.gyroData.collect { gyro ->
                    val x = gyro[0]; val y = gyro[1]; val z = gyro[2]
                    a.findViewById<TextView>(R.id.tvControllerGyroX).text = "X: %.2f".format(x)
                    a.findViewById<TextView>(R.id.tvControllerGyroY).text = "Y: %.2f".format(y)
                    a.findViewById<TextView>(R.id.tvControllerGyroZ).text = "Z: %.2f".format(z)
                    a.findViewById<android.widget.SeekBar>(R.id.seekControllerGyroX).progress = (x * 100).toInt().coerceIn(-3000, 3000)
                    a.findViewById<android.widget.SeekBar>(R.id.seekControllerGyroY).progress = (y * 100).toInt().coerceIn(-3000, 3000)
                    a.findViewById<android.widget.SeekBar>(R.id.seekControllerGyroZ).progress = (z * 100).toInt().coerceIn(-3000, 3000)
                    val s = a.viewModel.settings.value
                    val gyroEnabled = if (a.physicalControllerHandler.isConnected.value) s.controllerGyroEnabledConnected else s.controllerGyroEnabled
                    if (gyroEnabled && a.physicalControllerHandler.controllerHasGyro) {
                        val accel = a.physicalControllerHandler.accelData.value
                        a.viewModel.onPhysicalControllerGyro(x, y, z, accel[0], accel[1], accel[2])
                    }
                }
            }
        }
    }
}
