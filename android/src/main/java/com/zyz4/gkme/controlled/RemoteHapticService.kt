package com.zyz4.gkme.controlled

import android.content.Context
import android.os.Process
import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 运行在 Shizuku UserService 进程中的高清震动（RichTap 动态效果）服务。
 *
 * 该进程以 shell(uid 2000)/root 身份运行，不受 hidden API 限制，因此可以反射调用
 * `android.os.HapticPlayer` / `android.os.DynamicEffect`（定义在 `miui-framework.jar`）。
 * 与 [RemoteGamepadService] 一样，Shizuku v13 会优先使用带 [Context] 参数的构造器。
 */
class RemoteHapticService @JvmOverloads constructor(
    @Suppress("unused") private val context: Context? = null,
) : IHapticService.Stub() {

    companion object {
        private const val TAG = "GKME_RemoteHaptic"
        private const val DEFAULT_PACKAGE = "com.android.shell"
    }

    private val lock = Any()

    @Volatile
    private var available = false

    @Volatile
    private var version: String = ""

    @Volatile
    private var packageName: String = DEFAULT_PACKAGE

    @Volatile
    private var player: Any? = null

    // 反射缓存（类不存在时为 null）。
    private var hapticPlayerClass: Class<*>? = null
    private var dynamicEffectClass: Class<*>? = null
    private var startMethod: Method? = null
    private var startNoArgMethod: Method? = null
    private var stopMethod: Method? = null
    private var packageField: Field? = null

    init {
        synchronized(lock) {
            packageName = context?.packageName ?: DEFAULT_PACKAGE
            try {
                val hp = Class.forName("android.os.HapticPlayer")
                hapticPlayerClass = hp
                available = try {
                    hp.getMethod("isAvailable").invoke(null) as? Boolean ?: false
                } catch (t: Throwable) {
                    Log.w(TAG, "isAvailable 调用失败", t)
                    false
                }
                version = try {
                    hp.getMethod("getVersion").invoke(null) as? String ?: ""
                } catch (_: Throwable) {
                    ""
                }
                if (available) {
                    dynamicEffectClass = Class.forName("android.os.DynamicEffect")
                    startMethod = hp.getMethod(
                        "start",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    )
                    stopMethod = hp.getMethod("stop")
                    startNoArgMethod = hp.getMethod("start")
                    packageField = hp.getDeclaredField("mPackageName").apply { isAccessible = true }
                }
                Log.i(TAG, "HapticPlayer available=$available version=$version pkg=$packageName")
            } catch (t: Throwable) {
                available = false
                Log.w(TAG, "隐藏 API 不可用: ${t.message}")
            }
        }
    }

    override fun isAvailable(): Boolean = available

    override fun getVersion(): String = version

    override fun startPattern(
        heJson: String,
        loop: Int,
        interval: Int,
        amplitude: Int,
        freq: Int,
    ): Boolean {
        if (!available) return false
        val de = dynamicEffectClass ?: return false
        val start = startMethod ?: return false
        synchronized(lock) {
            return try {
                // 必须先 stop 旧 effect：实测“不 stop 直接 start”不会替换旧 track，
                // 幅度/频率更新会被忽略（震动卡在首帧）。stop+start 的重投递才是无缝的。
                stopLocked()
                val effect = de.getMethod("create", String::class.java).invoke(null, heJson)
                val hp = hapticPlayerClass ?: return false
                val newPlayer = hp.getConstructor(de).newInstance(effect)
                // app_process 下 mPackageName 为 null，start() 会 NPE，必须补齐。
                packageField?.set(newPlayer, packageName)
                start.invoke(newPlayer, loop, interval, amplitude, freq)
                player = newPlayer
                Log.i(TAG, "startPattern loop=$loop interval=$interval amp=$amplitude freq=$freq")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "startPattern 失败", t)
                false
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            stopLocked()
        }
    }

    override fun startEffect(heJson: String): Boolean {
        if (!available) return false
        val de = dynamicEffectClass ?: return false
        val start = startNoArgMethod ?: return false
        synchronized(lock) {
            return try {
                // 多事件分块效果：事件自带 Parameters/Curve，用无参 start() 避免被全局
                // amplitude/freq 覆盖。
                stopLocked()
                val effect = de.getMethod("create", String::class.java).invoke(null, heJson)
                val hp = hapticPlayerClass ?: return false
                val newPlayer = hp.getConstructor(de).newInstance(effect)
                packageField?.set(newPlayer, packageName)
                start.invoke(newPlayer)
                player = newPlayer
                Log.i(TAG, "startEffect chars=${heJson.length}")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "startEffect 失败", t)
                false
            }
        }
    }

    private fun stopLocked() {
        val p = player ?: return
        try {
            stopMethod?.invoke(p)
        } catch (t: Throwable) {
            // 厂商实现里 stop() 可能会打印一个 NPE 栈，但不影响停止。
            Log.w(TAG, "stop 异常: ${t.message}")
        } finally {
            player = null
        }
    }

    override fun exitService() {
        stop()
        Process.killProcess(Process.myPid())
    }
}
