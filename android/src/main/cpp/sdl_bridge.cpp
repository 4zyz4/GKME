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
// Physical-only back (paddle) bits, must match com.zyz4.gkme.model.PhysicalInputs.
constexpr int BIT_PADDLE_R1 = 0x400000;
constexpr int BIT_PADDLE_L1 = 0x800000;
constexpr int BIT_PADDLE_R2 = 0x1000000;
constexpr int BIT_PADDLE_L2 = 0x2000000;

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
    // Last rumble we forwarded to SDL for this pad. SDL caches the value and skips the
    // driver when it matches, so we force a fresh stop report on the transition to 0.
    int lastRumbleLow = 0;
    int lastRumbleHigh = 0;
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

// ── SDL audio output (phone speaker / external sound devices) ──
// The audio subsystem is initialised independently of the gamepad session, so the
// phone-speaker path keeps working when the physical-controller driver is not SDL.
std::mutex g_audioInitMutex;
std::atomic<bool> g_audioInitialized{false};

// Cached playback device list, refreshed from Kotlin before the UI reads it.
std::mutex g_audioDeviceMutex;
std::vector<SDL_AudioDeviceID> g_audioDevices;
std::vector<std::string> g_audioDeviceNames;

// Active playback sinks, keyed by an app-chosen handle (voice coil, controller
// audio, ...). Each sink is one SDL_AudioStream bound to the selected device, so
// the two lanes can play on different sound devices at the same time.
struct AudioSink {
    SDL_AudioStream *stream = nullptr;
    int srcRate = 0;
    int srcChannels = 0;
    int maxQueuedBytes = 0;
};

std::mutex g_audioMutex;
std::unordered_map<int, AudioSink> g_audioSinks;

