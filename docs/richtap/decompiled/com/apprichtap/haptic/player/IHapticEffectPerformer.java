/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  android.os.VibrationAttributes
 */
package com.apprichtap.haptic.player;

import android.os.VibrationAttributes;
import com.apprichtap.haptic.base.b;
import com.apprichtap.haptic.base.e;

public interface IHapticEffectPerformer {
    public static final String TAG = "IHapticEffectPerformer";

    /*
     * WARNING - void declaration
     */
    public static int[] convertHEStringToIntArray(String stringHE, int versionHE, int majorVersionOfRichTapCore, int minorVersionOfRichTapCore, int pid, int sid, boolean swapVibrationIndex) {
        void var6_6;
        void var5_5;
        void var4_4;
        void var3_3;
        void var2_2;
        void var1_1;
        return b.a(stringHE, (int)var1_1, (int)var2_2, (int)var3_3, (int)var4_4, (int)var5_5, (boolean)var6_6);
    }

    default public void start(String stringPattern) {
        e.a.d(TAG, "default start(String)");
    }

    default public void stop() {
        e.a.d(TAG, "default stop()");
    }

    default public void updateParameter(int intensity, int freq) {
        e.a.d(TAG, "default updateParameter(int, int)");
    }

    default public void setSenderIdKey(String senderIdKey) {
        e.a.d(TAG, "default setSenderId(int[])");
    }

    default public void swapVibrationIndex(boolean swap) {
        e.a.d(TAG, "default ");
    }

    default public boolean supportRealtimeAdjustment() {
        e.a.d(TAG, "default supportRealtimeAdjustment()");
        return false;
    }

    default public int getRichTapCoreMajorVersion() {
        e.a.d(TAG, "default getRichTapCoreMajorVersion");
        return -1;
    }

    default public void setGain(int gain) {
        e.a.d(TAG, "default setGain");
    }

    default public void setVibrationAttributes(VibrationAttributes attrs) {
        e.a.d(TAG, "default setVibrationAttributes");
    }

    default public void release() {
    }
}

