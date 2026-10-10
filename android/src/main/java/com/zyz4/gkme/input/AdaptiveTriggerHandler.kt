package com.zyz4.gkme.input

import com.zyz4.gkme.model.AdaptiveTriggerDevice
import com.zyz4.gkme.model.AdaptiveTriggerTargetType
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Routes PC adaptive-trigger effects to the actuator chosen in the vibration settings.
 *
 *  - [AdaptiveTriggerTargetType.CONTROLLER_TRIGGER] forwards the effect natively on
 *    adaptive-trigger controllers (DualSense). Controllers that only expose trigger
 *    rumble (e.g. Xbox One) receive it as trigger vibration.
 *  - [AdaptiveTriggerTargetType.CONTROLLER_MOTOR] / [AdaptiveTriggerTargetType.PHONE_MOTOR]
 *    convert the effect to motor vibration: the trigger position selects the amplitude
 *    defined by the effect bytes. Both forward each trigger's own native cycling frequency
 *    so HD-capable actuators (phone LRA, Switch/Pro 2 HD rumble, DualSense voice coil) can
 *    reproduce the effect's rate; amplitude-only motors ignore it.
 *
 * The same controller can be selected once as a motor target and once as a trigger target.
 */
class AdaptiveTriggerHandler(
    private val controllerHandler: PhysicalControllerHandler,
    private val phoneVibration: (left: Int, right: Int, leftFreqHz: Double, rightFreqHz: Double) -> Unit,
    private val controllerVibration: (controllerIndex: Int, left: Int, right: Int, leftFreqHz: Double, rightFreqHz: Double) -> Unit,
) {
    private var device: AdaptiveTriggerDevice = AdaptiveTriggerDevice.NONE
    private var swap: Boolean = false

    private var leftRaw: ByteArray? = null
    private var rightRaw: ByteArray? = null
    private var leftEffect: AdaptiveTriggerEffect = AdaptiveTriggerEffect.parse(null)
    private var rightEffect: AdaptiveTriggerEffect = AdaptiveTriggerEffect.parse(null)
    private var leftPosition = 0
    private var rightPosition = 0

    // Rising-edge state for the resistance effects (Feedback/Weapon): they must vibrate
    // only while the trigger is pressed deeper, so the amplitude is gated on an increase.
    private var leftRising = false
    private var rightRising = false

    // Xbox One trigger-rumble source (separate PC field). When active it overrides
    // the DualSense-effect source: the amplitudes are applied unconditionally instead
    // of being derived from the trigger position.
    private var rumbleActive = false
    private var rumbleLeft = 0
    private var rumbleRight = 0

    private var lastOutLeft = -1
    private var lastOutRight = -1
    private var lastOutLeftFreq = -1.0
    private var lastOutRightFreq = -1.0

    // Last native effect actually written to the pad, tracked per trigger so an update
    // that only changes one side writes that trigger's flag instead of re-sending both.
    private var lastLeftNativeKey: Int? = null
    private var lastRightNativeKey: Int? = null

    // Trigger-position updates arrive on the UI thread at the full input rate, and each
    // render can perform a blocking controller HID write (up to 1s). Rendering on the UI
    // thread stalled the whole app — including the gamepad-state producer that the network
    // send loop reads, so the PC stopped receiving input while the trigger moved. Positions
    // are therefore rendered on a dedicated worker; the single-slot queue conflates the
    // high-rate updates down to the latest position.
    private val renderExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        { r -> Thread(r, "GkmeAdaptive").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    /** Selects the output actuator; stops the previous one. */
    @Synchronized
    fun setTarget(newDevice: AdaptiveTriggerDevice, newSwap: Boolean) {
        if (newDevice == device && newSwap == swap) return
        stopOutput(device)
        device = newDevice
        swap = newSwap
        leftRising = false
        rightRising = false
        lastOutLeft = -1
        lastOutRight = -1
        lastOutLeftFreq = -1.0
        lastOutRightFreq = -1.0
        lastLeftNativeKey = null
        lastRightNativeKey = null
        render(force = true)
    }

    /** Called when the PC sends new left/right adaptive-trigger effects. */
    @Synchronized
    fun onEffects(left: ByteArray?, right: ByteArray?) {
        leftRaw = left
        rightRaw = right
        leftEffect = AdaptiveTriggerEffect.parse(left)
        rightEffect = AdaptiveTriggerEffect.parse(right)
        rumbleActive = false
        render(force = true)
    }

    /**
     * Called when the PC forwards Xbox One impulse-trigger rumble (0..255 per side).
     * The values are handled as an adaptive-trigger input but applied unconditionally:
     * a DualSense target receives a synthesised 0x26 Vibration effect, an Xbox target
     * the raw trigger rumble, and a motor target the amplitude directly.
     */
    @Synchronized
    fun onTriggerRumble(left: Int, right: Int) {
        rumbleLeft = left.coerceIn(0, 255)
        rumbleRight = right.coerceIn(0, 255)
        rumbleActive = true
        // Re-assert on every compact frame (like the effect source): a rumble-driven
        // motor output has no position updates to refresh it, so without this a
        // finite-duration controller rumble would lapse and the vibration would vanish.
        render(force = true)
    }

    /** Called whenever the emulated trigger positions change. Rendered off the UI thread. */
    fun onTriggerPositions(left: Int, right: Int) {
        try {
            renderExecutor.execute { handleTriggerPositions(left, right) }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun handleTriggerPositions(left: Int, right: Int) {
        if (left == leftPosition && right == rightPosition) {
            // Pressure stopped increasing: clear the rising edges so resistance effects
            // stop vibrating while the trigger is held or released.
            if (leftRising || rightRising) {
                leftRising = false
                rightRising = false
                render(force = false)
            }
            return
        }
        leftRising = left > leftPosition
        rightRising = right > rightPosition
        leftPosition = left
        rightPosition = right
        render(force = false)
    }

    /** Resolved motor output: per-channel amplitude plus each channel's cycling frequency. */
    private data class MotorOutput(
        val left: Int,
        val right: Int,
        val leftFrequencyHz: Double,
        val rightFrequencyHz: Double,
    )

    private fun render(force: Boolean) {
        val target = device
        when (target.type) {
            AdaptiveTriggerTargetType.NONE -> stopOutput(target)
            AdaptiveTriggerTargetType.PHONE_MOTOR ->
                renderMotor(force) { l, r, lf, rf -> phoneVibration(l, r, lf, rf) }
            AdaptiveTriggerTargetType.CONTROLLER_MOTOR ->
                renderMotor(force) { l, r, lf, rf -> controllerVibration(target.controllerIndex, l, r, lf, rf) }
            AdaptiveTriggerTargetType.CONTROLLER_TRIGGER -> renderTrigger(force, target)
        }
    }

    /** Motor output for the active source. Rumble values pass through unchanged; effect
     *  sources resolve the current trigger position, gate the resistance effects on a rising
     *  edge, and carry each side's own frequency. Only the output channels swap. */
    private fun motorOutput(): MotorOutput {
        if (rumbleActive) {
            return if (swap) MotorOutput(rumbleRight, rumbleLeft, 0.0, 0.0)
            else MotorOutput(rumbleLeft, rumbleRight, 0.0, 0.0)
        }
        val lEffect = if (swap) rightEffect else leftEffect
        val lPosition = if (swap) rightPosition else leftPosition
        val lRising = if (swap) rightRising else leftRising
        val rEffect = if (swap) leftEffect else rightEffect
        val rPosition = if (swap) leftPosition else rightPosition
        val rRising = if (swap) leftRising else rightRising
        val left = amplitudeOf(lEffect, lPosition, lRising)
        val right = amplitudeOf(rEffect, rPosition, rRising)
        return MotorOutput(left, right, lEffect.frequencyHz, rEffect.frequencyHz)
    }

    private fun amplitudeOf(effect: AdaptiveTriggerEffect, position: Int, rising: Boolean): Int {
        val amplitude = effect.amplitudeFor(position)
        return if (effect.risingOnly && !rising) 0 else amplitude
    }

    private fun renderMotor(force: Boolean, out: (Int, Int, Double, Double) -> Unit) {
        val output = motorOutput()
        if (!force && output.left == lastOutLeft && output.right == lastOutRight &&
            output.leftFrequencyHz == lastOutLeftFreq && output.rightFrequencyHz == lastOutRightFreq
        ) {
            return
        }
        lastOutLeft = output.left
        lastOutRight = output.right
        lastOutLeftFreq = output.leftFrequencyHz
        lastOutRightFreq = output.rightFrequencyHz
        out(output.left, output.right, output.leftFrequencyHz, output.rightFrequencyHz)
    }

    private fun renderTrigger(force: Boolean, target: AdaptiveTriggerDevice) {
        val info = controllerHandler.connectedControllers.value.getOrNull(target.controllerIndex)
        val output = motorOutput()
        when {
            info?.hasAdaptiveTrigger == true ->
                emitAdaptiveTrigger(target, output.left, output.right)
            info?.hasTriggerRumble == true ->
                emitTriggerRumble(target, force, output.left, output.right)
            else -> emitControllerMotor(
                target, force, output.left, output.right,
                output.leftFrequencyHz, output.rightFrequencyHz,
            )
        }
    }

    /** Native DualSense output: raw effect blocks for the effect source, or a synthesised
     *  0x26 Vibration effect when the active source is Xbox trigger rumble. */
    private fun emitAdaptiveTrigger(target: AdaptiveTriggerDevice, left: Int, right: Int) {
        val typeLeft: Byte
        val typeRight: Byte
        val dataLeft: ByteArray?
        val dataRight: ByteArray?
        val keyLeft: Int
        val keyRight: Int
        if (rumbleActive) {
            val l = AdaptiveTriggerEffect.vibrationPacket(left)
            val r = AdaptiveTriggerEffect.vibrationPacket(right)
            typeLeft = l[0]
            typeRight = r[0]
            dataLeft = l.copyOfRange(1, l.size)
            dataRight = r.copyOfRange(1, r.size)
            keyLeft = l.contentHashCode()
            keyRight = r.contentHashCode()
        } else {
            val l = if (swap) rightRaw else leftRaw
            val r = if (swap) leftRaw else rightRaw
            typeLeft = (l?.getOrNull(0) ?: 0).toByte()
            typeRight = (r?.getOrNull(0) ?: 0).toByte()
            dataLeft = payload(l)
            dataRight = payload(r)
            keyLeft = l?.contentHashCode() ?: 0
            keyRight = r?.contentHashCode() ?: 0
        }
        // Only flag the triggers whose effect actually changed; the pad latches the rest.
        var eventFlags = 0
        if (keyLeft != lastLeftNativeKey) eventFlags = eventFlags or TRIGGER_FLAG_LEFT
        if (keyRight != lastRightNativeKey) eventFlags = eventFlags or TRIGGER_FLAG_RIGHT
        if (eventFlags == 0) return
        lastLeftNativeKey = keyLeft
        lastRightNativeKey = keyRight
        controllerHandler.setAdaptiveTriggerEffects(
            target.controllerIndex, eventFlags.toByte(), typeLeft, typeRight, dataLeft, dataRight,
        )
    }

    private fun emitTriggerRumble(target: AdaptiveTriggerDevice, force: Boolean, left: Int, right: Int) {
        if (!force && left == lastOutLeft && right == lastOutRight) return
        lastOutLeft = left
        lastOutRight = right
        controllerHandler.setTriggerRumble(target.controllerIndex, left, right)
    }

    private fun emitControllerMotor(
        target: AdaptiveTriggerDevice,
        force: Boolean,
        left: Int,
        right: Int,
        leftFrequencyHz: Double,
        rightFrequencyHz: Double,
    ) {
        if (!force && left == lastOutLeft && right == lastOutRight &&
            leftFrequencyHz == lastOutLeftFreq && rightFrequencyHz == lastOutRightFreq
        ) {
            return
        }
        lastOutLeft = left
        lastOutRight = right
        lastOutLeftFreq = leftFrequencyHz
        lastOutRightFreq = rightFrequencyHz
        controllerVibration(target.controllerIndex, left, right, leftFrequencyHz, rightFrequencyHz)
    }

    private fun payload(raw: ByteArray?): ByteArray? =
        if (raw != null && raw.size > 1) raw.copyOfRange(1, raw.size) else null

    private fun stopOutput(target: AdaptiveTriggerDevice) {
        when (target.type) {
            AdaptiveTriggerTargetType.NONE -> Unit
            AdaptiveTriggerTargetType.PHONE_MOTOR -> phoneVibration(0, 0, 0.0, 0.0)
            AdaptiveTriggerTargetType.CONTROLLER_MOTOR ->
                controllerVibration(target.controllerIndex, 0, 0, 0.0, 0.0)
            AdaptiveTriggerTargetType.CONTROLLER_TRIGGER -> {
                controllerHandler.setAdaptiveTriggerEffects(
                    target.controllerIndex, TRIGGER_FLAG_MASK.toByte(), 0, 0, null, null,
                )
                controllerHandler.setTriggerRumble(target.controllerIndex, 0, 0)
                controllerVibration(target.controllerIndex, 0, 0, 0.0, 0.0)
            }
        }
        lastOutLeft = -1
        lastOutRight = -1
        lastOutLeftFreq = -1.0
        lastOutRightFreq = -1.0
        lastLeftNativeKey = null
        lastRightNativeKey = null
    }

    private companion object {
        const val TRIGGER_FLAG_RIGHT = 0x04
        const val TRIGGER_FLAG_LEFT = 0x08
        const val TRIGGER_FLAG_MASK = TRIGGER_FLAG_LEFT or TRIGGER_FLAG_RIGHT
    }
}
