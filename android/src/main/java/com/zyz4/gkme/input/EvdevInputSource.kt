package com.zyz4.gkme.input

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import com.zyz4.gkme.controlled.GamepadInjector
import com.zyz4.gkme.controlled.IEvdevListener
import com.zyz4.gkme.model.GamepadState
import com.zyz4.gkme.model.TouchPoint
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 后台实体手柄输入源：通过 Shizuku 用户服务读取 `/dev/input/event*`。
 *
 * 悬浮模式执行 `moveTaskToBack(true)` 后，Activity 失去窗口焦点，Android 不再把
 * key/motion 事件派发给 GKME，因此 SDL3 / InputManager / DualSense 蓝牙后端都会
 * 收不到实体手柄输入。本类改为在 shell（uid 2000，input 组）进程里直接读 evdev：
 *
 *  - evdev 支持多读者，系统 InputFlinger 仍然把同一份事件投递给前台游戏；
 *  - 读取与窗口焦点无关，悬浮/切后台时仍然有效；
 *  - 只接管输入状态，震动/LED/自适应扳机等输出仍由当前 backend 负责。
 *
 * 事件在 Binder 线程解码后，合并到主线程再回调，避免高频事件直接压到 UI 线程。
 */
class EvdevInputSource(
    private val target: InputDevice,
    private val onState: (PhysicalControllerState) -> Unit,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val publishLock = Any()
    private val decoderLock = Any()

    @Volatile
    private var pendingState: PhysicalControllerState? = null

    private var decoder: Decoder? = null

    @Volatile
    private var started = false

    /** Debug-only: last published button/dpad mask, used to log low-volume transitions. */
    private var lastLoggedButtons: UInt = 0u
    private var lastLoggedDpad = 0

    private val targetName: String = target.name.orEmpty()

    private val publishRunnable = Runnable {
        val state = synchronized(publishLock) {
            pendingState.also { pendingState = null }
        } ?: return@Runnable
        onState(state)
    }

    /** True when this source still targets [device]. */
    fun matches(device: InputDevice?): Boolean = device != null && device.id == target.id

    /** 打开并开始读取目标手柄对应的 evdev 节点。 */
    fun start(): Boolean {
        if (started) return true

        val descriptors = GamepadInjector.listEvdevDevices() ?: run {
            Log.w(TAG, "无法枚举 evdev 设备（Shizuku 用户服务未就绪）")
            return false
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "evdev descriptors(${descriptors.size}): ${descriptors.joinToString(" || ")}")
        }
        val devices = descriptors.mapNotNull { EvdevDevice.parse(it) }
        val gamepad = selectGamepad(devices) ?: run {
            Log.w(TAG, "未找到 \"$targetName\" (${target.vendorId}:${target.productId}) 的 evdev 节点")
            return false
        }
        val touchpad = selectTouchpad(devices, gamepad)
        val newDecoder = Decoder(gamepad, touchpad)

        val paths = buildList {
            add(gamepad.path)
            touchpad?.let { add(it.path) }
        }.toTypedArray()

        synchronized(decoderLock) { decoder = newDecoder }
        val result = GamepadInjector.startEvdevCapture(Listener(), paths)
        if (result != 0) {
            Log.w(TAG, "startEvdevCapture 失败: $result")
            synchronized(decoderLock) { decoder = null }
            return false
        }

        Log.i(TAG, "evdev 后台输入已启动: ${paths.joinToString()} (${gamepad.name})")
        started = true
        return true
    }

    /** 停止读取并释放按下的按键，避免切换来源后留下卡键。 */
    fun stop() {
        if (!started) return
        started = false
        GamepadInjector.stopEvdevCapture()
        synchronized(decoderLock) { decoder = null }
        mainHandler.removeCallbacks(publishRunnable)
        publish(PhysicalControllerState())
    }

    private fun publish(state: PhysicalControllerState) {
        logState(state)
        synchronized(publishLock) { pendingState = state }
        mainHandler.removeCallbacks(publishRunnable)
        mainHandler.post(publishRunnable)
    }

    private fun logState(state: PhysicalControllerState) {
        if (state.buttons != lastLoggedButtons || state.dpad != lastLoggedDpad) {
            lastLoggedButtons = state.buttons
            lastLoggedDpad = state.dpad
            Log.i(
                TAG,
                "buttons=0x${state.buttons.toString(16)} dpad=${state.dpad} " +
                    "lt=${state.leftTrigger} rt=${state.rightTrigger} " +
                    "lx=${state.leftStickX} ly=${state.leftStickY} " +
                    "rx=${state.rightStickX} ry=${state.rightStickY} " +
                    "touch=${state.touchpadTouch} click=${state.touchpadClick}",
            )
        }
    }

    private inner class Listener : IEvdevListener.Stub() {
        override fun onEvents(path: String, data: ByteArray) {
            if (!started) return
            val state = synchronized(decoderLock) { decoder?.handle(path, data) } ?: return
            publish(state)
        }

        override fun onCaptureError(path: String, errno: Int) {
            if (!started) return
            Log.w(TAG, "evdev 读取结束: $path errno=$errno")
            val state = synchronized(decoderLock) { decoder?.neutral() } ?: PhysicalControllerState()
            publish(state)
        }
    }

    private fun selectGamepad(devices: List<EvdevDevice>): EvdevDevice? {
        val candidates = devices.filter { it.isGamepad && !it.isGkmeVirtual }
        candidates.firstOrNull {
            it.vendor == target.vendorId && it.product == target.productId && it.name == targetName
        }?.let { return it }
        candidates.firstOrNull {
            it.vendor == target.vendorId && it.product == target.productId
        }?.let { return it }
        if (targetName.isNotBlank()) {
            candidates.firstOrNull {
                it.vendor == target.vendorId &&
                    (it.name.contains(targetName) || targetName.contains(it.name))
            }?.let { return it }
        }
        return null
    }

    private fun selectTouchpad(devices: List<EvdevDevice>, gamepad: EvdevDevice): EvdevDevice? {
        val candidates = devices.filter { it.isTouchpad && !it.isGkmeVirtual }
        if (gamepad.uniq.isNotEmpty()) {
            candidates.firstOrNull { it.uniq == gamepad.uniq }?.let { return it }
        }
        val prefix = gamepad.name.substringBefore(" Touchpad").trim()
        return candidates.firstOrNull {
            it.vendor == gamepad.vendor && it.product == gamepad.product &&
                (prefix.isEmpty() || it.name.startsWith(prefix))
        }
    }

    private data class EvdevDevice(
        val path: String,
        val name: String,
        val phys: String,
        val uniq: String,
        val bus: Int,
        val vendor: Int,
        val product: Int,
        val isGamepad: Boolean,
        val isTouchpad: Boolean,
        val isMotion: Boolean,
        val abs: Map<Int, IntRange>,
    ) {
        /**
         * GKME 自建的虚拟手柄：
         *  - uhid 的 DualSense/DS4/Switch Pro 使用 "GKME-DS5" 之类的 uniq；
         *  - uinput 的 Xbox 使用 "Xbox One S Controller"，且没有 uniq/phys。
         * 不能只按 vid/pid 过滤：真实 DualSense 也是 054c:0ce6。
         */
        val isGkmeVirtual: Boolean
            get() = uniq.startsWith("GKME-", ignoreCase = true) ||
                name.startsWith("GKME ", ignoreCase = true) ||
                (VirtualGamepad.virtualGamepadActive &&
                    name == "Xbox One S Controller" && uniq.isEmpty() && phys.isEmpty())

        companion object {
            fun parse(line: String): EvdevDevice? {
                val parts = line.split('|', limit = 9)
                if (parts.size < 9) return null
                val path = parts[0]
                if (!path.startsWith("/dev/input/event")) return null

                val abs = HashMap<Int, IntRange>()
                if (parts[8].isNotEmpty()) {
                    for (spec in parts[8].split(',')) {
                        val fields = spec.split(':')
                        if (fields.size != 3) continue
                        val code = fields[0].toIntOrNull() ?: continue
                        val min = fields[1].toIntOrNull() ?: continue
                        val max = fields[2].toIntOrNull() ?: continue
                        abs[code] = min..max
                    }
                }

                val flags = parts[7].toIntOrNull() ?: 0
                return EvdevDevice(
                    path = path,
                    name = parts[1],
                    phys = parts[2],
                    uniq = parts[3],
                    bus = parts[4].toIntOrNull() ?: 0,
                    vendor = parts[5].toIntOrNull() ?: -1,
                    product = parts[6].toIntOrNull() ?: -1,
                    isGamepad = flags and 1 != 0,
                    isTouchpad = flags and 2 != 0,
                    isMotion = flags and 4 != 0,
                    abs = abs,
                )
            }
        }
    }

    /**
     * 把一个节点的原始事件解码为 [PhysicalControllerState]。
     *
     * 非线程安全；由 [decoderLock] 串行化。
     */
    private class Decoder(
        private val gamepad: EvdevDevice,
        private val touchpad: EvdevDevice?,
    ) {
        private var keyBits = 0
        private var hatBits = 0
        private var leftX = 0
        private var leftY = 0
        private var rightX = 0
        private var rightY = 0
        private var leftTrigger = 0
        private var rightTrigger = 0

        private var touchActive = false
        private var click = false
        private var currentSlot = 0
        private val slotIds = IntArray(2) { -1 }
        private val slotX = IntArray(2)
        private val slotY = IntArray(2)

        fun handle(path: String, data: ByteArray): PhysicalControllerState {
            when {
                path == gamepad.path -> handleGamepad(data)
                touchpad != null && path == touchpad.path -> handleTouchpad(data)
            }
            return state()
        }

        /** 设备移除或读取线程结束：输出一帧全松开状态。 */
        fun neutral(): PhysicalControllerState {
            reset()
            return state()
        }

        private fun handleGamepad(data: ByteArray) {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            while (buffer.remaining() >= EVENT_SIZE) {
                val type = buffer.short.toInt() and 0xFFFF
                val code = buffer.short.toInt() and 0xFFFF
                val value = buffer.int
                when (type) {
                    EV_KEY -> handleKey(code, value)
                    EV_ABS -> handleGamepadAbs(code, value)
                    EV_SYN -> if (code == SYN_DROPPED) reset()
                }
            }
        }

        private fun handleKey(code: Int, value: Int) {
            val bit = keyBit(code)
            if (bit == 0) return
            keyBits = if (value == 0) keyBits and bit.inv() else keyBits or bit
        }

        private fun handleGamepadAbs(code: Int, value: Int) {
            when (code) {
                ABS_X -> leftX = stick(value, gamepad.abs[code])
                ABS_Y -> leftY = stick(value, gamepad.abs[code])
                ABS_RX -> rightX = stick(value, gamepad.abs[code])
                ABS_RY -> rightY = stick(value, gamepad.abs[code])
                ABS_Z, ABS_GAS -> leftTrigger = trigger(value, gamepad.abs[code])
                ABS_RZ, ABS_BRAKE -> rightTrigger = trigger(value, gamepad.abs[code])
                ABS_HAT0X -> hatBits = when {
                    value < 0 -> hatBits or GamepadState.DPAD_BIT_LEFT
                    value > 0 -> hatBits or GamepadState.DPAD_BIT_RIGHT
                    else -> hatBits and (GamepadState.DPAD_BIT_LEFT or GamepadState.DPAD_BIT_RIGHT).inv()
                }
                ABS_HAT0Y -> hatBits = when {
                    value < 0 -> hatBits or GamepadState.DPAD_BIT_UP
                    value > 0 -> hatBits or GamepadState.DPAD_BIT_DOWN
                    else -> hatBits and (GamepadState.DPAD_BIT_UP or GamepadState.DPAD_BIT_DOWN).inv()
                }
            }
        }

        private fun handleTouchpad(data: ByteArray) {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            while (buffer.remaining() >= EVENT_SIZE) {
                val type = buffer.short.toInt() and 0xFFFF
                val code = buffer.short.toInt() and 0xFFFF
                val value = buffer.int
                when (type) {
                    EV_KEY -> when (code) {
                        BTN_TOUCH, BTN_TOOL_FINGER -> touchActive = value != 0
                        // 触摸板按压在主手柄节点之外，事件里是 BTN_LEFT（BTN_MOUSE）。
                        BTN_LEFT -> click = value != 0
                    }
                    EV_ABS -> {
                        val slot = currentSlot.coerceIn(0, 1)
                        when (code) {
                            ABS_MT_SLOT -> currentSlot = value.coerceIn(0, 1)
                            ABS_MT_TRACKING_ID -> slotIds[slot] = value
                            ABS_MT_POSITION_X -> slotX[slot] = value
                            ABS_MT_POSITION_Y -> slotY[slot] = value
                        }
                    }
                    EV_SYN -> if (code == SYN_DROPPED) resetTouchpad()
                }
            }
        }

        private fun state(): PhysicalControllerState {
            val triggerBits = (if (leftTrigger >= TRIGGER_DIGITAL_THRESHOLD) GamepadState.LT else 0) or
                (if (rightTrigger >= TRIGGER_DIGITAL_THRESHOLD) GamepadState.RT else 0)
            val clickBit = if (click) GamepadState.TOUCHPAD_CLICK else 0
            val buttons = (keyBits or hatBits or triggerBits or clickBit).toUInt()

            val touches = buildTouches()
            val primary = touches.firstOrNull { it.active }

            return PhysicalControllerState(
                buttons = buttons,
                leftStickX = leftX.toShort(),
                leftStickY = leftY.toShort(),
                rightStickX = rightX.toShort(),
                rightStickY = rightY.toShort(),
                leftTrigger = leftTrigger,
                rightTrigger = rightTrigger,
                dpad = hatValue(hatBits),
                touchpadX = if (primary != null) primary.x / TOUCHPAD_MAX_XF else 0f,
                touchpadY = if (primary != null) primary.y / TOUCHPAD_MAX_YF else 0f,
                touchpadTouch = touchActive,
                touchpadClick = click,
                touches = touches,
            )
        }

        private fun buildTouches(): List<TouchPoint> {
            val pad = touchpad ?: return emptyList()
            val xRange = pad.abs[ABS_MT_POSITION_X] ?: pad.abs[ABS_X]
            val yRange = pad.abs[ABS_MT_POSITION_Y] ?: pad.abs[ABS_Y]
            return List(2) { index ->
                if (slotIds[index] >= 0) {
                    TouchPoint(
                        id = index,
                        x = normalize(slotX[index], xRange, TOUCHPAD_MAX_X),
                        y = normalize(slotY[index], yRange, TOUCHPAD_MAX_Y),
                        active = true,
                    )
                } else {
                    TouchPoint(id = index, active = false)
                }
            }
        }

        private fun reset() {
            keyBits = 0
            hatBits = 0
            leftX = 0
            leftY = 0
            rightX = 0
            rightY = 0
            leftTrigger = 0
            rightTrigger = 0
            resetTouchpad()
        }

        private fun resetTouchpad() {
            touchActive = false
            click = false
            currentSlot = 0
            for (i in 0 until 2) {
                slotIds[i] = -1
                slotX[i] = 0
                slotY[i] = 0
            }
        }

        private fun keyBit(code: Int): Int = when (code) {
            BTN_SOUTH -> GamepadState.A
            BTN_EAST -> GamepadState.B
            BTN_WEST -> GamepadState.X
            BTN_NORTH -> GamepadState.Y
            BTN_TL -> GamepadState.LB
            BTN_TR -> GamepadState.RB
            BTN_TL2 -> GamepadState.LT
            BTN_TR2 -> GamepadState.RT
            BTN_SELECT -> GamepadState.SELECT
            BTN_START -> GamepadState.START
            BTN_MODE -> GamepadState.HOME
            BTN_THUMBL -> GamepadState.L3
            BTN_THUMBR -> GamepadState.R3
            BTN_DPAD_UP -> GamepadState.DPAD_BIT_UP
            BTN_DPAD_DOWN -> GamepadState.DPAD_BIT_DOWN
            BTN_DPAD_LEFT -> GamepadState.DPAD_BIT_LEFT
            BTN_DPAD_RIGHT -> GamepadState.DPAD_BIT_RIGHT
            else -> 0
        }

        private fun stick(value: Int, range: IntRange?): Int {
            val min = range?.first ?: 0
            val max = range?.last ?: 255
            if (max <= min) return 0
            val normalized = (value - min).toFloat() / (max - min) * 2f - 1f
            return (normalized.coerceIn(-1f, 1f) * 32767f).toInt()
        }

        private fun trigger(value: Int, range: IntRange?): Int {
            val min = range?.first ?: 0
            val max = range?.last ?: 255
            if (max <= min) return 0
            val normalized = (value - min).toFloat() / (max - min)
            return (normalized.coerceIn(0f, 1f) * 255f).toInt()
        }

        private fun normalize(value: Int, range: IntRange?, outputMax: Int): Int {
            val min = range?.first ?: 0
            val max = range?.last ?: outputMax
            if (max <= min) return 0
            val normalized = (value - min).toFloat() / (max - min)
            return (normalized.coerceIn(0f, 1f) * outputMax).toInt()
        }

        private fun hatValue(bits: Int): Int {
            val up = bits and GamepadState.DPAD_BIT_UP != 0
            val down = bits and GamepadState.DPAD_BIT_DOWN != 0
            val left = bits and GamepadState.DPAD_BIT_LEFT != 0
            val right = bits and GamepadState.DPAD_BIT_RIGHT != 0
            return (if (up) GamepadState.DPAD_UP else 0) or
                (if (down) GamepadState.DPAD_DOWN else 0) or
                (if (left) GamepadState.DPAD_LEFT else 0) or
                (if (right) GamepadState.DPAD_RIGHT else 0)
        }
    }

    companion object {
        private const val TAG = "GKME_Evdev"
        private const val EVENT_SIZE = 8
        private const val TOUCHPAD_MAX_X = 1919
        private const val TOUCHPAD_MAX_Y = 942
        private const val TOUCHPAD_MAX_XF = 1919f
        private const val TOUCHPAD_MAX_YF = 942f
        private const val TRIGGER_DIGITAL_THRESHOLD = 128

        // Linux input-event-codes.h（稳定 ABI）
        private const val EV_SYN = 0x00
        private const val EV_KEY = 0x01
        private const val EV_ABS = 0x03
        private const val SYN_DROPPED = 3

        private const val BTN_LEFT = 0x110
        private const val BTN_SOUTH = 0x130
        private const val BTN_EAST = 0x131
        private const val BTN_NORTH = 0x133
        private const val BTN_WEST = 0x134
        private const val BTN_TL = 0x136
        private const val BTN_TR = 0x137
        private const val BTN_TL2 = 0x138
        private const val BTN_TR2 = 0x139
        private const val BTN_SELECT = 0x13a
        private const val BTN_START = 0x13b
        private const val BTN_MODE = 0x13c
        private const val BTN_THUMBL = 0x13d
        private const val BTN_THUMBR = 0x13e
        private const val BTN_TOOL_FINGER = 0x145
        private const val BTN_TOUCH = 0x14a
        private const val BTN_DPAD_UP = 0x220
        private const val BTN_DPAD_DOWN = 0x221
        private const val BTN_DPAD_LEFT = 0x222
        private const val BTN_DPAD_RIGHT = 0x223

        private const val ABS_X = 0x00
        private const val ABS_Y = 0x01
        private const val ABS_Z = 0x02
        private const val ABS_RX = 0x03
        private const val ABS_RY = 0x04
        private const val ABS_RZ = 0x05
        private const val ABS_GAS = 0x09
        private const val ABS_BRAKE = 0x0a
        private const val ABS_HAT0X = 0x10
        private const val ABS_HAT0Y = 0x11
        private const val ABS_MT_SLOT = 0x2f
        private const val ABS_MT_POSITION_X = 0x35
        private const val ABS_MT_POSITION_Y = 0x36
        private const val ABS_MT_TRACKING_ID = 0x39
    }
}
