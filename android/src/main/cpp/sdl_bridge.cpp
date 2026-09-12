// JNI bridge that lets the GKME app use SDL3 for physical gamepad handling.
//
// SDL3 (libSDL3.so) is provided prebuilt by SDL3-3.4.16.aar. The Java glue layer
// (org.libsdl.app.SDL / SDLControllerManager / HIDDeviceManager) is initialised from
// Kotlin (see SdlPlatform.kt); this bridge only drives the native SDL_Gamepad /
// SDL_Sensor / SDL_RumbleGamepad APIs and exposes snapshots to Kotlin.

#include <jni.h>
#include <android/log.h>

#include <SDL3/SDL.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#define LOG_TAG "GkmeSdl"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Application button bits, must match com.zyz4.gkme.model.GamepadState.
constexpr int BIT_A = 0x00001;
constexpr int BIT_B = 0x00002;
constexpr int BIT_X = 0x00004;
constexpr int BIT_Y = 0x00008;
constexpr int BIT_LB = 0x00010;
constexpr int BIT_RB = 0x00020;
constexpr int BIT_LT = 0x00040;
constexpr int BIT_RT = 0x00080;
constexpr int BIT_SELECT = 0x00100;
constexpr int BIT_START = 0x00200;
constexpr int BIT_L3 = 0x00400;
constexpr int BIT_R3 = 0x00800;
constexpr int BIT_DPAD_UP = 0x01000;
constexpr int BIT_DPAD_DOWN = 0x02000;
constexpr int BIT_DPAD_LEFT = 0x04000;
constexpr int BIT_DPAD_RIGHT = 0x08000;
constexpr int BIT_HOME = 0x10000;
constexpr int BIT_TOUCHPAD_CLICK = 0x20000;
constexpr int BIT_MIC_MUTE = 0x40000;

// Application D-pad hat values, must match com.zyz4.gkme.model.GamepadState.
constexpr int DPAD_UP = 1;
constexpr int DPAD_DOWN = 2;
constexpr int DPAD_LEFT = 4;
constexpr int DPAD_RIGHT = 8;

constexpr int TOUCHPAD_MAX_X = 1919;
constexpr int TOUCHPAD_MAX_Y = 942;
constexpr int TRIGGER_DIGITAL_THRESHOLD = 16384;  // half of 0..32767

struct Snapshot {
    int buttons = 0;
    int leftX = 0;
    int leftY = 0;
    int rightX = 0;
    int rightY = 0;
    int leftTrigger = 0;
    int rightTrigger = 0;
    int dpad = 0;
    int touchCount = 0;
    int touchX[2] = {0, 0};
    int touchY[2] = {0, 0};
    bool touchpadTouch = false;
    bool touchpadClick = false;
    float gyro[3] = {0.0f, 0.0f, 0.0f};
    float accel[3] = {0.0f, 0.0f, 0.0f};
};

struct Entry {
    SDL_JoystickID id = 0;
    SDL_Gamepad *handle = nullptr;
    std::string name;
    int motorCount = 0;
    bool hasGyro = false;
    bool hasAccel = false;
    bool sensorRequested = false;
    bool sensorApplied = false;
    // Physical identity, used to keep a stable slot when SDL hands a device from
    // one driver to another (e.g. Android -> HIDAPI on USB PlayStation pads).
    Uint16 vendor = 0;
    Uint16 product = 0;
    Uint64 removeTime = 0;
    bool pending = false;
    Snapshot snapshot;
};

// A device can briefly disappear from SDL while it is being handed over between
// drivers. Keep its slot visible for this long before dropping it.
constexpr Uint64 GAMEPAD_HANDOFF_GRACE_MS = 2000;

// SDL's Android joystick driver always tags devices as Bluetooth (see
// Android_AddJoystick), even for USB devices, while HIDAPI reports the real bus.
constexpr Uint16 SDL_BUS_BLUETOOTH = 0x05;

// How long to wait for HIDAPI to claim a USB controller before falling back to the
// Android driver. SDL's HIDAPI on Android only rescans every ~3s (plus the USB
// permission prompt), so waiting longer mostly just delays usability. After this we
// use the Android driver immediately and upgrade in place when HIDAPI takes over.
constexpr Uint64 HIDAPI_PREFER_MS = 3000;

