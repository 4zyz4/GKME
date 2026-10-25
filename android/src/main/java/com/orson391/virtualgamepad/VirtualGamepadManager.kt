package com.orson391.virtualgamepad

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.orson391.virtualgamepad.controlled.IGamepadService
import com.orson391.virtualgamepad.controlled.RemoteGamepadDevice
import com.orson391.virtualgamepad.controlled.RemoteGamepadService
import com.orson391.virtualgamepad.service.VirtualGamepadService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

enum class DeviceStatus { Stopped, Starting, Running, Stopping, Error }

private enum class GamepadProfile(
    val buildValue: String,
    val backend: Int,
    val nativeProfile: Int,
    val vendorId: Int,
    val productId: Int,
) {
    Xbox("xbox", RemoteGamepadService.BACKEND_UINPUT, 0, 0x045E, 0x02FD),
    Ds4("ds4", RemoteGamepadService.BACKEND_UHID, 1, 0x054C, 0x09CC),
    DualSense("dualsense", RemoteGamepadService.BACKEND_UHID, 2, 0x054C, 0x0CE6);

    companion object {
        fun fromBuildConfig(value: String): GamepadProfile = entries.firstOrNull { it.buildValue == value }
            ?: error("Unsupported gamepadProfile '$value'")
    }
}

internal class GamepadStatusController {
    private val mutableStatus = MutableStateFlow(DeviceStatus.Stopped)
    val status: StateFlow<DeviceStatus> = mutableStatus.asStateFlow()

    fun beginStart(): Boolean = when (mutableStatus.value) {
        DeviceStatus.Stopped, DeviceStatus.Error -> { mutableStatus.value = DeviceStatus.Starting; true }
        else -> false
    }

    fun running() { mutableStatus.value = DeviceStatus.Running }
    fun beginStop(): Boolean = when (mutableStatus.value) {
        DeviceStatus.Stopped, DeviceStatus.Stopping -> false
        else -> { mutableStatus.value = DeviceStatus.Stopping; true }
    }
    fun stopped() { mutableStatus.value = DeviceStatus.Stopped }
    fun error() { mutableStatus.value = DeviceStatus.Error }
}

object VirtualGamepadManager {
    private const val TAG = "VirtualGamepadManager"
    const val SHIZUKU_REQUEST_CODE = 0x5A17
    private const val BINDER_TIMEOUT_MS = 3_000L
    private const val BIND_TIMEOUT_MS = 30_000L
    private val gamepadProfile = GamepadProfile.fromBuildConfig(BuildConfig.GAMEPAD_PROFILE)
    val selectedProfile: String = gamepadProfile.buildValue

    private val state = GamepadStatusController()
    val status: StateFlow<DeviceStatus> = state.status
    private val mutableErrorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = mutableErrorMessage.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val releaseExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var gamepadService: IGamepadService? = null
    @Volatile private var binderAlive = false
    @Volatile private var initialized = false
    @Volatile private var pendingStart = false
    private var pendingActivity: Activity? = null
    private var userServiceArgs: Shizuku.UserServiceArgs? = null
    private var bindTimeoutRunnable: Runnable? = null
    private var binderTimeoutRunnable: Runnable? = null
    private var awaitingBinder = false

    private fun fail(message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.e(TAG, message) else Log.e(TAG, message, throwable)
        pendingStart = false
        awaitingBinder = false
        cancelBinderTimeout()
        cancelBindTimeout()
        mutableErrorMessage.value = message
        state.error()
    }

