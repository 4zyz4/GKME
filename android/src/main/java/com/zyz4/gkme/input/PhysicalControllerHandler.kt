package com.zyz4.gkme.input

import android.content.Context
import android.hardware.usb.UsbManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.zyz4.gkme.input.usb.DualSenseController
import com.zyz4.gkme.input.usb.Dualshock4Controller
import com.zyz4.gkme.input.usb.ProCon2Controller
import com.zyz4.gkme.input.usb.ProConController
import com.zyz4.gkme.input.usb.Xbox360Controller
import com.zyz4.gkme.input.usb.Xbox360WirelessDongle
import com.zyz4.gkme.input.usb.XboxOneController
import com.zyz4.gkme.model.ControllerDriver
import com.zyz4.gkme.model.TouchPoint
import com.zyz4.gkme.model.VibrationDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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
    private var compatibilityJob: Job? = null

    /** True while the USB driver cannot handle the attached gamepad and SDL3 is used instead. */
    private var fallbackToSdl = false

    /** The driver chosen by the user in the settings. */
    var driver: ControllerDriver = ControllerDriver.SDL3
        private set

    /** The driver actually in use; differs from [driver] when a fallback to SDL3 is active. */
    private val _activeDriver = MutableStateFlow(ControllerDriver.SDL3)
    val activeDriver: StateFlow<ControllerDriver> = _activeDriver.asStateFlow()

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
    private var storedInputControllerIndex = 0
    private var storedGyroControllerIndex = 0
    private var storedGameVibrationDevice = VibrationDevice.PHONE
    private var storedSwapPhoneMotors = false
    private var storedSwapControllerMotors = false

    override var controllerGyroEnabled: Boolean
        get() = storedControllerGyroEnabled
        set(value) { storedControllerGyroEnabled = value; backend?.controllerGyroEnabled = value }

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
        startCompatibilityMonitor()
    }

    override fun stop() {
        if (!started) return
        started = false
        compatibilityJob?.cancel()
        compatibilityJob = null
        stopBackend()
    }

    /** Switches the active driver, reconnecting the gamepad immediately. */
    fun setDriver(newDriver: ControllerDriver) {
        if (newDriver == driver) return
        driver = newDriver
        fallbackToSdl = false
        if (started) {
            stopBackend()
            createAndStartBackend()
        }
    }

    /** Reinitializes the current driver, immediately retrying connection. */
    fun reconnect() {
        if (started) {
            stopBackend()
            createAndStartBackend()
        }
    }

    private fun createAndStartBackend() {
        val effectiveDriver = if (fallbackToSdl) ControllerDriver.SDL3 else driver
        val newBackend: PhysicalControllerBackend = when (effectiveDriver) {
            ControllerDriver.SDL3 -> SdlPhysicalControllerBackend(context)
            ControllerDriver.AXIXI2233_USB -> UsbPhysicalControllerBackend(context)
        }
        newBackend.controllerGyroEnabled = storedControllerGyroEnabled
        newBackend.inputControllerIndex = storedInputControllerIndex
        newBackend.gyroControllerIndex = storedGyroControllerIndex
        newBackend.gameVibrationDevice = storedGameVibrationDevice
        newBackend.swapPhoneMotors = storedSwapPhoneMotors
        newBackend.swapControllerMotors = storedSwapControllerMotors
        newBackend.onPointerCaptureNeeded = { enabled -> onPointerCaptureNeeded?.invoke(enabled) }
        newBackend.start()
        backend = newBackend
        _activeDriver.value = effectiveDriver
        startMirroring(newBackend)
    }

    /**
     * Watches for a gamepad that the USB driver cannot handle and transparently
     * retries the connection through SDL3. The user's driver preference is kept, so
     * once a supported controller is attached (or the current one is removed) the
     * USB driver is brought back up automatically.
     */
    private fun startCompatibilityMonitor() {
        compatibilityJob?.cancel()
        compatibilityJob = scope.launch {
            while (isActive) {
                delay(COMPATIBILITY_CHECK_INTERVAL_MS)
                if (driver != ControllerDriver.AXIXI2233_USB) continue
                val shouldFallback = hasSystemGamepad() && !isUsbDriverCompatible()
                if (shouldFallback == fallbackToSdl) continue
                fallbackToSdl = shouldFallback
                if (started) {
                    stopBackend()
                    createAndStartBackend()
                }
            }
        }
    }

    /** True when an attached USB device matches one of the controllers the USB driver supports. */
    private fun isUsbDriverCompatible(): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return try {
            manager.deviceList.values.any { device ->
                XboxOneController.canClaimDevice(device) ||
                    Xbox360Controller.canClaimDevice(device) ||
                    Xbox360WirelessDongle.canClaimDevice(device) ||
                    ProCon2Controller.canClaimDevice(device) ||
                    ProConController.canClaimDevice(device) ||
                    DualSenseController.canClaimDevice(device) ||
                    Dualshock4Controller.canClaimDevice(device)
            }
        } catch (_: Exception) {
            false
        }
    }

    /** True when Android reports an attached gamepad or joystick (wired or wireless). */
    private fun hasSystemGamepad(): Boolean {
        return try {
            InputDevice.getDeviceIds().any { id ->
                val device = InputDevice.getDevice(id) ?: return@any false
                val sources = device.sources
                (sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) ||
                    (sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
            }
        } catch (_: Exception) {
            false
        }
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

    override fun stopAllVibration() {
        backend?.stopAllVibration()
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

    override fun sendCompactFrame(
        rumbleLow: Int, rumbleHigh: Int,
        triggerTypeLeft: Byte, triggerTypeRight: Byte,
        triggerDataLeft: ByteArray?, triggerDataRight: ByteArray?,
        ledColor: Int, playerLed: Int,
        eventFlags: Byte,
    ) {
        backend?.sendCompactFrame(
            rumbleLow, rumbleHigh,
            triggerTypeLeft, triggerTypeRight,
            triggerDataLeft, triggerDataRight,
            ledColor, playerLed, eventFlags,
        )
    }

    companion object {
        private const val COMPATIBILITY_CHECK_INTERVAL_MS = 1000L
    }
}
