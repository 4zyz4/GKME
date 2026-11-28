package com.zyz4.gkme.controlled

import android.content.Context
import android.os.Process
import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 运行在 Shizuku UserService 进程中的虚拟手柄服务。
 *
 * 该进程以 shell(uid 2000) 或 root 身份运行，因此可以打开 /dev/uinput 与 /dev/uhid。
 * Shizuku v13 会优先使用带 [Context] 参数的构造器。
 *
 * 支持两种后端：
 *  - [BACKEND_UINPUT]：伪装成 Xbox One S 的 Linux input 设备（带内核 FF 震动）。
 *  - [BACKEND_UHID]：用真实 HID 报告描述符创建 DS4 / DualSense / Switch Pro。
 */
class RemoteGamepadService @JvmOverloads constructor(
    @Suppress("unused") private val context: Context? = null,
) : IGamepadService.Stub() {

    companion object {
        private const val TAG = "GKME_RemoteGamepad"

        const val BACKEND_UINPUT = 0
        const val BACKEND_UHID = 1

        /** 每次 native 读取最多编码的事件数（与 native 的 EVDEV_MAX_EVENTS 一致）。 */
        private const val EVDEV_BATCH_EVENTS = 64

        /** poll 超时：有事件时立即返回，空闲时每 100ms 唤醒一次以便停止线程。 */
        private const val EVDEV_POLL_TIMEOUT_MS = 100
    }

    private val fd = AtomicInteger(-1)
    private val kbdFd = AtomicInteger(-1)
    private val mouseFd = AtomicInteger(-1)
    private val touchpadFd = AtomicInteger(-1)
    private val lock = Any()

    /** 后台 evdev 读取线程；每个节点一个线程。 */
    private val evdevLock = Any()
    private val evdevReaders = ArrayList<EvdevReader>()

    @Volatile
    private var backend = BACKEND_UINPUT

    @Volatile
    private var lastError: String? = null

    override fun create(backend: Int, profile: Int, rumbleEnabled: Boolean): Int {
        if (!RemoteGamepadDevice.isLoaded()) {
            lastError = "native 库加载失败: ${RemoteGamepadDevice.loadError()}"
            Log.e(TAG, lastError!!)
            return -19 // -ENODEV
        }
        synchronized(lock) {
            if (fd.get() >= 0) return 0
            this.backend = backend
            val f = if (backend == BACKEND_UHID) {
                RemoteGamepadDevice.nativeCreateUhid(profile, if (rumbleEnabled) 1 else 0)
            } else {
                RemoteGamepadDevice.nativeCreate(if (rumbleEnabled) 1 else 0)
            }
            if (f < 0) {
                lastError = if (backend == BACKEND_UHID) {
                    "无法通过 /dev/uhid 创建虚拟手柄 (errno=${-f})"
                } else {
                    "无法打开 /dev/uinput (errno=${-f})"
                }
                Log.e(TAG, lastError!!)
                return f
            }
            fd.set(f)
            Log.i(TAG, "虚拟手柄已创建 backend=$backend profile=$profile fd=$f")
            return 0
        }
    }

    override fun update(
        buttons: Int,
        leftTrigger: Int,
        rightTrigger: Int,
        leftX: Int,
        leftY: Int,
        rightX: Int,
        rightY: Int,
        gyroX: Float,
        gyroY: Float,
        gyroZ: Float,
        accelX: Float,
        accelY: Float,
        accelZ: Float,
        touches: IntArray?,
    ) {
        val f = fd.get()
        if (f < 0 || !RemoteGamepadDevice.isLoaded()) return
        try {
            if (backend == BACKEND_UHID) {
                RemoteGamepadDevice.nativeWriteUhid(
                    f, buttons, leftTrigger, rightTrigger, leftX, leftY, rightX, rightY,
                    gyroX, gyroY, gyroZ, accelX, accelY, accelZ, touches,
                )
            } else {
                RemoteGamepadDevice.nativeWrite(
                    f, buttons, leftTrigger, rightTrigger, leftX, leftY, rightX, rightY,
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "nativeWrite 失败", t)
            lastError = t.message
        }
    }

    override fun pumpRumble(): Long {
        val f = fd.get()
        if (f < 0 || !RemoteGamepadDevice.isLoaded()) return 0L
        return try {
            if (backend == BACKEND_UHID) {
                RemoteGamepadDevice.nativeRumbleUhid(f)
            } else {
                RemoteGamepadDevice.nativeRumble(f)
            }
        } catch (_: Throwable) {
            0L
        }
    }

    override fun release() {
        releaseKeyboardMouse()
        releasePrecisionTouchpad()
        synchronized(lock) {
            val f = fd.getAndSet(-1)
            if (f >= 0 && RemoteGamepadDevice.isLoaded()) {
                try {
                    if (backend == BACKEND_UHID) {
                        RemoteGamepadDevice.nativeDestroyUhid(f)
                    } else {
                        RemoteGamepadDevice.nativeDestroy(f)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "nativeDestroy 失败", t)
                }
                Log.i(TAG, "虚拟手柄已销毁 fd=$f")
            }
        }
    }

    override fun createKeyboard(): Int {
        if (!RemoteGamepadDevice.isLoaded()) {
            lastError = "native 库加载失败: ${RemoteGamepadDevice.loadError()}"
            Log.e(TAG, lastError!!)
            return -19 // -ENODEV
        }
        synchronized(lock) {
            if (kbdFd.get() >= 0) return 0
            val f = if (backend == BACKEND_UHID) {
                RemoteGamepadDevice.nativeCreateKeyboardUhid()
            } else {
                RemoteGamepadDevice.nativeCreateKeyboard()
            }
            if (f < 0) {
                lastError = "无法创建虚拟键盘 (errno=${-f})"
                Log.e(TAG, lastError!!)
                return f
            }
            kbdFd.set(f)
            Log.i(TAG, "虚拟键盘已创建 fd=$f")
            return 0
        }
    }

    override fun updateKeyboard(modifiers: Int, usages: IntArray?) {
        val f = kbdFd.get()
        if (f < 0 || !RemoteGamepadDevice.isLoaded()) return
        try {
            if (backend == BACKEND_UHID) {
                RemoteGamepadDevice.nativeWriteKeyboardUhid(f, modifiers, usages ?: IntArray(0))
            } else {
                RemoteGamepadDevice.nativeWriteKeyboard(f, modifiers, usages ?: IntArray(0))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "nativeWriteKeyboard 失败", t)
            lastError = t.message
        }
    }

    override fun createMouse(): Int {
        if (!RemoteGamepadDevice.isLoaded()) {
            lastError = "native 库加载失败: ${RemoteGamepadDevice.loadError()}"
            Log.e(TAG, lastError!!)
            return -19 // -ENODEV
        }
        synchronized(lock) {
            if (mouseFd.get() >= 0) return 0
            val f = if (backend == BACKEND_UHID) {
                RemoteGamepadDevice.nativeCreateMouseUhid()
            } else {
                RemoteGamepadDevice.nativeCreateMouse()
            }
            if (f < 0) {
                lastError = "无法创建虚拟鼠标 (errno=${-f})"
                Log.e(TAG, lastError!!)
                return f
            }
            mouseFd.set(f)
            Log.i(TAG, "虚拟鼠标已创建 fd=$f")
            return 0
        }
    }

    override fun updateMouse(dx: Int, dy: Int, wheel: Int, pan: Int, buttons: Int) {
        val f = mouseFd.get()
        if (f < 0 || !RemoteGamepadDevice.isLoaded()) return
        try {
            if (backend == BACKEND_UHID) {
                RemoteGamepadDevice.nativeWriteMouseUhid(f, dx, dy, wheel, pan, buttons)
            } else {
                RemoteGamepadDevice.nativeWriteMouse(f, dx, dy, wheel, pan, buttons)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "nativeWriteMouse 失败", t)
            lastError = t.message
        }
    }

    override fun createPrecisionTouchpad(): Int {
        if (!RemoteGamepadDevice.isLoaded()) {
            lastError = "native 库加载失败: ${RemoteGamepadDevice.loadError()}"
            Log.e(TAG, lastError!!)
            return -19 // -ENODEV
        }
        synchronized(lock) {
            if (touchpadFd.get() >= 0) return 0
            val f = RemoteGamepadDevice.nativeCreateTouchpadUhid()
            if (f < 0) {
                lastError = "无法通过 /dev/uhid 创建精确触摸板 (errno=${-f})"
                Log.e(TAG, lastError!!)
                return f
            }
            touchpadFd.set(f)
            Log.i(TAG, "精确触摸板已创建 fd=$f")
            return 0
        }
    }

    override fun updatePrecisionTouchpad(buttons: Int, contacts: IntArray?) {
        val f = touchpadFd.get()
        if (f < 0 || !RemoteGamepadDevice.isLoaded()) return
        try {
            RemoteGamepadDevice.nativeWriteTouchpadUhid(f, buttons, contacts ?: IntArray(0))
        } catch (t: Throwable) {
            Log.e(TAG, "nativeWriteTouchpad 失败", t)
            lastError = t.message
        }
    }

    override fun releasePrecisionTouchpad() {
        synchronized(lock) {
            val f = touchpadFd.getAndSet(-1)
            if (f >= 0 && RemoteGamepadDevice.isLoaded()) {
                try {
                    RemoteGamepadDevice.nativeDestroyUhid(f)
                } catch (t: Throwable) {
                    Log.e(TAG, "销毁精确触摸板失败", t)
                }
                Log.i(TAG, "精确触摸板已销毁 fd=$f")
            }
        }
    }

    override fun releaseKeyboardMouse() {
        synchronized(lock) {
            val k = kbdFd.getAndSet(-1)
            if (k >= 0 && RemoteGamepadDevice.isLoaded()) {
                try {
                    if (backend == BACKEND_UHID) {
                        RemoteGamepadDevice.nativeDestroyUhidInput(k)
                    } else {
                        RemoteGamepadDevice.nativeDestroyInput(k)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "销毁键盘失败", t)
                }
                Log.i(TAG, "虚拟键盘已销毁 fd=$k")
            }
            val m = mouseFd.getAndSet(-1)
            if (m >= 0 && RemoteGamepadDevice.isLoaded()) {
                try {
                    if (backend == BACKEND_UHID) {
                        RemoteGamepadDevice.nativeDestroyUhidInput(m)
                    } else {
                        RemoteGamepadDevice.nativeDestroyInput(m)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "销毁鼠标失败", t)
                }
                Log.i(TAG, "虚拟鼠标已销毁 fd=$m")
            }
        }
    }

    /**
     * 以 shell 身份执行 `cmd settings get system <key>`。
     *
     * 用户服务以 shell/root 运行，可以读取 App 进程无权访问的私有设置；
     * 这里只用于本机模式判断系统是否会把手机振动重定向到输入设备（形成回环）。
     */
    override fun readSystemSetting(key: String): String? {
        // 只接受普通设置键名，避免把调用方传入的任意内容拼进命令行。
        if (!key.matches(Regex("[A-Za-z0-9_.]+"))) return null
        return try {
            val process = Runtime.getRuntime()
                .exec(arrayOf("/system/bin/cmd", "settings", "get", "system", key))
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy()
                return null
            }
            val value = process.inputStream.bufferedReader().use { it.readText() }.trim()
            value.takeIf { it.isNotEmpty() && it != "null" }
        } catch (t: Throwable) {
            Log.w(TAG, "读取系统设置失败: $key", t)
            null
        }
    }

    // ── evdev 后台实体手柄采集 ─────────────────────────────

    override fun listEvdevDevices(): Array<String>? {
        if (!RemoteGamepadDevice.isLoaded()) {
            lastError = "native 库加载失败: ${RemoteGamepadDevice.loadError()}"
            return null
        }
        return try {
            RemoteGamepadDevice.nativeEvdevList()
        } catch (t: Throwable) {
            Log.e(TAG, "nativeEvdevList 失败", t)
            lastError = t.message
            null
        }
    }

    override fun startEvdevCapture(listener: IEvdevListener?, paths: Array<String>?): Int {
        if (!RemoteGamepadDevice.isLoaded()) return -19 // -ENODEV
        if (listener == null || paths == null || paths.isEmpty()) return -22 // -EINVAL
        synchronized(evdevLock) {
            stopEvdevCaptureLocked()
            var started = 0
            var lastError = 0
            for (path in paths) {
                // 只接受 /dev/input/eventN，避免调用方传入任意路径。
                if (!path.matches(Regex("/dev/input/event\\d+"))) {
                    lastError = -22
                    continue
                }
                val deviceFd = try {
                    RemoteGamepadDevice.nativeEvdevOpen(path)
                } catch (t: Throwable) {
                    Log.e(TAG, "nativeEvdevOpen 失败: $path", t)
                    -1
                }
                if (deviceFd < 0) {
                    lastError = deviceFd
                    Log.w(TAG, "无法打开 evdev 节点 $path (errno=${-deviceFd})")
                    continue
                }
                val reader = EvdevReader(path, deviceFd, listener)
                evdevReaders.add(reader)
                reader.start()
                started++
            }
            if (started > 0) {
                Log.i(TAG, "evdev 采集已启动: ${paths.joinToString()}")
                return 0
            }
            return if (lastError != 0) lastError else -19
        }
    }

    override fun stopEvdevCapture() {
        synchronized(evdevLock) {
            if (evdevReaders.isEmpty()) return
            stopEvdevCaptureLocked()
            Log.i(TAG, "evdev 采集已停止")
        }
    }

    private fun stopEvdevCaptureLocked() {
        if (evdevReaders.isEmpty()) return
        for (reader in evdevReaders) reader.stopReading()
        for (reader in evdevReaders) {
            try {
                reader.join(300)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        evdevReaders.clear()
    }

    /** 单个节点的读取线程：poll 等待事件，批量回传给 App。 */
    private class EvdevReader(
        private val path: String,
        private val deviceFd: Int,
        private val listener: IEvdevListener,
    ) : Thread("GKME-Evdev") {

        @Volatile
        private var running = true

        private val buffer = ByteArray(8 * EVDEV_BATCH_EVENTS)

        override fun run() {
            while (running) {
                val read = try {
                    RemoteGamepadDevice.nativeEvdevRead(deviceFd, buffer, EVDEV_POLL_TIMEOUT_MS)
                } catch (t: Throwable) {
                    Log.e(TAG, "nativeEvdevRead 失败: $path", t)
                    -1
                }
                when {
                    read > 0 -> {
                        val data = buffer.copyOf(read)
                        try {
                            listener.onEvents(path, data)
                        } catch (_: Throwable) {
                            // App 进程已退出或 Binder 断开：结束本线程。
                            running = false
                        }
                    }
                    read < 0 -> {
                        try {
                            listener.onCaptureError(path, read)
                        } catch (_: Throwable) {
                        }
                        break
                    }
                }
            }
            RemoteGamepadDevice.nativeEvdevClose(deviceFd)
        }

        fun stopReading() {
            running = false
        }
    }

    override fun exitService() {
        stopEvdevCapture()
        release()
        // Shizuku 约定：用户服务进程不会被自动杀死，需要自行退出。
        Process.killProcess(Process.myPid())
    }
}
