package com.zyz4.gkme.controlled;

/**
 * native uinput 虚拟手柄的 Java 封装。只应在 Shizuku UserService 进程中使用，
 * 该进程拥有 shell/root 身份，可以打开 /dev/uinput。
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

    /** 创建虚拟键盘，返回 uinput fd（负数表示 -errno）。 */
    public static native int nativeCreateKeyboard();

    /**
     * 写入一次全量键盘状态。
     *
     * @param modifiers HID 修饰键位掩码（bit0=LCtrl … bit7=RGui）
     * @param usages    HID Keyboard/Keypad 键位用法（0x04..0xFF，修饰键除外）
     */
    public static native void nativeWriteKeyboard(int fd, int modifiers, int[] usages);

    /** 创建虚拟鼠标，返回 uinput fd（负数表示 -errno）。 */
    public static native int nativeCreateMouse();

    /**
     * 写入一次鼠标帧。按钮掩码：bit0 左键、bit1 右键、bit2 中键、bit3 后退、bit4 前进。
     *
     * @param dx/dy  相对位移
     * @param wheel  垂直滚轮（正为向上）
     * @param pan    横向滚轮（正为向右）
     */
    public static native void nativeWriteMouse(int fd, int dx, int dy, int wheel, int pan,
                                               int buttons);

    /** 销毁键盘/鼠标 uinput 设备（与手柄的 nativeDestroy 区分）。 */
    public static native void nativeDestroyInput(int fd);
}
