package com.zyz4.gkme.input

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import com.zyz4.gkme.model.ControllerDriver
import com.zyz4.gkme.model.TouchPoint
import com.zyz4.gkme.model.VibrationDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Front-end for the physical gamepad stack.
 *
 * The user can pick between the built-in SDL3 backend and the "Axixi2233的USB驱动"
 * backend in the physical-controller settings. Switching tears the active backend
 * down and immediately brings the other one up, re-applying the stored settings so
 * the selected gamepad reconnects right away.
 */
class PhysicalControllerHandler(private val context: Context) : PhysicalControllerBackend {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var mirrorJobs = mutableListOf<Job>()

    private var started = false
    private var backend: PhysicalControllerBackend? = null

    var driver: ControllerDriver = ControllerDriver.SDL3
        private set

    // Facade-owned flows so existing collectors stay valid across driver switches.
    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _controllerState = MutableStateFlow(PhysicalControllerState())
    override val controllerState: StateFlow<PhysicalControllerState> = _controllerState.asStateFlow()

    private val _connectedControllers = MutableStateFlow<List<ControllerInfo>>(emptyList())
    override val connectedControllers: StateFlow<List<ControllerInfo>> = _connectedControllers.asStateFlow()

    private val _gyroData = MutableStateFlow(FloatArray(3))
    override val gyroData: StateFlow<FloatArray> = _gyroData.asStateFlow()

    private val _accelData = MutableStateFlow(FloatArray(3))
    override val accelData: StateFlow<FloatArray> = _accelData.asStateFlow()

    // Stored settings, re-applied to a freshly created backend after a switch.
    private var storedControllerGyroEnabled = false
    private var storedNonLinearTriggerAdaptation = false
    private var storedInputControllerIndex = 0
    private var storedGyroControllerIndex = 0
    private var storedGameVibrationDevice = VibrationDevice.PHONE
    private var storedSwapPhoneMotors = false
    private var storedSwapControllerMotors = false

    override var controllerGyroEnabled: Boolean
        get() = storedControllerGyroEnabled
        set(value) { storedControllerGyroEnabled = value; backend?.controllerGyroEnabled = value }

    override var nonLinearTriggerAdaptation: Boolean
        get() = storedNonLinearTriggerAdaptation
        set(value) { storedNonLinearTriggerAdaptation = value; backend?.nonLinearTriggerAdaptation = value }

    override var controllerHasGyro: Boolean
        get() = backend?.controllerHasGyro ?: false
        set(_) { }

    override var inputControllerIndex: Int
        get() = storedInputControllerIndex
        set(value) { storedInputControllerIndex = value; backend?.inputControllerIndex = value }

    override var gyroControllerIndex: Int
        get() = storedGyroControllerIndex
        set(value) { storedGyroControllerIndex = value; backend?.gyroControllerIndex = value }

    override var gameVibrationDevice: VibrationDevice
        get() = storedGameVibrationDevice
        set(value) { storedGameVibrationDevice = value; backend?.gameVibrationDevice = value }

    override var swapPhoneMotors: Boolean
        get() = storedSwapPhoneMotors
        set(value) { storedSwapPhoneMotors = value; backend?.swapPhoneMotors = value }

    override var swapControllerMotors: Boolean
        get() = storedSwapControllerMotors
        set(value) { storedSwapControllerMotors = value; backend?.swapControllerMotors = value }

    override var onPointerCaptureNeeded: ((Boolean) -> Unit)? = null

    override var isPointerCaptureActive: Boolean = false

    // ── Lifecycle ──────────────────────────────────────────

    override fun start() {
        if (started) return
        started = true
        createAndStartBackend()
    }

    override fun stop() {
        if (!started) return
        started = false
        stopBackend()
    }

    /** Switches the active driver, reconnecting the gamepad immediately. */
    fun setDriver(newDriver: ControllerDriver) {
        if (newDriver == driver) return
        driver = newDriver
        if (started) {
            stopBackend()
            createAndStartBackend()
        }
    }