int clampInt(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

Uint16 gamepadBus(SDL_JoystickID id) {
    const SDL_GUID guid = SDL_GetGamepadGUIDForID(id);
    return static_cast<Uint16>(guid.data[0] | (guid.data[1] << 8));
}

// SDL exposes trigger rumble only for Xbox One gamepads (see SDL_RumbleGamepadTriggers).
bool gamepadHasTriggerRumble(SDL_Gamepad *gp) {
    return gp != nullptr && SDL_GetGamepadType(gp) == SDL_GAMEPAD_TYPE_XBOXONE;
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

    // Low-latency phone-speaker playback: keep the hardware buffer small and tag
    // the stream so Android routes it as game audio. These must be set before any
    // audio device is opened.
    SDL_SetHint(SDL_HINT_AUDIO_DEVICE_SAMPLE_FRAMES, "256");
    SDL_SetHint(SDL_HINT_AUDIO_DEVICE_STREAM_NAME, "GKME");
    SDL_SetHint(SDL_HINT_AUDIO_DEVICE_STREAM_ROLE, "Game");
}

// Builds the mask of buttons the given gamepad physically exposes, using the same bit
// layout as the application's GamepadState plus the physical-only paddle bits.
int supportedButtonMask(SDL_Gamepad *gp) {
    if (!gp) {
        return 0;
    }
    int m = 0;
    if (SDL_GamepadHasButton(gp, SDL_GAMEPAD_BUTTON_SOUTH)) m |= BIT_A;
    if (SDL_GamepadHasButton(gp, SDL_GAMEPAD_BUTTON_EAST)) m |= BIT_B;
    if (SDL_GamepadHasButton(gp, SDL_GAMEPAD_BUTTON_WEST)) m |= BIT_X;
    if (SDL_GamepadHasButton(gp, SDL_GAMEPAD_BUTTON_NORTH)) m |= BIT_Y;
    if (SDL_GamepadHasButton(gp, SDL_GAMEPAD_BUTTON_LEFT_SHOULDER)) m |= BIT_LB;
    if (SDL_GamepadHasButton(gp, SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER)) m |= BIT_RB;
    if (SDL_GamepadHasAxis(gp, SDL_GAMEPAD_AXIS_LEFT_TRIGGER)) m |= BIT_LT;
    if (SDL_GamepadHasAxis(gp, SDL_GAMEPAD_AXIS_RIGHT_TRIGGER)) m |= BIT_RT;
    if (SDL_GamepadHasButton(gp, SDL_GAMEPAD_BUTTON_BACK)) m |= BIT_SELECT;
    if (SDL_GamepadHasButton(gp, SDL_GAMEPAD_BUTTON_START)) m |= BIT_START;
    if (SDL_GamepadHasButton(gp, SDL_GAMEPAD_BUTTON_LEFT_STICK)) m |= BIT_L3;
    if (SDL_GamepadHasButton(gp, SDL_GAMEPAD_BUTTON_RIGHT_STICK)) m |= BIT_R3;
    if (SDL_GamepadHasButton(gp, SDL_GAMEPAD_BUTTON_DPAD_UP)) m |= BIT_DPAD_UP;
    if (SDL_GamepadHasButton(gp, SDL_GAMEPAD_BUTTON_DPAD_DOWN)) m |= BIT_DPAD_DOWN;
    if (SDL_GamepadHasButton(gp, SDL_GAMEPAD_BUTTON_DPAD_LEFT)) m |= BIT_DPAD_LEFT;
    if (SDL_GamepadHasButton(gp, SDL_GAMEPAD_BUTTON_DPAD_RIGHT)) m |= BIT_DPAD_RIGHT;
    if (SDL_GamepadHasButton(gp, SDL_GAMEPAD_BUTTON_GUIDE)) m |= BIT_HOME;
    if (SDL_GamepadHasButton(gp, SDL_GAMEPAD_BUTTON_TOUCHPAD)) m |= BIT_TOUCHPAD_CLICK;
    if (SDL_GamepadHasButton(gp, SDL_GAMEPAD_BUTTON_MISC1)) m |= BIT_MIC_MUTE;
    if (SDL_GamepadHasButton(gp, SDL_GAMEPAD_BUTTON_RIGHT_PADDLE1)) m |= BIT_PADDLE_R1;
    if (SDL_GamepadHasButton(gp, SDL_GAMEPAD_BUTTON_LEFT_PADDLE1)) m |= BIT_PADDLE_L1;
    if (SDL_GamepadHasButton(gp, SDL_GAMEPAD_BUTTON_RIGHT_PADDLE2)) m |= BIT_PADDLE_R2;
    if (SDL_GamepadHasButton(gp, SDL_GAMEPAD_BUTTON_LEFT_PADDLE2)) m |= BIT_PADDLE_L2;
    return m;
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
    if (SDL_GetGamepadButton(gp, SDL_GAMEPAD_BUTTON_RIGHT_PADDLE1)) buttons |= BIT_PADDLE_R1;
    if (SDL_GetGamepadButton(gp, SDL_GAMEPAD_BUTTON_LEFT_PADDLE1)) buttons |= BIT_PADDLE_L1;
    if (SDL_GetGamepadButton(gp, SDL_GAMEPAD_BUTTON_RIGHT_PADDLE2)) buttons |= BIT_PADDLE_R2;
    if (SDL_GetGamepadButton(gp, SDL_GAMEPAD_BUTTON_LEFT_PADDLE2)) buttons |= BIT_PADDLE_L2;

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
                // Command the motors off before closing. SDL only auto-stops a
                // rumble it tracks with an expiration, so a motor latched on by a
                // dropped report (or driven through the Android vibrator path) could
                // otherwise keep running until the pad is reset.
                SDL_RumbleGamepad(entry.handle, 0, 0, 0);
                SDL_RumbleGamepadTriggers(entry.handle, 0, 0, 0);
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
    // SDL is initialised and pumped on this dedicated thread. Only the gamepad and
    // sensor subsystems are owned here; SDL_INIT_AUDIO is reference-counted
    // separately so the audio output survives a controller-driver switch.
    const bool ok = SDL_InitSubSystem(SDL_INIT_GAMEPAD | SDL_INIT_SENSOR);
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
                // Explicit stop so a motor cannot be left latched on when the
                // keyboard/stream-hold rumble outlives the SDL session.
                SDL_RumbleGamepad(entry.handle, 0, 0, 0);
                SDL_RumbleGamepadTriggers(entry.handle, 0, 0, 0);
                SDL_CloseGamepad(entry.handle);
            }
        }
        g_gamepads.clear();
        g_pendingHidapi.clear();
        g_usbDeviceKeys.clear();
    }
    // Do not call SDL_Quit() here: it would tear down the independently owned audio
    // subsystem (and any open playback stream) as well.
    SDL_QuitSubSystem(SDL_INIT_GAMEPAD | SDL_INIT_SENSOR);
}