// Shorter wait for USB pads whose exact model SDL does not classify; HIDAPI may or
// may not support them, so fall back to the Android driver sooner.
constexpr Uint64 HIDAPI_PREFER_SHORT_MS = 2000;

std::mutex g_mutex;
std::vector<Entry> g_gamepads;
std::thread g_thread;
std::atomic<bool> g_running{false};
std::atomic<bool> g_initialized{false};

std::mutex g_initMutex;
std::condition_variable g_initCv;
bool g_initFinished = false;
bool g_initResult = false;

// vendor/product key -> deadline until which we wait for HIDAPI to claim the device.
std::unordered_map<Uint32, Uint64> g_pendingHidapi;

// vendor/product keys of the USB devices currently attached to Android (pushed
// from Kotlin). Used to tell that an Android-driver gamepad is really a USB device.
std::unordered_set<Uint32> g_usbDeviceKeys;

int clampInt(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

Uint16 gamepadBus(SDL_JoystickID id) {
    const SDL_GUID guid = SDL_GetGamepadGUIDForID(id);
    return static_cast<Uint16>(guid.data[0] | (guid.data[1] << 8));
}

// Console controllers that HIDAPI is known to handle (and where it adds gyro,
// touchpad and proper rumble). Only these wait for HIDAPI so generic HID pads
// are not delayed.
bool isKnownHidapiType(SDL_JoystickID id) {
    switch (SDL_GetGamepadTypeForID(id)) {
        case SDL_GAMEPAD_TYPE_PS3:
        case SDL_GAMEPAD_TYPE_PS4:
        case SDL_GAMEPAD_TYPE_PS5:
        case SDL_GAMEPAD_TYPE_XBOX360:
        case SDL_GAMEPAD_TYPE_XBOXONE:
        case SDL_GAMEPAD_TYPE_NINTENDO_SWITCH_PRO:
        case SDL_GAMEPAD_TYPE_NINTENDO_SWITCH_JOYCON_LEFT:
        case SDL_GAMEPAD_TYPE_NINTENDO_SWITCH_JOYCON_RIGHT:
        case SDL_GAMEPAD_TYPE_NINTENDO_SWITCH_JOYCON_PAIR:
        case SDL_GAMEPAD_TYPE_GAMECUBE:
            return true;
        default:
            return false;
    }
}

// True when Android currently has a USB device with this vendor/product. This is
// independent of HIDAPI's open/permission state, unlike SDL_hid_enumerate().
bool isUsbDevice(Uint32 key) {
    return g_usbDeviceKeys.find(key) != g_usbDeviceKeys.end();
}

void applyHints() {
    // Use the HIDAPI drivers whenever possible (USB / BLE controllers).
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI, "1");
    // "Extended reports": rumble/effects on Bluetooth PlayStation controllers and
    // gyro on Nintendo Switch controllers. This is the "扩展报告" feature.
    SDL_SetHint(SDL_HINT_JOYSTICK_ENHANCED_REPORTS, "1");

    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_PS3, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_PS4, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_PS5, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_SWITCH, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_SWITCH2, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_JOY_CONS, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_COMBINE_JOY_CONS, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_NINTENDO_CLASSIC, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_WII, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_XBOX, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_XBOX_360, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_XBOX_360_WIRELESS, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_XBOX_ONE, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_GIP, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_STEAM, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_STEAMDECK, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_STADIA, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_LUNA, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_SHIELD, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_8BITDO, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_FLYDIGI, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_SINPUT, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_ZUIKI, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_LG4FF, "1");
    SDL_SetHint(SDL_HINT_JOYSTICK_HIDAPI_GAMECUBE, "1");

    SDL_SetHint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, "1");
}