    private fun createAndStartBackend() {
        val newBackend: PhysicalControllerBackend = when (driver) {
            ControllerDriver.SDL3 -> SdlPhysicalControllerBackend(context)
            ControllerDriver.AXIXI2233_USB -> UsbPhysicalControllerBackend(context)
        }
        newBackend.controllerGyroEnabled = storedControllerGyroEnabled
        newBackend.nonLinearTriggerAdaptation = storedNonLinearTriggerAdaptation
        newBackend.inputControllerIndex = storedInputControllerIndex
        newBackend.gyroControllerIndex = storedGyroControllerIndex
        newBackend.gameVibrationDevice = storedGameVibrationDevice
        newBackend.swapPhoneMotors = storedSwapPhoneMotors
        newBackend.swapControllerMotors = storedSwapControllerMotors
        newBackend.onPointerCaptureNeeded = { enabled -> onPointerCaptureNeeded?.invoke(enabled) }
        newBackend.start()
        backend = newBackend
        startMirroring(newBackend)
    }

    private fun stopBackend() {
        mirrorJobs.forEach { it.cancel() }
        mirrorJobs.clear()
        backend?.stop()
        backend = null
        _isConnected.value = false
        _connectedControllers.value = emptyList()
        _controllerState.value = PhysicalControllerState()
        _gyroData.value = floatArrayOf(0f, 0f, 0f)
        _accelData.value = floatArrayOf(0f, 0f, 0f)
    }

    private fun startMirroring(b: PhysicalControllerBackend) {
        mirrorJobs += scope.launch { b.isConnected.collect { _isConnected.value = it } }
        mirrorJobs += scope.launch { b.controllerState.collect { _controllerState.value = it } }
        mirrorJobs += scope.launch { b.connectedControllers.collect { _connectedControllers.value = it } }
        mirrorJobs += scope.launch { b.gyroData.collect { _gyroData.value = it } }
        mirrorJobs += scope.launch { b.accelData.collect { _accelData.value = it } }
    }

    // ── Forwarding ─────────────────────────────────────────

    override fun onControllerGyroSettingChanged(enabled: Boolean) {
        storedControllerGyroEnabled = enabled
        backend?.onControllerGyroSettingChanged(enabled)
    }

    override fun ensureGyroRegistered() {
        backend?.ensureGyroRegistered()
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean = backend?.handleKeyEvent(event) ?: false

    override fun handleMotionEvent(event: MotionEvent): Boolean = backend?.handleMotionEvent(event) ?: false

    override fun setCapturedTouchpadState(
        normalizedX: Float, normalizedY: Float,
        touches: List<TouchPoint>, touchpadTouch: Boolean, touchpadClick: Boolean,
    ) {
        backend?.setCapturedTouchpadState(normalizedX, normalizedY, touches, touchpadTouch, touchpadClick)
    }

    override fun setLedColor(color: Int, playerLed: Int) {
        backend?.setLedColor(color, playerLed)
    }

    override fun setControllerMotorsVibration(controllerIndex: Int, leftIntensity: Int, rightIntensity: Int) {
        backend?.setControllerMotorsVibration(controllerIndex, leftIntensity, rightIntensity)
    }

    override fun rumble(lowFreqMotor: Int, highFreqMotor: Int) {
        backend?.rumble(lowFreqMotor, highFreqMotor)
    }

    override fun setVoiceCoilMotorOutput(leftAmp: Int, rightAmp: Int) {
        backend?.setVoiceCoilMotorOutput(leftAmp, rightAmp)
    }

    override fun controllerSupportsVoiceCoilPcm(controllerIndex: Int): Boolean =
        backend?.controllerSupportsVoiceCoilPcm(controllerIndex) ?: false

    override fun controllerSupportsAudio(controllerIndex: Int): Boolean =
        backend?.controllerSupportsAudio(controllerIndex) ?: false

    override fun submitVoiceCoilFrame(controllerIndex: Int, frame: ByteArray): Boolean =
        backend?.submitVoiceCoilFrame(controllerIndex, frame) ?: false

    override fun submitControllerAudioFrame(controllerIndex: Int, frame: ByteArray): Boolean =
        backend?.submitControllerAudioFrame(controllerIndex, frame) ?: false

    override fun setAdaptiveTriggerEffects(
        controllerIndex: Int, eventFlags: Byte, typeLeft: Byte, typeRight: Byte,
        left: ByteArray?, right: ByteArray?,
    ) {
        backend?.setAdaptiveTriggerEffects(controllerIndex, eventFlags, typeLeft, typeRight, left, right)
    }

    override fun setTriggerRumble(controllerIndex: Int, leftTrigger: Int, rightTrigger: Int) {
        backend?.setTriggerRumble(controllerIndex, leftTrigger, rightTrigger)
    }

    override fun playVoiceCoilTest(controllerIndex: Int) {
        backend?.playVoiceCoilTest(controllerIndex)
    }
}
