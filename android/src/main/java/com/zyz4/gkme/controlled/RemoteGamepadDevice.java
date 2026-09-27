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
}