    fun clearError() { mutableErrorMessage.value = null }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != SHIZUKU_REQUEST_CODE) return@OnRequestPermissionResultListener
        if (grantResult == PackageManager.PERMISSION_GRANTED && pendingStart) {
            Log.i(TAG, "Shizuku permission granted")
            pendingStart = false
            pendingActivity?.let(::startDevice)
        } else if (grantResult != PackageManager.PERMISSION_GRANTED) {
            fail("Shizuku permission denied. Open Shizuku > Authorized apps > allow Virtual Gamepad")
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        binderAlive = true
        if (awaitingBinder && status.value == DeviceStatus.Starting) {
            awaitingBinder = false
            cancelBinderTimeout()
            pendingActivity?.let(::continueStart)
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.e(TAG, "Shizuku binder died")
        binderAlive = false
        val currentStatus = status.value
        cleanup()
        if (currentStatus == DeviceStatus.Starting || currentStatus == DeviceStatus.Running) {
            fail("Shizuku binder died. Open Shizuku and start it again")
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            cancelBindTimeout()
            if (status.value != DeviceStatus.Starting) return
            if (binder == null) {
                cleanup()
                fail("Shizuku returned a null gamepad service binder. Restart Shizuku and try again")
                return
            }
            try {
                gamepadService = IGamepadService.Stub.asInterface(binder)
                Log.i(TAG, "Creating ${gamepadProfile.buildValue} profile " +
                    "(${String.format("%04x:%04x", gamepadProfile.vendorId, gamepadProfile.productId)})")
                val result = gamepadService!!.create(
                    gamepadProfile.backend,
                    gamepadProfile.nativeProfile,
                    false,
                )
                if (result == 0) {
                    gamepadService!!.update(
                        0, 0, 0, 0, 0, 0, 0,
                        0f, 0f, 0f, 0f, 0f, 0f,
                        null,
                    )
                    Log.i(TAG, "Virtual ${gamepadProfile.buildValue} gamepad created and sent neutral input")
                    state.running()
                } else {
                    cleanup()
                    fail(createError(result))
                }
            } catch (throwable: Throwable) {
                cleanup()
                fail("Could not create the virtual gamepad service: ${throwable.message ?: throwable.javaClass.simpleName}", throwable)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.e(TAG, "Shizuku gamepad service disconnected")
            gamepadService = null
            if (status.value == DeviceStatus.Starting || status.value == DeviceStatus.Running) {
                cleanup()
                fail("Shizuku gamepad service disconnected. Restart Shizuku and try again")
            }
        }
    }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (throwable: Throwable) {
            Log.e(TAG, "Failed to register Shizuku listeners", throwable)
        }
    }

    fun start(activity: Activity) {
        init(activity)
        if (!state.beginStart()) {
            Log.d(TAG, "Start ignored: current status is ${status.value}")
            return
        }
        clearError()
        pendingActivity = activity
        if (!binderAlive) {
            awaitingBinder = true
            binderTimeoutRunnable = Runnable {
                if (awaitingBinder && status.value == DeviceStatus.Starting) {
                    awaitingBinder = false
                    fail("Shizuku is not running. Open Shizuku and start it")
                }
            }
            mainHandler.postDelayed(binderTimeoutRunnable!!, BINDER_TIMEOUT_MS)
            return
        }
        continueStart(activity)
    }

    private fun continueStart(activity: Activity) {
        if (status.value != DeviceStatus.Starting) return
        if (!binderAlive || !Shizuku.pingBinder()) {
            fail("Shizuku is not running. Open Shizuku and start it")
            return
        }
        if (Shizuku.isPreV11()) {
            fail("Shizuku is older than v11. Update Shizuku and try again")
            return
        }
        val permissionGranted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (throwable: Throwable) {
            fail("Could not check Shizuku permission. Open Shizuku and start it", throwable)
            return
        }
        if (permissionGranted) {
            startDevice(activity)
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            fail("Shizuku permission is permanently denied. Open Shizuku > Authorized apps > allow Virtual Gamepad")
        } else {
            pendingStart = true
            try {
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            } catch (throwable: Throwable) {
                fail("Could not request Shizuku permission. Open Shizuku > Authorized apps > allow Virtual Gamepad", throwable)
            }
        }
    }

    private fun startDevice(context: Context) {
        if (status.value != DeviceStatus.Starting) return
        val app = context.applicationContext
        try {
            VirtualGamepadService.start(app)
        } catch (throwable: Throwable) {
            fail("Could not start the gamepad notification service: ${throwable.message ?: throwable.javaClass.simpleName}", throwable)
            return
        }
        val args = Shizuku.UserServiceArgs(ComponentName(app.packageName, RemoteGamepadService::class.java.name))
            .daemon(false).processNameSuffix("virtual_gamepad").debuggable(false).version(2).tag("VirtualGamepad")
        userServiceArgs = args
        bindTimeoutRunnable = Runnable {
            if (status.value == DeviceStatus.Starting) {
                cleanup()
                fail("Shizuku could not start the gamepad service. Restart Shizuku (or reboot if two Shizuku servers are running)")
            }
        }
        mainHandler.postDelayed(bindTimeoutRunnable!!, BIND_TIMEOUT_MS)
        try {
            Shizuku.bindUserService(args, serviceConnection)
        } catch (throwable: Throwable) {
            cleanup()
            fail("Could not bind the Shizuku gamepad service: ${throwable.message ?: throwable.javaClass.simpleName}", throwable)
        }
    }

    fun stop(context: Context) {
        if (!state.beginStop()) {
            Log.d(TAG, "Stop ignored: current status is ${status.value}")
            return
        }
        pendingStart = false
        awaitingBinder = false
        cancelBinderTimeout()
        cancelBindTimeout()
        val service = gamepadService
        gamepadService = null
        cleanup()
        releaseExecutor.execute {
            try { service?.release() } catch (throwable: Throwable) { Log.e(TAG, "Exception while releasing IGamepadService", throwable) }
            finally { mainHandler.post { state.stopped(); Log.i(TAG, "Virtual gamepad stopped") } }
        }
    }

    private fun createError(result: Int): String {
        val errno = -result
        if (gamepadProfile.backend == RemoteGamepadService.BACKEND_UHID) {
            return "Could not create the ${gamepadProfile.buildValue} UHID gamepad (errno=$errno). " +
                "This ROM blocks /dev/uhid for the shell user"
        }
        val suffix = if (errno == 19) {
            val loadError = runCatching { RemoteGamepadDevice.loadError() }.getOrNull()
            ". errno 19 means the native library failed to load${loadError?.let { ": $it" } ?: ""}"
        } else ""
        return "Virtual gamepad create() failed with errno=$errno$suffix"
    }

    private fun cancelBindTimeout() { bindTimeoutRunnable?.let(mainHandler::removeCallbacks); bindTimeoutRunnable = null }
    private fun cancelBinderTimeout() { binderTimeoutRunnable?.let(mainHandler::removeCallbacks); binderTimeoutRunnable = null }
    private fun cleanup() {
        userServiceArgs?.let { args ->
            try { Shizuku.unbindUserService(args, serviceConnection, true) }
            catch (throwable: Throwable) { Log.w(TAG, "Error unbinding gamepad user service", throwable) }
        }
        userServiceArgs = null
        gamepadService = null
    }
}