void updateSnapshot(Entry &entry) {
    SDL_Gamepad *gp = entry.handle;
    if (!gp) {
        return;
    }
    Snapshot &s = entry.snapshot;

    int buttons = 0;
    if (SDL_GetGamepadButton(gp, SDL_GAMEPAD_BUTTON_SOUTH)) buttons |= BIT_A;
    if (SDL_GetGamepadButton(gp, SDL_GAMEPAD_BUTTON_EAST)) buttons |= BIT_B;
    if (SDL_GetGamepadButton(gp, SDL_GAMEPAD_BUTTON_WEST)) buttons |= BIT_X;
    if (SDL_GetGamepadButton(gp, SDL_GAMEPAD_BUTTON_NORTH)) buttons |= BIT_Y;
    if (SDL_GetGamepadButton(gp, SDL_GAMEPAD_BUTTON_LEFT_SHOULDER)) buttons |= BIT_LB;
    if (SDL_GetGamepadButton(gp, SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER)) buttons |= BIT_RB;
    if (SDL_GetGamepadButton(gp, SDL_GAMEPAD_BUTTON_BACK)) buttons |= BIT_SELECT;
    if (SDL_GetGamepadButton(gp, SDL_GAMEPAD_BUTTON_START)) buttons |= BIT_START;
    if (SDL_GetGamepadButton(gp, SDL_GAMEPAD_BUTTON_LEFT_STICK)) buttons |= BIT_L3;
    if (SDL_GetGamepadButton(gp, SDL_GAMEPAD_BUTTON_RIGHT_STICK)) buttons |= BIT_R3;
    if (SDL_GetGamepadButton(gp, SDL_GAMEPAD_BUTTON_GUIDE)) buttons |= BIT_HOME;
    if (SDL_GetGamepadButton(gp, SDL_GAMEPAD_BUTTON_TOUCHPAD)) buttons |= BIT_TOUCHPAD_CLICK;
    if (SDL_GetGamepadButton(gp, SDL_GAMEPAD_BUTTON_MISC1)) buttons |= BIT_MIC_MUTE;

    int dpad = 0;
    if (SDL_GetGamepadButton(gp, SDL_GAMEPAD_BUTTON_DPAD_UP)) dpad |= DPAD_UP;
    if (SDL_GetGamepadButton(gp, SDL_GAMEPAD_BUTTON_DPAD_DOWN)) dpad |= DPAD_DOWN;
    if (SDL_GetGamepadButton(gp, SDL_GAMEPAD_BUTTON_DPAD_LEFT)) dpad |= DPAD_LEFT;
    if (SDL_GetGamepadButton(gp, SDL_GAMEPAD_BUTTON_DPAD_RIGHT)) dpad |= DPAD_RIGHT;
    buttons |= (dpad & DPAD_UP) ? BIT_DPAD_UP : 0;
    buttons |= (dpad & DPAD_DOWN) ? BIT_DPAD_DOWN : 0;
    buttons |= (dpad & DPAD_LEFT) ? BIT_DPAD_LEFT : 0;
    buttons |= (dpad & DPAD_RIGHT) ? BIT_DPAD_RIGHT : 0;

    const int ltRaw = SDL_GetGamepadAxis(gp, SDL_GAMEPAD_AXIS_LEFT_TRIGGER);
    const int rtRaw = SDL_GetGamepadAxis(gp, SDL_GAMEPAD_AXIS_RIGHT_TRIGGER);
    const int lt = ltRaw * 255 / 32767;
    const int rt = rtRaw * 255 / 32767;
    if (ltRaw > TRIGGER_DIGITAL_THRESHOLD) buttons |= BIT_LT;
    if (rtRaw > TRIGGER_DIGITAL_THRESHOLD) buttons |= BIT_RT;

    int touchCount = 0;
    int touchX[2] = {0, 0};
    int touchY[2] = {0, 0};
    if (SDL_GetNumGamepadTouchpads(gp) > 0) {
        const int fingers = SDL_GetNumGamepadTouchpadFingers(gp, 0);
        for (int i = 0; i < fingers && touchCount < 2; ++i) {
            bool down = false;
            float x = 0.0f;
            float y = 0.0f;
            float pressure = 0.0f;
            if (SDL_GetGamepadTouchpadFinger(gp, 0, i, &down, &x, &y, &pressure) && down) {
                touchX[touchCount] = clampInt(static_cast<int>(x * TOUCHPAD_MAX_X), 0, TOUCHPAD_MAX_X);
                touchY[touchCount] = clampInt(static_cast<int>(y * TOUCHPAD_MAX_Y), 0, TOUCHPAD_MAX_Y);
                ++touchCount;
            }
        }
    }

    float gyro[3] = {0.0f, 0.0f, 0.0f};
    float accel[3] = {0.0f, 0.0f, 0.0f};
    if (entry.sensorApplied) {
        if (entry.hasGyro) {
            SDL_GetGamepadSensorData(gp, SDL_SENSOR_GYRO, gyro, 3);
        }
        if (entry.hasAccel) {
            SDL_GetGamepadSensorData(gp, SDL_SENSOR_ACCEL, accel, 3);
        }
    }

    s.buttons = buttons;
    s.leftX = SDL_GetGamepadAxis(gp, SDL_GAMEPAD_AXIS_LEFTX);
    s.leftY = SDL_GetGamepadAxis(gp, SDL_GAMEPAD_AXIS_LEFTY);
    s.rightX = SDL_GetGamepadAxis(gp, SDL_GAMEPAD_AXIS_RIGHTX);
    s.rightY = SDL_GetGamepadAxis(gp, SDL_GAMEPAD_AXIS_RIGHTY);
    s.leftTrigger = lt;
    s.rightTrigger = rt;
    s.dpad = dpad;
    s.touchCount = touchCount;
    s.touchX[0] = touchX[0];
    s.touchX[1] = touchX[1];
    s.touchY[0] = touchY[0];
    s.touchY[1] = touchY[1];
    s.touchpadTouch = touchCount > 0;
    s.touchpadClick = (buttons & BIT_TOUCHPAD_CLICK) != 0;
    std::memcpy(s.gyro, gyro, sizeof(gyro));
    std::memcpy(s.accel, accel, sizeof(accel));
}

