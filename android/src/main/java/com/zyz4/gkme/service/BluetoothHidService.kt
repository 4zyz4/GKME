package com.zyz4.gkme.service

import android.annotation.SuppressLint
import com.zyz4.gkme.model.AppSettings
import kotlinx.coroutines.flow.StateFlow

enum class BluetoothTransportType { CLASSIC, BLE }

@SuppressLint("MissingPermission")
interface BluetoothHidService {
    fun start(settings: AppSettings, onOutputReport: (ByteArray) -> Unit)
    fun sendReport(report: ByteArray)
    fun sendMouseReport(button: Byte, dx: Byte, dy: Byte, wheel: Byte, hWheel: Byte)
    fun sendKeyboardReport(modifier: Byte, keys: ByteArray)
    fun restart(settings: AppSettings, onOutputReport: (ByteArray) -> Unit)
    fun stop()
    val connectionPhase: StateFlow<ConnectionPhase>
    val transportType: BluetoothTransportType
}
