package com.zyz4.gkme.input

import android.content.Context
import android.view.InputDevice
import com.zyz4.gkme.input.usb.DualSenseAdaptiveTriggerEffect
import com.zyz4.gkme.input.usb.wireless.dualsense.DirectDualSenseBluetoothManager

/**
 * Physical-controller backend dedicated to a PS5 DualSense connected through Android's own
 * Bluetooth stack.
 *
 * Input is decoded with the standard platform gamepad mapping (inherited from
 * [InputManagerPhysicalControllerBackend]); output (rumble, adaptive triggers, lightbar and
 * player LEDs) is written directly to the controller through the hidden Bluetooth HID Host
 * profile exposed by [DirectDualSenseBluetoothManager]. Only Bluetooth DualSense controllers are
 * claimed.
 */
class DualSenseBluetoothPhysicalControllerBackend(context: Context) :
    InputManagerPhysicalControllerBackend(context) {

    private val direct = DirectDualSenseBluetoothManager(context).apply {
        onSendFailure = { /* fall back to the inherited Android vibrator path */ }
    }

    override fun acceptDevice(device: InputDevice): Boolean =
        super.acceptDevice(device) && direct.isDualSenseBluetooth(device)

    override fun onDevicesChanged(devices: List<InputDevice>) {
        direct.syncDevices(devices)
    }

    override fun toInfo(device: InputDevice): ControllerInfo {
        val info = super.toInfo(device)
        if (direct.outputFor(device.id) == null) return info
        return info.copy(
            motorCount = maxOf(info.motorCount, 2),
            hasTriggerRumble = true,
            hasAdaptiveTrigger = true,
        )
    }

    override fun driveRumbleOutput(device: InputDevice, low: Int, high: Int): Boolean {
        val output = direct.outputFor(device.id) ?: return false
        output.updateRumble((low * 257).toShort(), (high * 257).toShort())
        return true
    }

    override fun setLedColor(color: Int, playerLed: Int) {
        val r = ((color shr 16) and 0xFF).toByte()
        val g = ((color shr 8) and 0xFF).toByte()
        val b = (color and 0xFF).toByte()
        val pattern = if (playerLed != 0) {
            PLAYER_LED_PATTERNS[(Integer.bitCount(playerLed) - 1).coerceIn(0, 4)]
        } else {
            0
        }
        for (device in devices) {
            val output = direct.outputFor(device.id) ?: continue
            output.updateLightbar(r, g, b)
            output.updatePlayerLeds(pattern)
        }
    }

    override fun setAdaptiveTriggerEffects(
        controllerIndex: Int, eventFlags: Byte, typeLeft: Byte, typeRight: Byte,
        left: ByteArray?, right: ByteArray?,
    ) {
        val device = devices.getOrNull(controllerIndex) ?: return
        val output = direct.outputFor(device.id) ?: return
        val leftPayload = left ?: ByteArray(DualSenseAdaptiveTriggerEffect.PAYLOAD_SIZE)
        val rightPayload = right ?: ByteArray(DualSenseAdaptiveTriggerEffect.PAYLOAD_SIZE)
        if (eventFlags.toInt() and DualSenseAdaptiveTriggerEffect.PLAYER_LED_FLAG != 0 &&
            leftPayload.isNotEmpty()
        ) {
            output.updatePlayerLeds(leftPayload[0].toInt() and 0x1F)
        }
        output.updateAdaptiveTriggers(eventFlags, typeLeft, typeRight, leftPayload, rightPayload)
    }

    override fun setTriggerRumble(controllerIndex: Int, leftTrigger: Int, rightTrigger: Int) {
        val device = devices.getOrNull(controllerIndex) ?: return
        val output = direct.outputFor(device.id) ?: return
        output.updateTriggerRumble(
            (leftTrigger.coerceIn(0, 255) * 257).toShort(),
            (rightTrigger.coerceIn(0, 255) * 257).toShort(),
        )
    }

    override fun stopAllVibration() {
        for (device in devices) {
            direct.outputFor(device.id)?.updateRumble(0, 0)
        }
        super.stopAllVibration()
    }

    override fun stop() {
        super.stop()
        direct.close()
    }

    companion object {
        // DualSense player-indicator LED bitmasks for 1..5 players (mirrors the USB backend).
        private val PLAYER_LED_PATTERNS = intArrayOf(0x04, 0x0A, 0x15, 0x1B, 0x1F)
    }
}