void reconcileLocked() {
    int count = 0;
    SDL_JoystickID *ids = SDL_GetGamepads(&count);
    if (!ids) {
        return;
    }
    const Uint64 now = SDL_GetTicks();

    // Mark gamepads that SDL no longer reports as pending instead of dropping them
    // immediately. On Android a USB PlayStation pad is first exposed by the Android
    // driver and then handed over to HIDAPI; keeping the slot avoids a flicker in
    // the device list.
    for (auto &entry : g_gamepads) {
        bool found = false;
        for (int i = 0; i < count; ++i) {
            if (ids[i] == entry.id) {
                found = true;
                break;
            }
        }
        if (!found && !entry.pending) {
            LOGI("Gamepad removed: %s", entry.name.c_str());
            if (entry.handle) {
                SDL_CloseGamepad(entry.handle);
                entry.handle = nullptr;
            }
            entry.snapshot = Snapshot();
            entry.sensorApplied = false;
            entry.pending = true;
            entry.removeTime = now;
        }
    }

    // Attach newly reported gamepads, reusing a pending slot with the same
    // vendor/product so the list stays stable across the driver hand-over.
    for (int i = 0; i < count; ++i) {
        bool found = false;
        for (const auto &entry : g_gamepads) {
            if (entry.id == ids[i]) {
                found = true;
                break;
            }
        }
        if (found) {
            continue;
        }
        const Uint16 vendor = SDL_GetGamepadVendorForID(ids[i]);
        const Uint16 product = SDL_GetGamepadProductForID(ids[i]);
        const Uint16 bus = gamepadBus(ids[i]);
        const Uint32 key = (static_cast<Uint32>(vendor) << 16) | product;

        if (bus == SDL_BUS_BLUETOOTH) {
            // SDL's Android driver tags every device as Bluetooth. For a USB device,
            // prefer HIDAPI and give it a chance to claim the controller first (it
            // exposes gyro/touchpad/rumble); only fall back to the Android driver if
            // HIDAPI never takes over.
            auto pending = g_pendingHidapi.find(key);
            if (pending != g_pendingHidapi.end()) {
                if (now < pending->second) {
                    continue;
                }
                g_pendingHidapi.erase(pending);
            } else if (isUsbDevice(key)) {
                const Uint64 wait = isKnownHidapiType(ids[i])
                                        ? HIDAPI_PREFER_MS
                                        : HIDAPI_PREFER_SHORT_MS;
                g_pendingHidapi[key] = now + wait;
                LOGI("Waiting for HIDAPI to claim 0x%04x/0x%04x", vendor, product);
                continue;
            }
        } else {
            g_pendingHidapi.erase(key);
        }

        SDL_Gamepad *gp = SDL_OpenGamepad(ids[i]);
        if (!gp) {
            continue;
        }
        const char *name = SDL_GetGamepadName(gp);

        Entry *target = nullptr;
        for (auto &entry : g_gamepads) {
            if (entry.pending && entry.vendor == vendor && entry.product == product) {
                target = &entry;
                break;
            }
        }

        const bool hasGyro = SDL_GamepadHasSensor(gp, SDL_SENSOR_GYRO);
        const bool hasAccel = SDL_GamepadHasSensor(gp, SDL_SENSOR_ACCEL);

        if (target != nullptr) {
            // Same physical device re-appeared through another driver: reuse the
            // slot (no flicker) and update the name to the active driver's name.
            target->id = ids[i];
            target->handle = gp;
            target->name = name ? name : target->name;
            target->hasGyro = hasGyro;
            target->hasAccel = hasAccel;
            target->pending = false;
            target->removeTime = 0;
            target->sensorApplied = false;
            LOGI("Gamepad re-attached: %s", target->name.c_str());
        } else {
            Entry entry;
            entry.id = ids[i];
            entry.handle = gp;
            entry.name = name ? name : "Gamepad";
            entry.hasGyro = hasGyro;
            entry.hasAccel = hasAccel;
            entry.vendor = vendor;
            entry.product = product;
            // SDL does not expose a motor count; the vast majority of gamepads expose a
            // dual (low/high frequency) rumble. Report 2 so the swap-motor UI is available.
            entry.motorCount = 2;
            g_gamepads.push_back(entry);
            LOGI("Gamepad added: %s (gyro=%d accel=%d)", entry.name.c_str(),
                 entry.hasGyro ? 1 : 0, entry.hasAccel ? 1 : 0);
        }
    }

    // Drop stale pending entries (device unplugged while we were waiting for HIDAPI).
    for (auto it = g_pendingHidapi.begin(); it != g_pendingHidapi.end();) {
        if (now > it->second + 60000) {
            it = g_pendingHidapi.erase(it);
        } else {
            ++it;
        }
    }

    // Drop slots that did not come back within the grace period.
    for (auto it = g_gamepads.begin(); it != g_gamepads.end();) {
        if (it->pending && now - it->removeTime > GAMEPAD_HANDOFF_GRACE_MS) {
            it = g_gamepads.erase(it);
        } else {
            ++it;
        }
    }

    SDL_free(ids);
}

