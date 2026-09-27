package com.zyz4.gkme.controlled

import android.content.Context
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * 运行在 Shizuku UserService 进程中的虚拟手柄服务。
 *
 * 该进程以 shell(uid 2000) 或 root 身份运行，因此可以打开 /dev/uinput。
 * Shizuku v13 会优先使用带 [Context] 参数的构造器。
 */
class RemoteGamepadService @JvmOverloads constructor(
    @Suppress("unused") private val context: Context? = null,
) : IGamepadService.Stub() {

    companion object {
        private const val TAG = "GKME_RemoteGamepad"
    }

    private val fd = AtomicInteger(-1)
    private val lock = Any()

    @Volatile
    private var lastError: String? = null

    override fun create(rumbleEnabled: Boolean): Int {
        if (!RemoteGamepadDevice.isLoaded()) {
            lastError = "native 库加载失败: ${RemoteGamepadDevice.loadError()}"
            Log.e(TAG, lastError!!)
            return -19 // -ENODEV
        }
        synchronized(lock) {
            if (fd.get() >= 0) return 0
            val f = RemoteGamepadDevice.nativeCreate(if (rumbleEnabled) 1 else 0)
            if (f < 0) {
                lastError = "无法打开 /dev/uinput (errno=${-f})"
                Log.e(TAG, lastError!!)
                return f
            }
            fd.set(f)
            Log.i(TAG, "虚拟手柄已创建 fd=$f")
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
    ) {
        val f = fd.get()
        if (f < 0 || !RemoteGamepadDevice.isLoaded()) return
        try {
            RemoteGamepadDevice.nativeWrite(
                f, buttons, leftTrigger, rightTrigger, leftX, leftY, rightX, rightY,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "nativeWrite 失败", t)
            lastError = t.message
        }
    }

    override fun pumpRumble(): Long {
        val f = fd.get()
        if (f < 0 || !RemoteGamepadDevice.isLoaded()) return 0L
        return try {
            RemoteGamepadDevice.nativeRumble(f)
        } catch (_: Throwable) {
            0L
        }
    }

    override fun release() {
        synchronized(lock) {
            val f = fd.getAndSet(-1)
            if (f >= 0 && RemoteGamepadDevice.isLoaded()) {
                try {
                    RemoteGamepadDevice.nativeDestroy(f)
                } catch (t: Throwable) {
                    Log.e(TAG, "nativeDestroy 失败", t)
                }
                Log.i(TAG, "虚拟手柄已销毁 fd=$f")
            }
        }
    }

    override fun exitService() {
        release()
        // Shizuku 约定：用户服务进程不会被自动杀死，需要自行退出。
        Process.killProcess(Process.myPid())
    }
}