bool validIndex(int index) {
    return index >= 0 && index < static_cast<int>(g_gamepads.size());
}

// ── SDL audio output helpers ──

// Brings up SDL_INIT_AUDIO on demand. The Android AAudio backend registers its
// device-hotplug callback here, which is what populates the playback device list.
bool ensureAudioSubsystem() {
    if (g_audioInitialized.load(std::memory_order_acquire)) {
        return true;
    }
    std::lock_guard<std::mutex> lock(g_audioInitMutex);
    if (g_audioInitialized.load(std::memory_order_relaxed)) {
        return true;
    }
    applyHints();
    if (!SDL_InitSubSystem(SDL_INIT_AUDIO)) {
        LOGE("SDL_InitSubSystem(AUDIO) failed: %s", SDL_GetError());
        return false;
    }
    g_audioInitialized.store(true, std::memory_order_release);
    LOGI("SDL3 audio subsystem initialised");
    return true;
}

// Caller must hold g_audioMutex.
void destroyAudioSinkLocked(int handle) {
    auto it = g_audioSinks.find(handle);
    if (it == g_audioSinks.end()) {
        return;
    }
    // Destroys the bound logical device too (SDL_OpenAudioDeviceStream closes it).
    if (it->second.stream != nullptr) {
        SDL_DestroyAudioStream(it->second.stream);
    }
    g_audioSinks.erase(it);
}

// Caller must hold g_audioMutex.
void destroyAllAudioSinksLocked() {
    for (auto &entry : g_audioSinks) {
        if (entry.second.stream != nullptr) {
            SDL_DestroyAudioStream(entry.second.stream);
        }
    }
    g_audioSinks.clear();
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
Java_com_zyz4_gkme_input_SdlNative_nativeGetControllerHasTriggerRumble(JNIEnv *env, jobject thiz, jint index) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!validIndex(index)) {
        return JNI_FALSE;
    }
    return gamepadHasTriggerRumble(g_gamepads[index].handle) ? JNI_TRUE : JNI_FALSE;
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

JNIEXPORT jboolean JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeGetControllerHasAnalogTriggers(JNIEnv *env, jobject thiz,
                                                                        jint index) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!validIndex(index) || g_gamepads[index].handle == nullptr) {
        return JNI_FALSE;
    }
    SDL_Gamepad *gp = g_gamepads[index].handle;
    const bool hasLeft = SDL_GamepadHasAxis(gp, SDL_GAMEPAD_AXIS_LEFT_TRIGGER);
    const bool hasRight = SDL_GamepadHasAxis(gp, SDL_GAMEPAD_AXIS_RIGHT_TRIGGER);
    return (hasLeft && hasRight) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeGetControllerHasTouchpad(JNIEnv *env, jobject thiz,
                                                                  jint index) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!validIndex(index) || g_gamepads[index].handle == nullptr) {
        return JNI_FALSE;
    }
    return SDL_GetNumGamepadTouchpads(g_gamepads[index].handle) > 0 ? JNI_TRUE : JNI_FALSE;
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

JNIEXPORT jint JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeGetControllerButtonMask(JNIEnv *env, jobject thiz,
                                                                 jint index) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!validIndex(index)) {
        return 0;
    }
    return static_cast<jint>(supportedButtonMask(g_gamepads[index].handle));
}

JNIEXPORT jint JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeGetControllerVendor(JNIEnv *env, jobject thiz, jint index) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!validIndex(index) || g_gamepads[index].handle == nullptr) {
        return 0;
    }
    return static_cast<jint>(SDL_GetGamepadVendor(g_gamepads[index].handle));
}

