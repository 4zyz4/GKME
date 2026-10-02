package com.zyz4.gkme.controlled

import android.content.Context
import android.os.Process
import android.util.Log
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
    }

    private val fd = AtomicInteger(-1)
    private val kbdFd = AtomicInteger(-1)
    private val mouseFd = AtomicInteger(-1)
    private val lock = Any()

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
    ) {
        val f = fd.get()
        if (f < 0 || !RemoteGamepadDevice.isLoaded()) return
        try {
            if (backend == BACKEND_UHID) {
                RemoteGamepadDevice.nativeWriteUhid(
                    f, buttons, leftTrigger, rightTrigger, leftX, leftY, rightX, rightY,
                    gyroX, gyroY, gyroZ, accelX, accelY, accelZ,
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

    override fun exitService() {
        release()
        // Shizuku 约定：用户服务进程不会被自动杀死，需要自行退出。
        Process.killProcess(Process.myPid())
    }
}
