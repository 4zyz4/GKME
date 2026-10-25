package com.orson391.virtualgamepad.controlled;

/**
 * Java wrapper for the native uinput/uhid virtual gamepad backend.
 * Must only be invoked within the Shizuku UserService process, which runs
 * with shell/root privileges allowing access to /dev/uinput and /dev/uhid.
 */
public final class RemoteGamepadDevice {

    private static final boolean LOADED;
    private static final String LOAD_ERROR;

    static {
        boolean loaded;
        String error = null;
        try {
            System.loadLibrary("gkme_uinput");
            loaded = true;
        } catch (Throwable t) {
            loaded = false;
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOADED = loaded;
        LOAD_ERROR = error;
    }

    private RemoteGamepadDevice() {
    }

    public static boolean isLoaded() {
        return LOADED;
    }

    public static String loadError() {
        return LOAD_ERROR;
    }

    public static native int nativeCreate(int rumbleEnabled);

    public static native void nativeWrite(int fd, int buttons, int leftTrigger, int rightTrigger,
                                          int leftX, int leftY, int rightX, int rightY);

    public static native long nativeRumble(int fd);

    public static native void nativeDestroy(int fd);

    /** Creates a virtual keyboard, returning the uinput fd (negative represents -errno). */
    public static native int nativeCreateKeyboard();

    /**
     * Writes full keyboard state.
     *
     * @param modifiers HID modifier key bitmask (bit0=LCtrl ... bit7=RGui)
     * @param usages    HID Keyboard/Keypad usage IDs (0x04..0xFF, excluding modifiers)
     */
    public static native void nativeWriteKeyboard(int fd, int modifiers, int[] usages);

    /** Creates a virtual mouse, returning the uinput fd (negative represents -errno). */
    public static native int nativeCreateMouse();

    /**
     * Writes mouse movement and button frame.
     *
     * @param dx/dy   Relative movement
     * @param wheel   Vertical wheel (positive is scroll up)
     * @param pan     Horizontal scroll (positive is scroll right)
     * @param buttons Button mask (bit0 Left, bit1 Right, bit2 Middle, bit3 Back, bit4 Forward)
     */
    public static native void nativeWriteMouse(int fd, int dx, int dy, int wheel, int pan,
                                               int buttons);

    /** Destroys keyboard/mouse uinput device. */
    public static native void nativeDestroyInput(int fd);

    // -- UHID Backend (Direct HID emulation via /dev/uhid) --------------------

    /**
     * Creates an authentic HID gamepad via /dev/uhid, returning fd (negative represents -errno).
     *
     * @param profile 1 = DualShock 4, 2 = DualSense, 3 = Switch Pro
     */
    public static native int nativeCreateUhid(int profile, int rumbleEnabled);

    /**
     * Writes UHID gamepad state.
     *
     * @param touches Touchpad data: up to 2 points, 4 ints each: [id, x, y, active],
     *                x/y in 0..1919 / 0..942; used by DS4/DualSense, can pass null.
     */
    public static native void nativeWriteUhid(int fd, int buttons, int leftTrigger,
                                              int rightTrigger, int leftX, int leftY,
                                              int rightX, int rightY,
                                              float gyroX, float gyroY, float gyroZ,
                                              float accelX, float accelY, float accelZ,
                                              int[] touches);

    public static native long nativeRumbleUhid(int fd);

    public static native void nativeDestroyUhid(int fd);

    public static native int nativeCreateKeyboardUhid();

    public static native void nativeWriteKeyboardUhid(int fd, int modifiers, int[] usages);

    public static native int nativeCreateMouseUhid();

    public static native void nativeWriteMouseUhid(int fd, int dx, int dy, int wheel, int pan,
                                                   int buttons);

    public static native void nativeDestroyUhidInput(int fd);
}
