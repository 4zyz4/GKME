package com.zyz4.gkme.input

import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.zyz4.gkme.BuildConfig
import com.zyz4.gkme.model.PhysicalInputs
import com.zyz4.gkme.proto.GamepadInput

/** An opt-in, bounded capture of Joy-Con input and the phone's outgoing gamepad frames. */
internal object JoyConDiagnostics {
    @Volatile
    var recording = false
        private set

    var report: String? = null
        private set

    private data class Entry(val order: Long, val elapsedMs: Long, val text: String)
    private data class Output(
        val route: String, val buttons: Int, val dpad: Int,
        val lx: Int, val ly: Int, val rx: Int, val ry: Int, val lt: Int, val rt: Int,
    )

    private val keysAndDevices = ArrayDeque<Entry>()
    private val motionAndOutput = ArrayDeque<Entry>()
    private val lastMotionMs = mutableMapOf<Int, Long>()
    private var lastOutput: Output? = null
    private var nextOrder = 0L
    private var startedMs = 0L
    private var dropped = 0
    private var sampledOut = 0

    @Synchronized
    fun start(connection: String) {
        keysAndDevices.clear()
        motionAndOutput.clear()
        lastMotionMs.clear()
        lastOutput = null
        nextOrder = 0
        dropped = 0
        sampledOut = 0
        report = null
        startedMs = SystemClock.uptimeMillis()
        recording = true
        note("START app=${BuildConfig.VERSION_NAME} phone=${Build.MANUFACTURER} ${Build.MODEL} " +
            "Android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} $connection")
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            if (isJoyCon(device)) note("DEVICE ${deviceInfo(device)}")
        }
    }

    @Synchronized
    fun stop(connection: String) {
        if (!recording) return
        note("STOP $connection")
        recording = false
        report = buildString {
            appendLine("GKME Joy-Con mapping diagnostics v2")
            appendLine("KEY and MOTION = Android app events; MAP = Joy-Con backend; TX = phone transport submission.")
            appendLine("KEY stages pre_ime and activity can show the same unhandled event. Match by t, id, action and scan.")
            appendLine("TX records changed gamepad frames only. It does not measure reception on the PC.")
            appendLine("discarded-old-rows=$dropped sampled-motion-rows=$sampledOut")
            for (entry in (keysAndDevices + motionAndOutput).sortedBy { it.order }) {
                appendLine("+${entry.elapsedMs}ms ${entry.text}")
            }
        }
        keysAndDevices.clear()
        motionAndOutput.clear()
        lastMotionMs.clear()
        lastOutput = null
    }

    fun key(event: KeyEvent, stage: String) {
        if (!recording || !isJoyCon(event.device) || event.repeatCount != 0) return
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            else -> "action=${event.action}"
        }
        val code = KeyEvent.keyCodeToString(event.keyCode)
        note("KEY t=${event.eventTime} id=${event.deviceId} $action $code scan=${event.scanCode} " +
            "source=0x${event.source.toString(16)} stage=$stage flags=0x${event.flags.toString(16)}")
    }

    fun mappedKey(event: KeyEvent, side: JoyConSide, bit: Int, state: PhysicalControllerState?) {
        if (!recording || event.repeatCount != 0) return
        val result = if (state == null) "ignored" else "target=${buttons(bit)} ${physicalState(state)}"
        note("MAP key t=${event.eventTime} id=${event.deviceId} side=$side $result")
    }

    @Synchronized
    fun motion(event: MotionEvent, mapped: () -> String) {
        if (!recording) return
        val device = event.device ?: return
        if (!isJoyCon(device)) return
        val previous = lastMotionMs[event.deviceId]
        if (previous != null && event.eventTime in previous until previous + 40) {
            sampledOut++
            return
        }
        lastMotionMs[event.deviceId] = event.eventTime
        val axes = device.motionRanges.distinctBy { it.axis }.joinToString(" ") {
            "${MotionEvent.axisToString(it.axis)}=${event.getAxisValue(it.axis)}"
        }.ifEmpty { "(no advertised axes)" }
        append(motionAndOutput, 3072, "MOTION t=${event.eventTime} id=${event.deviceId} " +
            "action=${MotionEvent.actionToString(event.action)} source=0x${event.source.toString(16)} $axes ${mapped()}")
    }

    @Synchronized
    fun outbound(route: String, input: GamepadInput) {
        if (!recording) return
        val output = Output(route, input.buttons, input.dpad,
            input.leftStickX, input.leftStickY, input.rightStickX, input.rightStickY,
            input.leftTrigger, input.rightTrigger)
        if (output == lastOutput) return
        lastOutput = output
        append(motionAndOutput, 3072, "TX route=$route buttons=${buttons(output.buttons)} dpad=${output.dpad} " +
            "LX=${output.lx} LY=${output.ly} RX=${output.rx} RY=${output.ry} LT=${output.lt} RT=${output.rt}")
    }

    @Synchronized
    fun note(message: String) {
        if (recording) append(keysAndDevices, 512, message)
    }

    private fun append(entries: ArrayDeque<Entry>, capacity: Int, message: String) {
        if (entries.size == capacity) {
            entries.removeFirst()
            dropped++
        }
        entries.addLast(Entry(nextOrder++, SystemClock.uptimeMillis() - startedMs, message))
    }

    fun physicalState(state: PhysicalControllerState): String =
        "buttons=${buttons(state.buttons.toInt())} dpad=${state.dpad} LX=${state.leftStickX} " +
            "LY=${state.leftStickY} RX=${state.rightStickX} RY=${state.rightStickY} " +
            "LT=${state.leftTrigger} RT=${state.rightTrigger}"

    private fun buttons(bits: Int): String {
        val names = PhysicalInputs.BUTTONS.filter { bits and it.bitMask != 0 }.joinToString(",") { it.key }
        return "0x${bits.toUInt().toString(16)}[${names.ifEmpty { "-" }}]"
    }

    private fun isJoyCon(device: InputDevice?): Boolean = device != null &&
        ((device.vendorId == 0x057e && device.productId in 0x2006..0x2007) ||
            device.name.contains("Joy-Con (L)", ignoreCase = true) ||
            device.name.contains("Joy-Con (R)", ignoreCase = true))

    private fun deviceInfo(device: InputDevice): String {
        val axes = device.motionRanges.joinToString(" ") {
            "${MotionEvent.axisToString(it.axis)}@${it.source.toString(16)}=[${it.min},${it.max}] flat=${it.flat}"
        }.ifEmpty { "none" }
        val reportedKeys = device.hasKeys(*GAMEPAD_KEYS)
        val keys = GAMEPAD_KEYS.filterIndexed { index, _ -> reportedKeys.getOrElse(index) { false } }
            .joinToString(",") { KeyEvent.keyCodeToString(it) }.ifEmpty { "none" }
        return "id=${device.id} vid=${device.vendorId.toString(16)} pid=${device.productId.toString(16)} " +
            "sources=0x${device.sources.toString(16)} advertised-keys=$keys axes=$axes"
    }

    private val GAMEPAD_KEYS =
        ((KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_MODE) +
            (KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16) +
            (KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER) +
            listOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MEDIA_RECORD)).toIntArray()
}
