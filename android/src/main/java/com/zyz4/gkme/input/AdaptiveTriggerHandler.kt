package com.zyz4.gkme.input

import com.zyz4.gkme.model.AdaptiveTriggerDevice
import com.zyz4.gkme.model.AdaptiveTriggerTargetType

/**
 * Routes PC adaptive-trigger effects to the actuator chosen in the vibration settings.
 *
 *  - [AdaptiveTriggerTargetType.CONTROLLER_TRIGGER] forwards the effect natively on
 *    adaptive-trigger controllers (DualSense). Controllers that only expose trigger
 *    rumble (e.g. Xbox One) receive it as trigger vibration.
 *  - [AdaptiveTriggerTargetType.CONTROLLER_MOTOR] / [AdaptiveTriggerTargetType.PHONE_MOTOR]
 *    convert the effect to motor vibration: the trigger position selects the amplitude
 *    defined by the effect bytes.
 *
 * The same controller can be selected once as a motor target and once as a trigger target.
 */
class AdaptiveTriggerHandler(
    private val controllerHandler: PhysicalControllerHandler,
    private val phoneVibration: (left: Int, right: Int) -> Unit,
) {
    private var device: AdaptiveTriggerDevice = AdaptiveTriggerDevice.NONE
    private var swap: Boolean = false

    private var leftRaw: ByteArray? = null
    private var rightRaw: ByteArray? = null
    private var leftEffect: AdaptiveTriggerEffect = AdaptiveTriggerEffect.parse(null)
    private var rightEffect: AdaptiveTriggerEffect = AdaptiveTriggerEffect.parse(null)
    private var leftPosition = 0
    private var rightPosition = 0

    private var lastOutLeft = -1
    private var lastOutRight = -1
    private var lastNativeKey: String? = null

    /** Selects the output actuator; stops the previous one. */
    @Synchronized
    fun setTarget(newDevice: AdaptiveTriggerDevice, newSwap: Boolean) {
        if (newDevice == device && newSwap == swap) return
        stopOutput(device)
        device = newDevice
        swap = newSwap
        lastOutLeft = -1
        lastOutRight = -1
        lastNativeKey = null
        render(force = true)
    }

    /** Called when the PC sends new left/right adaptive-trigger effects. */
    @Synchronized
    fun onEffects(left: ByteArray?, right: ByteArray?) {
        leftRaw = left
        rightRaw = right
        leftEffect = AdaptiveTriggerEffect.parse(left)
        rightEffect = AdaptiveTriggerEffect.parse(right)
        render(force = true)
    }

    /** Called whenever the emulated trigger positions change. */
    @Synchronized
    fun onTriggerPositions(left: Int, right: Int) {
        if (left == leftPosition && right == rightPosition) return
        leftPosition = left
        rightPosition = right
        render(force = false)
    }

    private fun render(force: Boolean) {
        val target = device
        when (target.type) {
            AdaptiveTriggerTargetType.NONE -> stopOutput(target)
            AdaptiveTriggerTargetType.PHONE_MOTOR -> renderMotor(force) { l, r -> phoneVibration(l, r) }
            AdaptiveTriggerTargetType.CONTROLLER_MOTOR ->
                renderMotor(force) { l, r -> controllerHandler.setControllerMotorsVibration(target.controllerIndex, l, r) }
            AdaptiveTriggerTargetType.CONTROLLER_TRIGGER -> renderTrigger(force, target)
        }
    }

    /** Converts the effects to motor amplitudes. Only the output channel is swapped. */
    private fun renderMotor(force: Boolean, out: (Int, Int) -> Unit) {
        val left = if (swap) rightEffect.amplitudeFor(rightPosition) else leftEffect.amplitudeFor(leftPosition)
        val right = if (swap) leftEffect.amplitudeFor(leftPosition) else rightEffect.amplitudeFor(rightPosition)
        if (!force && left == lastOutLeft && right == lastOutRight) return
        lastOutLeft = left
        lastOutRight = right
        out(left, right)
    }

    private fun renderTrigger(force: Boolean, target: AdaptiveTriggerDevice) {
        val info = controllerHandler.connectedControllers.value.getOrNull(target.controllerIndex)
        val left = if (swap) rightEffect.amplitudeFor(rightPosition) else leftEffect.amplitudeFor(leftPosition)
        val right = if (swap) leftEffect.amplitudeFor(leftPosition) else rightEffect.amplitudeFor(rightPosition)
        when {
            info?.hasAdaptiveTrigger == true -> {
                val key = nativeKey()
                if (!force && key == lastNativeKey) return
                lastNativeKey = key
                val l = if (swap) rightRaw else leftRaw
                val r = if (swap) leftRaw else rightRaw
                controllerHandler.setAdaptiveTriggerEffects(
                    target.controllerIndex, 0x0F,
                    (l?.getOrNull(0) ?: 0).toByte(),
                    (r?.getOrNull(0) ?: 0).toByte(),
                    payload(l), payload(r),
                )
            }
            info?.hasTriggerRumble == true -> emitTriggerRumble(target, force, left, right)
            else -> emitControllerMotor(target, force, left, right)
        }
    }

    private fun emitTriggerRumble(target: AdaptiveTriggerDevice, force: Boolean, left: Int, right: Int) {
        if (!force && left == lastOutLeft && right == lastOutRight) return
        lastOutLeft = left
        lastOutRight = right
        controllerHandler.setTriggerRumble(target.controllerIndex, left, right)
    }

    private fun emitControllerMotor(target: AdaptiveTriggerDevice, force: Boolean, left: Int, right: Int) {
        if (!force && left == lastOutLeft && right == lastOutRight) return
        lastOutLeft = left
        lastOutRight = right
        controllerHandler.setControllerMotorsVibration(target.controllerIndex, left, right)
    }

    private fun nativeKey(): String {
        val l = if (swap) rightRaw else leftRaw
        val r = if (swap) leftRaw else rightRaw
        return "${l?.contentHashCode() ?: 0}:${r?.contentHashCode() ?: 0}"
    }

    private fun payload(raw: ByteArray?): ByteArray? =
        if (raw != null && raw.size > 1) raw.copyOfRange(1, raw.size) else null

    private fun stopOutput(target: AdaptiveTriggerDevice) {
        when (target.type) {
            AdaptiveTriggerTargetType.NONE -> Unit
            AdaptiveTriggerTargetType.PHONE_MOTOR -> phoneVibration(0, 0)
            AdaptiveTriggerTargetType.CONTROLLER_MOTOR ->
                controllerHandler.setControllerMotorsVibration(target.controllerIndex, 0, 0)
            AdaptiveTriggerTargetType.CONTROLLER_TRIGGER -> {
                controllerHandler.setAdaptiveTriggerEffects(target.controllerIndex, 0x0F, 0, 0, null, null)
                controllerHandler.setTriggerRumble(target.controllerIndex, 0, 0)
                controllerHandler.setControllerMotorsVibration(target.controllerIndex, 0, 0)
            }
        }
        lastOutLeft = -1
        lastOutRight = -1
        lastNativeKey = null
    }
}