JNIEXPORT jint JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeGetControllerProduct(JNIEnv *env, jobject thiz, jint index) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!validIndex(index) || g_gamepads[index].handle == nullptr) {
        return 0;
    }
    return static_cast<jint>(SDL_GetGamepadProduct(g_gamepads[index].handle));
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
    Entry &entry = g_gamepads[index];
    if (lowValue == 0 && highValue == 0 &&
        (entry.lastRumbleLow != 0 || entry.lastRumbleHigh != 0)) {
        // SDL skips the driver when the requested value matches its cached value, and
        // its HIDAPI rumble thread ignores write failures. Nudging a (perceptually
        // silent) 1-count first guarantees the following zero is actually written, so
        // a stop whose first HID write was dropped cannot latch the motor.
        SDL_RumbleGamepad(entry.handle, 1, 1, 1);
    }
    entry.lastRumbleLow = lowValue;
    entry.lastRumbleHigh = highValue;
    return SDL_RumbleGamepad(entry.handle, lowValue, highValue, duration)
               ? JNI_TRUE
               : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeRumbleTriggers(JNIEnv *env, jobject thiz, jint index,
                                                        jint left, jint right, jint durationMs) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!validIndex(index)) {
        return JNI_FALSE;
    }
    const Uint16 leftValue = static_cast<Uint16>(clampInt(left, 0, 65535));
    const Uint16 rightValue = static_cast<Uint16>(clampInt(right, 0, 65535));
    const Uint32 duration = durationMs <= 0 ? 0 : static_cast<Uint32>(durationMs);
    return SDL_RumbleGamepadTriggers(g_gamepads[index].handle, leftValue, rightValue, duration)
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

// ── SDL audio output (phone speaker path) ──

JNIEXPORT jboolean JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeAudioInit(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    return ensureAudioSubsystem() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeAudioShutdown(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    {
        std::lock_guard<std::mutex> lock(g_audioMutex);
        destroyAllAudioSinksLocked();
    }
    if (g_audioInitialized.exchange(false)) {
        SDL_QuitSubSystem(SDL_INIT_AUDIO);
    }
    std::lock_guard<std::mutex> lock(g_audioDeviceMutex);
    g_audioDevices.clear();
    g_audioDeviceNames.clear();
}

JNIEXPORT jint JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeAudioRefreshDevices(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_audioDeviceMutex);
    g_audioDevices.clear();
    g_audioDeviceNames.clear();
    if (!ensureAudioSubsystem()) {
        return 0;
    }
    int count = 0;
    SDL_AudioDeviceID *ids = SDL_GetAudioPlaybackDevices(&count);
    if (ids == nullptr) {
        return 0;
    }
    for (int i = 0; i < count; ++i) {
        const char *name = SDL_GetAudioDeviceName(ids[i]);
        g_audioDevices.push_back(ids[i]);
        g_audioDeviceNames.emplace_back(name != nullptr ? name : "");
    }
    SDL_free(ids);
    return static_cast<jint>(g_audioDevices.size());
}

JNIEXPORT jint JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeAudioDeviceIdAt(JNIEnv *env, jobject thiz, jint index) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_audioDeviceMutex);
    if (index < 0 || index >= static_cast<jint>(g_audioDevices.size())) {
        return 0;
    }
    return static_cast<jint>(g_audioDevices[index]);
}

