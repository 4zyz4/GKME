package com.zyz4.gkme.input

import android.view.KeyEvent
import android.view.MotionEvent
import com.zyz4.gkme.model.TouchPoint
import com.zyz4.gkme.model.VibrationDevice
import kotlinx.coroutines.flow.StateFlow

/** A connected physical gamepad that can receive game rumble. */
data class ControllerInfo(
    val id: Int,
    val name: String,
    val motorCount: Int,
    /** True when the controller exposes rumble in its triggers (e.g. Xbox One). */
    val hasTriggerRumble: Boolean = false,
    /** True when the controller exposes natively programmable adaptive triggers (DualSense). */
    val hasAdaptiveTrigger: Boolean = false,
    /** True when the controller exposes motion (gyro/accelerometer) sensors. */
    val hasGyro: Boolean = false,
    /** True when the controller exposes analog (linear) triggers. */
    val hasAnalogTrigger: Boolean = false,
    /** True when the controller exposes a touchpad. */
    val hasTouchpad: Boolean = false,
    /** Mask of buttons the controller physically exposes (see PhysicalInputs). 0 = unknown. */
    val supportedButtons: Int = 0,
)

/**
 * Common surface shared by the SDL3 and Axixi2233 USB physical-controller backends.
 * [PhysicalControllerHandler] delegates to whichever backend is selected in settings.
 */
interface PhysicalControllerBackend {

    val isConnected: StateFlow<Boolean>
    val controllerState: StateFlow<PhysicalControllerState>
    val connectedControllers: StateFlow<List<ControllerInfo>>
    val gyroData: StateFlow<FloatArray>
    val accelData: StateFlow<FloatArray>

    var controllerGyroEnabled: Boolean
    var controllerHasGyro: Boolean

    /** Index into [connectedControllers] used as the input source; -1 disables controller input. */
    var inputControllerIndex: Int

    /** Index into [connectedControllers] whose gyro/accel sensors are read. */
    var gyroControllerIndex: Int

    var gameVibrationDevice: VibrationDevice
    var swapPhoneMotors: Boolean
    var swapControllerMotors: Boolean

    var onPointerCaptureNeeded: ((Boolean) -> Unit)?
    var isPointerCaptureActive: Boolean

    fun start()
    fun stop()

    fun onControllerGyroSettingChanged(enabled: Boolean)
    fun ensureGyroRegistered()

    fun handleKeyEvent(event: KeyEvent): Boolean
    fun handleMotionEvent(event: MotionEvent): Boolean

    fun setCapturedTouchpadState(
        normalizedX: Float, normalizedY: Float,
        touches: List<TouchPoint>, touchpadTouch: Boolean, touchpadClick: Boolean,
    )

    fun setLedColor(color: Int, playerLed: Int)
    fun setControllerMotorsVibration(controllerIndex: Int, leftIntensity: Int, rightIntensity: Int)
    fun rumble(lowFreqMotor: Int, highFreqMotor: Int)

    /**
     * Commands every output (phone and all attached controllers) off. Called when the
     * stream ends or the host disconnects, so a rumble latched on by the last packet
     * cannot keep running after the host is gone.
     */
    fun stopAllVibration() {
        rumble(0, 0)
    }

    /** Updates the latest voice-coil motor amplitudes for rumble conflict resolution. */
    fun setVoiceCoilMotorOutput(leftAmp: Int, rightAmp: Int) = Unit

    // ── Controller audio / voice coil (USB driver) ──

    /** True when the controller at [controllerIndex] can play PCM through its voice-coil haptics. */
    fun controllerSupportsVoiceCoilPcm(controllerIndex: Int): Boolean = false

    /** True when the controller at [controllerIndex] exposes a speaker/audio endpoint. */
    fun controllerSupportsAudio(controllerIndex: Int): Boolean = false

    /** Sends a 4-channel, 48 kHz, S16LE frame to the controller voice coil. */
    fun submitVoiceCoilFrame(controllerIndex: Int, frame: ByteArray): Boolean = false

    /** Sends a 4-channel, 48 kHz, S16LE frame to the controller speaker. */
    fun submitControllerAudioFrame(controllerIndex: Int, frame: ByteArray): Boolean = false

    // ── HD rumble (Nintendo Switch family, USB driver) ─────

    /** True when the controller at [controllerIndex] can play native HD rumble. */
    fun controllerSupportsHdRumble(controllerIndex: Int): Boolean = false

    /**
     * Drives the controller's two linear actuators with HD rumble bands.
     * Frequencies are in Hz and amplitudes in 0..1.
     */
    fun setControllerHdRumble(
        controllerIndex: Int,
        leftHighFreq: Float, leftHighAmp: Float, leftLowFreq: Float, leftLowAmp: Float,
        rightHighFreq: Float, rightHighAmp: Float, rightLowFreq: Float, rightLowAmp: Float,
    ) = Unit

    // ── Adaptive triggers / trigger rumble (reserved interfaces) ──

    fun setAdaptiveTriggerEffects(
        controllerIndex: Int, eventFlags: Byte, typeLeft: Byte, typeRight: Byte,
        left: ByteArray?, right: ByteArray?,
    ) = Unit

    fun setTriggerRumble(controllerIndex: Int, leftTrigger: Int, rightTrigger: Int) = Unit

    /** Sends a combined rumble + trigger + LED report in one HID bulkTransfer. */
    fun sendCompactFrame(
        rumbleLow: Int, rumbleHigh: Int,
        triggerTypeLeft: Byte, triggerTypeRight: Byte,
        triggerDataLeft: ByteArray?, triggerDataRight: ByteArray?,
        ledColor: Int, playerLed: Int,
        eventFlags: Byte = 0x0F,
    ) = Unit
}
