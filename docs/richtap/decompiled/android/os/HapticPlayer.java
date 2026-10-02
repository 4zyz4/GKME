/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  android.annotation.SuppressLint
 *  android.util.Log
 */
package android.os;

import android.annotation.SuppressLint;
import android.os.DynamicEffect;
import android.util.Log;

@SuppressLint(value={"NotCloseable"})
public class HapticPlayer {
    private static final String TAG = "HapticPlayer";
    DynamicEffect mEffect;

    private HapticPlayer() {
    }

    /*
     * WARNING - void declaration
     */
    public HapticPlayer(DynamicEffect effect) {
        this();
        void var1_1;
        Log.i((String)TAG, (String)"HapticPlayer(DynamicEffect)");
        this.mEffect = var1_1;
    }

    public static boolean isAvailable() {
        return false;
    }

    public void start(int loop) {
        Log.e((String)TAG, (String)"not support Haptic player api, start with loop");
    }

    public void start(int loop, int interval, int amplitude) {
        Log.e((String)TAG, (String)"not support Haptic player api, start with loop & interval & amplitude");
    }

    public void start(int loop, int interval, int amplitude, int freq) {
        Log.e((String)TAG, (String)"not support Haptic player api, start with loop & interval & amplitude & freq");
    }

    public void updateInterval(int interval) {
        Log.e((String)TAG, (String)"not support Haptic player api, updateInterval with interval");
    }

    public void updateAmplitude(int amplitude) {
        Log.e((String)TAG, (String)"not support Haptic player api, updateAmplitude with amplitude");
    }

    public void updateFrequency(int freq) {
        Log.e((String)TAG, (String)"not support Haptic player api, updateFrequency with freq");
    }

    public void updateParameter(int interval, int amplitude, int freq) {
        Log.e((String)TAG, (String)"not support Haptic player api, updateParameter with interval/amplitude/freq");
    }

    public void stop() {
        Log.e((String)TAG, (String)"not support Haptic player api, stop");
    }
}