JNIEXPORT jstring JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeAudioDeviceNameAt(JNIEnv *env, jobject thiz, jint index) {
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_audioDeviceMutex);
    if (index < 0 || index >= static_cast<jint>(g_audioDeviceNames.size())) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(g_audioDeviceNames[index].c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeAudioOpen(JNIEnv *env, jobject thiz, jint handle,
                                                   jint deviceId, jint sampleRate, jint channels,
                                                   jint maxQueuedMs) {
    (void) env;
    (void) thiz;
    if (sampleRate <= 0 || channels <= 0) {
        return JNI_FALSE;
    }
    if (!ensureAudioSubsystem()) {
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(g_audioMutex);
    destroyAudioSinkLocked(handle);

    SDL_AudioSpec spec;
    SDL_zero(spec);
    spec.format = SDL_AUDIO_S16;
    spec.channels = static_cast<int>(channels);
    spec.freq = static_cast<int>(sampleRate);

    // -1 is SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK (0xFFFFFFFF): let the OS pick.
    const SDL_AudioDeviceID dev = static_cast<SDL_AudioDeviceID>(static_cast<Uint32>(deviceId));
    SDL_AudioStream *stream = SDL_OpenAudioDeviceStream(dev, &spec, nullptr, nullptr);
    if (stream == nullptr) {
        LOGE("SDL_OpenAudioDeviceStream(%u) failed: %s", static_cast<unsigned>(dev), SDL_GetError());
        return JNI_FALSE;
    }
    // SDL_OpenAudioDeviceStream opens the device paused; start it now so queued PCM
    // is played back with the smallest possible latency.
    SDL_ResumeAudioStreamDevice(stream);

    AudioSink sink;
    sink.stream = stream;
    sink.srcRate = static_cast<int>(sampleRate);
    sink.srcChannels = static_cast<int>(channels);
    sink.maxQueuedBytes = maxQueuedMs > 0
                              ? static_cast<int>(maxQueuedMs) * sink.srcRate * sink.srcChannels * 2 / 1000
                              : 0;
    g_audioSinks[static_cast<int>(handle)] = sink;
    LOGI("SDL audio sink %d opened: device=%u rate=%d ch=%d maxQueued=%dms",
         static_cast<int>(handle), static_cast<unsigned>(dev), sink.srcRate, sink.srcChannels,
         static_cast<int>(maxQueuedMs));
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeAudioWrite(JNIEnv *env, jobject thiz, jint handle,
                                                    jbyteArray data, jint waitBudgetMs) {
    (void) thiz;
    if (data == nullptr) {
        return -1;
    }
    const jsize length = env->GetArrayLength(data);
    if (length <= 0) {
        return 0;
    }
    std::vector<Uint8> buffer(static_cast<size_t>(length));
    env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte *>(buffer.data()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return -1;
    }

    std::lock_guard<std::mutex> lock(g_audioMutex);
    auto it = g_audioSinks.find(static_cast<int>(handle));
    if (it == g_audioSinks.end() || it->second.stream == nullptr) {
        return -1;
    }
    AudioSink &sink = it->second;

    // Pace the producer to real time: block while the stream holds more than the
    // low-latency budget so a network burst cannot build up unbounded latency.
    if (sink.maxQueuedBytes > 0 && waitBudgetMs > 0) {
        const Uint64 start = SDL_GetTicks();
        while (true) {
            const int queued = SDL_GetAudioStreamQueued(sink.stream);
            if (queued < 0 || queued <= sink.maxQueuedBytes) {
                break;
            }
            if (SDL_GetTicks() - start >= static_cast<Uint64>(waitBudgetMs)) {
                break;
            }
            SDL_Delay(1);
        }
    }
    if (!SDL_PutAudioStreamData(sink.stream, buffer.data(), static_cast<int>(length))) {
        LOGE("SDL_PutAudioStreamData(handle=%d) failed: %s", static_cast<int>(handle), SDL_GetError());
        return -1;
    }
    return static_cast<jint>(length);
}

JNIEXPORT void JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeAudioClose(JNIEnv *env, jobject thiz, jint handle) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_audioMutex);
    destroyAudioSinkLocked(handle);
}

JNIEXPORT void JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeAudioCloseAll(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_audioMutex);
    destroyAllAudioSinksLocked();
}

JNIEXPORT jint JNICALL
Java_com_zyz4_gkme_input_SdlNative_nativeAudioQueuedMs(JNIEnv *env, jobject thiz, jint handle) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lock(g_audioMutex);
    auto it = g_audioSinks.find(static_cast<int>(handle));
    if (it == g_audioSinks.end() || it->second.stream == nullptr ||
        it->second.srcRate <= 0 || it->second.srcChannels <= 0) {
        return 0;
    }
    const int queued = SDL_GetAudioStreamQueued(it->second.stream);
    if (queued <= 0) {
        return 0;
    }
    const int bytesPerMs = it->second.srcRate * it->second.srcChannels * 2 / 1000;
    return bytesPerMs > 0 ? queued / bytesPerMs : 0;
}

}  // extern "C"
