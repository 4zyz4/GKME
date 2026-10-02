/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  android.os.Build$VERSION
 */
package com.apprichtap.haptic.player;

import android.os.Build;
import android.os.DynamicEffect;
import android.os.HapticPlayer;
import com.apprichtap.haptic.base.b;
import com.apprichtap.haptic.base.e;
import com.apprichtap.haptic.player.IHapticEffectPerformer;

public class e
implements IHapticEffectPerformer {
    static String b = "TencentPerformer";
    HapticPlayer a;

    @Override
    public void start(String patternString) {
        DynamicEffect dynamicEffect;
        if (Build.VERSION.SDK_INT < 26) {
            e.a.b(b, "start(), The system api level is low than 26,cannot support richTap!!");
            return;
        }
        dynamicEffect = DynamicEffect.create(com.apprichtap.haptic.base.b.a((String)((Object)dynamicEffect), true));
        this.a = new HapticPlayer(dynamicEffect);
        try {
            this.a.start(1, 0, 255);
        }
        catch (NoSuchMethodError noSuchMethodError) {
            try {
                e.a.d(b, "no method HapticPlayer.start(loop, interval, amplitude), in TIMED_VIBRATION");
                this.a.start(1);
            }
            catch (Throwable throwable) {
                throwable.printStackTrace();
            }
        }
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    @Override
    public void stop() {
        e.a.a(b, "stop!");
        try {
            HapticPlayer this_ = ((e)((Object)this_)).a;
            if (this_ == null) {
                e.a.b(b, "stop(), HapticsPlayer is null");
                return;
            }
            this_.stop();
            return;
        }
        catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void updateParameter(int intensity, int freq) {
        void var1_1;
        HapticPlayer hapticPlayer = this.a;
        if (hapticPlayer == null) {
            e.a.b(b, "updateParameter, HapticsPlayer is null");
            return;
        }
        try {
            void var2_2;
            hapticPlayer.updateParameter(0, (int)((float)var1_1 / 100.0f * 255.0f), (int)var2_2);
        }
        catch (Throwable throwable) {
            e.a.b(b, "no method HapticPlayer.updateParameter(interval, amplitude, freq), in sendLoopParameter(int amplitude, int interval, int freq)");
            throwable.printStackTrace();
            HapticPlayer hapticPlayer2 = this.a;
            try {
                hapticPlayer2.updateAmplitude((int)((float)var1_1 / 100.0f * 255.0f));
            }
            catch (Throwable throwable2) {
                e.a.b(b, "no updateAmplitude(amplitude) method");
                throwable2.printStackTrace();
            }
        }
    }
}

