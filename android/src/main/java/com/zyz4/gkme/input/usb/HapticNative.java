package com.zyz4.gkme.input.usb;

import java.nio.ByteBuffer;

public final class HapticNative {

    static {
        System.loadLibrary("gkme_sdl");
    }

    private HapticNative() {
    }

    public static native boolean nativeConnectHaptics(int fd, int ifaceId, int altSetting, byte epAddr);

    public static native boolean nativeEnableHaptics();

    public static native boolean nativeSendHapticFeedback(ByteBuffer buffer, int length, float intensityGain);

    public static native boolean nativeSendNativeHapticPcm(ByteBuffer buffer, int length);

    public static native void nativeCleanupHaptics();
}
