// User service interface for virtual gamepad.
// The Stub for this interface is instantiated in the Shizuku shell/root process, and the App process communicates via Binder.
package com.orson391.virtualgamepad.controlled;

interface IGamepadService {
    /**
     * Create virtual gamepad. Returns 0 on success, or negative (-errno) on error.
     * backend: 0 = uinput (emulates Xbox One S), 1 = uhid (authentic HID device).
     * profile: when backend=uhid, select identity: 1 = DualShock 4, 2 = DualSense, 3 = Switch Pro.
     * rumbleEnabled: whether force feedback vibration is forwarded. FF capability is always exposed;
     *   local mode passes false to avoid feedback loops.
     */
    int create(int backend, int profile, boolean rumbleEnabled) = 1;

    /**
     * Write gamepad state; buttons use XInput wButtons bitmask (bit17 = touchpad click).
     * gyro/accel are motion sensor values (rad/s, m/s^2), only used by uhid; ignored by uinput.
     * touches are touchpad data: up to 2 touch points, 4 ints each [id, x, y, active],
     * x/y in 0..1919 / 0..942; only used by DS4/DualSense, can pass null or empty array.
     */
    void update(int buttons, int leftTrigger, int rightTrigger,
                int leftX, int leftY, int rightX, int rightY,
                float gyroX, float gyroY, float gyroZ,
                float accelX, float accelY, float accelZ,
                in int[] touches) = 2;

    /** Read pending vibration data. High 16 bits = left motor, low 16 bits = right motor (0..65535). Returns 0 if none. */
    long pumpRumble() = 3;

    /** Destroy virtual gamepad. */
    void release() = 4;

    /** Create virtual keyboard. Returns 0 on success, negative (-errno) on error. */
    int createKeyboard() = 5;

    /**
     * Write full keyboard state.
     * modifiers: HID modifier bitmask (bit0=LCtrl ... bit7=RGui).
     * usages: HID Keyboard/Keypad usage IDs (0x04..0xFF, excluding modifiers 0xE0..0xE7).
     */
    void updateKeyboard(int modifiers, in int[] usages) = 6;

    /** Create virtual mouse. Returns 0 on success, negative (-errno) on error. */
    int createMouse() = 7;

    /**
     * Write mouse state frame.
     * buttons: bitmask (bit0 Left, bit1 Right, bit2 Middle, bit3 Back, bit4 Forward).
     * dx/dy: relative displacement.
     * wheel/pan: vertical/horizontal scroll wheel.
     */
    void updateMouse(int dx, int dy, int wheel, int pan, int buttons) = 8;

    /** Destroy virtual keyboard and mouse. */
    void releaseKeyboardMouse() = 9;

    /**
     * UserService exit transaction (Shizuku convention: code 16777114 in AIDL, 16777115 in protocol).
     */
    void exitService() = 16777114;
}