void pollLoop() {
    // SDL is initialised and pumped on this dedicated thread.
    const bool ok = SDL_Init(SDL_INIT_GAMEPAD | SDL_INIT_SENSOR);
    if (!ok) {
        LOGE("SDL_Init failed: %s", SDL_GetError());
    }
    {
        std::lock_guard<std::mutex> lock(g_initMutex);
        g_initResult = ok;
        g_initFinished = true;
    }
    g_initCv.notify_all();

    if (!ok) {
        g_running.store(false);
        return;
    }

    while (g_running.load(std::memory_order_relaxed)) {
        {
            std::lock_guard<std::mutex> lock(g_mutex);
            SDL_PumpEvents();
            SDL_UpdateGamepads();
            reconcileLocked();
            for (auto &entry : g_gamepads) {
                if (entry.handle != nullptr && entry.sensorRequested != entry.sensorApplied) {
                    if (entry.hasGyro) {
                        SDL_SetGamepadSensorEnabled(entry.handle, SDL_SENSOR_GYRO, entry.sensorRequested);
                    }
                    if (entry.hasAccel) {
                        SDL_SetGamepadSensorEnabled(entry.handle, SDL_SENSOR_ACCEL, entry.sensorRequested);
                    }
                    entry.sensorApplied = entry.sensorRequested;
                }
                updateSnapshot(entry);
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        for (auto &entry : g_gamepads) {
            if (entry.handle) {
                SDL_CloseGamepad(entry.handle);
            }
        }
        g_gamepads.clear();
        g_pendingHidapi.clear();
        g_usbDeviceKeys.clear();
    }
    SDL_Quit();
}

bool validIndex(int index) {
    return index >= 0 && index < static_cast<int>(g_gamepads.size());
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeInit(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    if (g_initialized.load()) {
        return JNI_TRUE;
    }
    applyHints();

    {
        std::lock_guard<std::mutex> lock(g_initMutex);
        g_initFinished = false;
        g_initResult = false;
    }
    g_running.store(true);
    g_thread = std::thread(pollLoop);

    std::unique_lock<std::mutex> lock(g_initMutex);
    g_initCv.wait_for(lock, std::chrono::seconds(3), [] { return g_initFinished; });
    const bool result = g_initResult;
    lock.unlock();

    if (result) {
        g_initialized.store(true);
        LOGI("SDL3 gamepad subsystem initialised");
    } else {
        g_running.store(false);
        if (g_thread.joinable()) {
            g_thread.join();
        }
    }
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeShutdown(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    if (!g_initialized.load()) {
        return;
    }
    g_running.store(false);
    if (g_thread.joinable()) {
        g_thread.join();
    }
    g_initialized.store(false);
    LOGI("SDL3 gamepad subsystem shut down");
}

JNIEXPORT jint JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeGetControllerCount(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    return static_cast<jint>(g_gamepads.size());
}

JNIEXPORT jstring JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeGetControllerName(JNIEnv *env, jobject thiz, jint index) {
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!validIndex(index)) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(g_gamepads[index].name.c_str());
}

JNIEXPORT jint JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeGetControllerInstanceId(JNIEnv *env, jobject thiz, jint index) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!validIndex(index)) {
        return -1;
    }
    return static_cast<jint>(g_gamepads[index].id);
}

JNIEXPORT jint JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeGetControllerMotorCount(JNIEnv *env, jobject thiz, jint index) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!validIndex(index)) {
        return 0;
    }
    return static_cast<jint>(g_gamepads[index].motorCount);
}

