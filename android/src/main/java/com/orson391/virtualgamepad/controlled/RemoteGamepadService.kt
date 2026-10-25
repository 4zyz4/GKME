package com.orson391.virtualgamepad.controlled

import android.content.Context
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * Virtual gamepad service running in the Shizuku UserService process.
 *
 * This process runs as shell (uid 2000) or root, granting permission to access
 * /dev/uinput and /dev/uhid. Shizuku v13+ prefers the constructor with a [Context] parameter.
 *
 * Backends:
 *  - [BACKEND_UINPUT]: Emulates an Xbox One S Linux input device with kernel FF rumble.
 *  - [BACKEND_UHID]: Creates authentic HID report descriptors for DS4 / DualSense / Switch Pro.
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
            lastError = "Native library failed to load: ${RemoteGamepadDevice.loadError()}"
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
                    "Could not open or initialize /dev/uhid (errno=${-f}). " +
                        "This ROM blocks /dev/uhid for the shell user"
                } else {
                    "Unable to open /dev/uinput (errno=${-f})"
                }
                Log.e(TAG, lastError!!)
                return f
            }
            fd.set(f)
            Log.i(TAG, "Virtual gamepad created: backend=$backend profile=$profile fd=$f")
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
            Log.e(TAG, "nativeWrite failed", t)
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
                    Log.e(TAG, "nativeDestroy failed", t)
                }
                Log.i(TAG, "Virtual gamepad destroyed fd=$f")
            }
        }
    }

    override fun createKeyboard(): Int {
        if (!RemoteGamepadDevice.isLoaded()) {
            lastError = "Native library failed to load: ${RemoteGamepadDevice.loadError()}"
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
                lastError = "Unable to create virtual keyboard (errno=${-f})"
                Log.e(TAG, lastError!!)
                return f
            }
            kbdFd.set(f)
            Log.i(TAG, "Virtual keyboard created fd=$f")
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
            Log.e(TAG, "nativeWriteKeyboard failed", t)
            lastError = t.message
        }
    }

    override fun createMouse(): Int {
        if (!RemoteGamepadDevice.isLoaded()) {
            lastError = "Native library failed to load: ${RemoteGamepadDevice.loadError()}"
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
                lastError = "Unable to create virtual mouse (errno=${-f})"
                Log.e(TAG, lastError!!)
                return f
            }
            mouseFd.set(f)
            Log.i(TAG, "Virtual mouse created fd=$f")
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
            Log.e(TAG, "nativeWriteMouse failed", t)
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
                    Log.e(TAG, "Failed to destroy keyboard", t)
                }
                Log.i(TAG, "Virtual keyboard destroyed fd=$k")
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
                    Log.e(TAG, "Failed to destroy mouse", t)
                }
                Log.i(TAG, "Virtual mouse destroyed fd=$m")
            }
        }
    }

    override fun exitService() {
        release()
        // Shizuku convention: UserService process is not automatically killed, must terminate itself.
        Process.killProcess(Process.myPid())
    }
}
