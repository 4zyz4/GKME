package com.zyz4.gkme.input.usb;

import android.util.Log;

/** Minimal logging shim so the ported driver code keeps its original call sites. */
public final class LimeLog {
    private static final String TAG = "Axixi2233Usb";

    private LimeLog() {
    }

    public static void info(String message) {
        Log.i(TAG, message);
    }

    public static void warning(String message) {
        Log.w(TAG, message);
    }

    public static void severe(String message) {
        Log.e(TAG, message);
    }
}