JNIEXPORT jboolean JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeGetControllerHasGyro(JNIEnv *env, jobject thiz, jint index) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!validIndex(index)) {
        return JNI_FALSE;
    }
    return g_gamepads[index].hasGyro ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeGetControllerHasAccel(JNIEnv *env, jobject thiz, jint index) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!validIndex(index)) {
        return JNI_FALSE;
    }
    return g_gamepads[index].hasAccel ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeGetControllerType(JNIEnv *env, jobject thiz, jint index) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!validIndex(index)) {
        return 0;
    }
    return static_cast<jint>(SDL_GetGamepadType(g_gamepads[index].handle));
}

JNIEXPORT jboolean JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativePollState(JNIEnv *env, jobject thiz, jint index,
                                                   jintArray outArray) {
    (void) thiz;
    if (!outArray || env->GetArrayLength(outArray) < 16) {
        return JNI_FALSE;
    }
    jint values[16] = {0};
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!validIndex(index)) {
            env->SetIntArrayRegion(outArray, 0, 16, values);
            return JNI_FALSE;
        }
        const Snapshot &s = g_gamepads[index].snapshot;
        values[0] = s.buttons;
        values[1] = s.leftX;
        values[2] = s.leftY;
        values[3] = s.rightX;
        values[4] = s.rightY;
        values[5] = s.leftTrigger;
        values[6] = s.rightTrigger;
        values[7] = s.dpad;
        values[8] = s.touchCount;
        values[9] = s.touchX[0];
        values[10] = s.touchY[0];
        values[11] = s.touchX[1];
        values[12] = s.touchY[1];
        values[13] = s.touchpadTouch ? 1 : 0;
        values[14] = s.touchpadClick ? 1 : 0;
        values[15] = 1;
    }
    env->SetIntArrayRegion(outArray, 0, 16, values);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativePollSensor(JNIEnv *env, jobject thiz, jint index,
                                                    jfloatArray outArray) {
    (void) thiz;
    if (!outArray || env->GetArrayLength(outArray) < 6) {
        return JNI_FALSE;
    }
    jfloat values[6] = {0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (!validIndex(index)) {
            env->SetFloatArrayRegion(outArray, 0, 6, values);
            return JNI_FALSE;
        }
        const Snapshot &s = g_gamepads[index].snapshot;
        values[0] = s.gyro[0];
        values[1] = s.gyro[1];
        values[2] = s.gyro[2];
        values[3] = s.accel[0];
        values[4] = s.accel[1];
        values[5] = s.accel[2];
    }
    env->SetFloatArrayRegion(outArray, 0, 6, values);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeRumble(JNIEnv *env, jobject thiz, jint index,
                                                jint low, jint high, jint durationMs) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!validIndex(index)) {
        return JNI_FALSE;
    }
    const Uint16 lowValue = static_cast<Uint16>(clampInt(low, 0, 65535));
    const Uint16 highValue = static_cast<Uint16>(clampInt(high, 0, 65535));
    const Uint32 duration = durationMs <= 0 ? 0 : static_cast<Uint32>(durationMs);
    return SDL_RumbleGamepad(g_gamepads[index].handle, lowValue, highValue, duration)
               ? JNI_TRUE
               : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeSetSensorEnabled(JNIEnv *env, jobject thiz, jint index,
                                                          jboolean enabled) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!validIndex(index)) {
        return;
    }
    g_gamepads[index].sensorRequested = enabled == JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeGetControllerHasLed(JNIEnv *env, jobject thiz,
                                                             jint index) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!validIndex(index) || g_gamepads[index].handle == nullptr) {
        return JNI_FALSE;
    }
    const SDL_PropertiesID props = SDL_GetGamepadProperties(g_gamepads[index].handle);
    const bool hasRgb =
        SDL_GetBooleanProperty(props, SDL_PROP_GAMEPAD_CAP_RGB_LED_BOOLEAN, false);
    const bool hasMono =
        SDL_GetBooleanProperty(props, SDL_PROP_GAMEPAD_CAP_MONO_LED_BOOLEAN, false);
    return (hasRgb || hasMono) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeGetControllerHasPlayerLed(JNIEnv *env, jobject thiz,
                                                                   jint index) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!validIndex(index) || g_gamepads[index].handle == nullptr) {
        return JNI_FALSE;
    }
    const SDL_PropertiesID props = SDL_GetGamepadProperties(g_gamepads[index].handle);
    return SDL_GetBooleanProperty(props, SDL_PROP_GAMEPAD_CAP_PLAYER_LED_BOOLEAN, false)
               ? JNI_TRUE
               : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeSetControllerLed(JNIEnv *env, jobject thiz, jint index,
                                                          jint red, jint green, jint blue) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!validIndex(index) || g_gamepads[index].handle == nullptr) {
        return JNI_FALSE;
    }
    const Uint8 r = static_cast<Uint8>(clampInt(red, 0, 255));
    const Uint8 g = static_cast<Uint8>(clampInt(green, 0, 255));
    const Uint8 b = static_cast<Uint8>(clampInt(blue, 0, 255));
    return SDL_SetGamepadLED(g_gamepads[index].handle, r, g, b) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeSetControllerPlayerIndex(JNIEnv *env, jobject thiz,
                                                                  jint index, jint playerIndex) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!validIndex(index) || g_gamepads[index].handle == nullptr) {
        return JNI_FALSE;
    }
    return SDL_SetGamepadPlayerIndex(g_gamepads[index].handle, playerIndex) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeSetUsbDeviceIds(JNIEnv *env, jobject thiz,
                                                         jintArray keys) {
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    g_usbDeviceKeys.clear();
    if (!keys) {
        return;
    }
    const jsize length = env->GetArrayLength(keys);
    if (length <= 0) {
        return;
    }
    jint *values = env->GetIntArrayElements(keys, nullptr);
    if (!values) {
        return;
    }
    for (jsize i = 0; i < length; ++i) {
        g_usbDeviceKeys.insert(static_cast<Uint32>(values[i]));
    }
    env->ReleaseIntArrayElements(keys, values, JNI_ABORT);
}

}  // extern "C"
