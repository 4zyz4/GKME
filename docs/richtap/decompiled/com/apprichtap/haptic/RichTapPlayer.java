/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  android.content.Context
 *  android.os.Build
 *  android.os.Build$VERSION
 *  android.os.VibrationAttributes
 *  android.os.VibrationEffect
 *  android.os.Vibrator
 */
package com.apprichtap.haptic;

import android.content.Context;
import android.os.Build;
import android.os.HapticPlayer;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import com.apprichtap.haptic.base.b;
import com.apprichtap.haptic.base.c;
import com.apprichtap.haptic.base.e;
import com.apprichtap.haptic.player.IHapticEffectPerformer;
import com.apprichtap.haptic.player.IHapticPlayer;
import com.apprichtap.haptic.player.PlayerEventCallback;
import com.apprichtap.haptic.player.d;
import com.apprichtap.haptic.sync.SyncCallback;
import java.io.File;
import java.util.ArrayList;

public class RichTapPlayer
implements IHapticPlayer {
    private static final String TAG = "RichTapPlayer";
    private Context mContext;
    private Vibrator mVibrator;
    private IHapticPlayer mPlayer;
    private d mRichTapPerformer;

    /*
     * WARNING - void declaration
     */
    private RichTapPlayer(IHapticEffectPerformer performer) {
        void var1_1;
        this.mPlayer = IHapticPlayer.create((IHapticEffectPerformer)var1_1);
    }

    /*
     * WARNING - void declaration
     */
    private RichTapPlayer(Context context, int playerType) {
        d d2;
        Object object;
        RichTapPlayer richTapPlayer = this;
        this.mContext = object.getApplicationContext();
        richTapPlayer.mVibrator = (Vibrator)object.getSystemService("vibrator");
        object = d2;
        richTapPlayer.mRichTapPerformer = new d(this.mContext);
        if (playerType != 0) {
            void var2_2;
            if (var2_2 != true) {
                if (var2_2 != 2) {
                    e.a.d(TAG, "unknown player type:" + (int)var2_2);
                } else {
                    RichTapPlayer richTapPlayer2;
                    RichTapPlayer richTapPlayer3 = richTapPlayer2;
                    richTapPlayer2 = new RichTapPlayer((IHapticEffectPerformer)object);
                    this.mPlayer = richTapPlayer3;
                }
            } else {
                com.apprichtap.haptic.player.e e2;
                RichTapPlayer richTapPlayer4;
                object = richTapPlayer4;
                com.apprichtap.haptic.player.e e3 = e2;
                e2 = new com.apprichtap.haptic.player.e();
                richTapPlayer4 = new RichTapPlayer(e3);
                this.mPlayer = object;
            }
        } else {
            this.mPlayer = new RichTapPlayer(new com.apprichtap.haptic.player.b(this.mVibrator));
        }
        e.d = this.mContext;
    }

    /*
     * WARNING - void declaration
     */
    public static RichTapPlayer create(Context context, int playerType) {
        void var1_1;
        Context context2;
        if (Build.VERSION.SDK_INT < 26) {
            e.a.b(TAG, "OS is lower than Android O, NOT SUPPORTED!");
            return null;
        }
        if (context2 == null) {
            e.a.b(TAG, "context == null");
            return null;
        }
        if (!RichTapPlayer.isPlayerTypeAvailable((int)var1_1)) {
            e.a.b(TAG, "specified player type not available!");
            return null;
        }
        return new RichTapPlayer(context2, (int)var1_1);
    }

    public static RichTapPlayer create(Context context) {
        Context context2;
        if (RichTapPlayer.isPlayerTypeAvailable(2)) {
            return RichTapPlayer.create(context2, 2);
        }
        if (RichTapPlayer.isPlayerTypeAvailable(1)) {
            return RichTapPlayer.create(context2, 1);
        }
        return RichTapPlayer.create(context2, 0);
    }

    public static RichTapPlayer create(IHapticEffectPerformer performer) {
        IHapticEffectPerformer iHapticEffectPerformer;
        return new RichTapPlayer(iHapticEffectPerformer);
    }

    /*
     * Unable to fully structure code
     */
    public static boolean isPlayerTypeAvailable(int playerType) {
        block5: {
            block4: {
                if (playerType == 0) break block5;
                if (var0 == 1) ** GOTO lbl7
                if (var0 != 2) {
                    return false;
                }
                return d.a();
lbl7:
                // 1 sources

                if (!HapticPlayer.isAvailable()) break block4;
                try {
                    if (RichTapPlayer.isSamsungDevice()) break block4;
                    v0 = true;
                }
                catch (Throwable v1) {
                    v1.printStackTrace();
                    return false;
                }
            }
            v0 = false;
            return v0;
        }
        return true;
    }

    private static boolean isSamsungDevice() {
        String string = Build.BRAND;
        String string2 = Build.MANUFACTURER;
        e.a.a(TAG, "Model: " + Build.MODEL + "\nDevice: " + Build.DEVICE + "\nID: " + Build.ID + "\nTYPE: " + Build.TYPE + "\nBRAND: " + string + "\nMANUFACTURER: " + string2 + "\n");
        return "samsung".equalsIgnoreCase(string2) || "samsung".equalsIgnoreCase(string);
    }

    public static int getRichTapCoreMajorVersion(Context context) {
        if (context == null) {
            e.a.b(TAG, "getRichTapCoreMajorVersion, null == context");
            return -1;
        }
        try {
            Context context2;
            return new d(context2).getRichTapCoreMajorVersion();
        }
        catch (Throwable throwable) {
            throwable.printStackTrace();
            return -1;
        }
    }

    public static String getPrebakedEffectNameById(int id) {
        return c.b(id);
    }

    /*
     * WARNING - void declaration
     */
    @Deprecated
    public static void convertM2VHeToWaveformParams(String heString, ArrayList<Long> timings, ArrayList<Integer> amplitudes) {
        try {
            void var2_2;
            void var1_1;
            b.b(heString, (ArrayList<Long>)var1_1, (ArrayList<Integer>)var2_2);
        }
        catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    /*
     * WARNING - void declaration
     */
    @Deprecated
    public static void convertM2VHeToWaveformParams(File heFile, ArrayList<Long> timings, ArrayList<Integer> amplitudes) {
        try {
            void var2_2;
            void var1_1;
            b.b(e.a(heFile), (ArrayList<Long>)var1_1, (ArrayList<Integer>)var2_2);
        }
        catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    @Override
    public void reset() {
        IHapticPlayer this_ = ((RichTapPlayer)this_).mPlayer;
        if (this_ == null) {
            e.a.b(TAG, "null == mPlayer!");
            return;
        }
        this_.reset();
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void setDataSource(String heString, int amplitude, int freq, SyncCallback syncCallback) {
        void var4_4;
        void var3_3;
        void var2_2;
        void var1_1;
        IHapticPlayer this_ = ((RichTapPlayer)this_).mPlayer;
        if (this_ == null) {
            e.a.b(TAG, "null == mPlayer!");
            return;
        }
        this_.setDataSource((String)var1_1, (int)var2_2, (int)var3_3, (SyncCallback)var4_4);
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void setDataSource(File file, int amplitude, int freq, SyncCallback syncCallback) {
        void var4_4;
        void var3_3;
        void var2_2;
        void var1_1;
        IHapticPlayer this_ = ((RichTapPlayer)this_).mPlayer;
        if (this_ == null) {
            e.a.b(TAG, "null == mPlayer!");
            return;
        }
        this_.setDataSource((File)var1_1, (int)var2_2, (int)var3_3, (SyncCallback)var4_4);
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void setDataSource(String heString, int amplitude, int freq, int loopCount, int interval, SyncCallback syncCallback) {
        void var6_6;
        void var5_5;
        void var4_4;
        void var3_3;
        void var2_2;
        void var1_1;
        IHapticPlayer this_ = ((RichTapPlayer)this_).mPlayer;
        if (this_ == null) {
            e.a.b(TAG, "null == mPlayer!");
            return;
        }
        this_.setDataSource((String)var1_1, (int)var2_2, (int)var3_3, (int)var4_4, (int)var5_5, (SyncCallback)var6_6);
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void setDataSource(int prebakeId, int amplitude) {
        void var2_2;
        void var1_1;
        IHapticPlayer this_ = ((RichTapPlayer)this_).mPlayer;
        if (this_ == null) {
            e.a.b(TAG, "null == mPlayer!");
            return;
        }
        this_.setDataSource((int)var1_1, (int)var2_2);
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void setStartOffsetMillis(int offsetMillis) {
        void var1_1;
        this.mPlayer.setStartOffsetMillis((int)var1_1);
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void setOffsetRepeat(boolean repeat) {
        void var1_1;
        this.mPlayer.setOffsetRepeat((boolean)var1_1);
    }

    @Override
    public void release() {
        IHapticPlayer iHapticPlayer = this.mPlayer;
        if (iHapticPlayer != null) {
            iHapticPlayer.release();
        }
        this.mPlayer = null;
    }

    @Override
    public void prepare() {
        IHapticPlayer this_ = ((RichTapPlayer)this_).mPlayer;
        if (this_ == null) {
            e.a.b(TAG, "null == mPlayer!");
            return;
        }
        this_.prepare();
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void seekTo(int millSeconds) {
        void var1_1;
        IHapticPlayer this_ = ((RichTapPlayer)this_).mPlayer;
        if (this_ == null) {
            e.a.b(TAG, "null == mPlayer!");
            return;
        }
        this_.seekTo((int)var1_1);
    }

    @Override
    public void start() {
        IHapticPlayer this_ = ((RichTapPlayer)this_).mPlayer;
        if (this_ == null) {
            e.a.b(TAG, "null == mPlayer!");
            return;
        }
        this_.start();
    }

    @Override
    public void stop() {
        e.a.a(TAG, "stop!");
        IHapticPlayer this_ = ((RichTapPlayer)this_).mPlayer;
        if (this_ == null) {
            e.a.b(TAG, "null == mPlayer!");
            return;
        }
        this_.stop();
    }

    @Override
    public void pause() {
        IHapticPlayer this_ = ((RichTapPlayer)this_).mPlayer;
        if (this_ == null) {
            e.a.b(TAG, "null == mPlayer!");
            return;
        }
        this_.pause();
    }

    @Override
    public int getCurrentPosition() {
        IHapticPlayer this_ = ((RichTapPlayer)this_).mPlayer;
        if (this_ == null) {
            e.a.b(TAG, "null == mPlayer!");
            return 0;
        }
        return this_.getCurrentPosition();
    }

    @Override
    public int getDuration() {
        IHapticPlayer this_ = ((RichTapPlayer)this_).mPlayer;
        if (this_ == null) {
            e.a.b(TAG, "null == mPlayer!");
            return 0;
        }
        return this_.getDuration();
    }

    @Override
    public boolean isPlaying() {
        IHapticPlayer this_ = ((RichTapPlayer)this_).mPlayer;
        if (this_ == null) {
            e.a.b(TAG, "null == mPlayer!");
            return false;
        }
        return this_.isPlaying();
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void setLooping(boolean looping) {
        void var1_1;
        IHapticPlayer this_ = ((RichTapPlayer)this_).mPlayer;
        if (this_ == null) {
            e.a.b(TAG, "null == mPlayer!");
            return;
        }
        this_.setLooping((boolean)var1_1);
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void registerPlayerEventCallback(PlayerEventCallback cb) {
        void var1_1;
        IHapticPlayer this_ = ((RichTapPlayer)this_).mPlayer;
        if (this_ == null) {
            e.a.b(TAG, "null == mPlayer!");
            return;
        }
        this_.registerPlayerEventCallback((PlayerEventCallback)var1_1);
    }

    @Override
    public void unregisterPlayerEventCallback() {
        IHapticPlayer this_ = ((RichTapPlayer)this_).mPlayer;
        if (this_ == null) {
            e.a.b(TAG, "null == mPlayer!");
            return;
        }
        this_.unregisterPlayerEventCallback();
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void setSpeed(float multiple) {
        void var1_1;
        if (3.0f < var1_1 || 0.5f > var1_1) {
            e.a.b(TAG, "invalid speed multiple!");
        }
        if ((this = ((RichTapPlayer)this).mPlayer) == null) {
            e.a.b(TAG, "null == mPlayer!");
            return;
        }
        try {
            this.setSpeed((float)var1_1);
        }
        catch (Throwable throwable) {
            throwable.printStackTrace();
            e.a.b(TAG, "fail to setSpeed");
        }
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void setSwitching(boolean switching) {
        void var1_1;
        IHapticPlayer this_ = ((RichTapPlayer)this_).mPlayer;
        if (this_ == null) {
            e.a.b(TAG, "null == mPlayer!");
            return;
        }
        this_.setSwitching((boolean)var1_1);
    }

    @Override
    public boolean getSwitching() {
        IHapticPlayer this_ = ((RichTapPlayer)this_).mPlayer;
        return this_ != null ? this_.getSwitching() : false;
    }

    @Override
    public float getSpeed() {
        IHapticPlayer this_ = ((RichTapPlayer)this_).mPlayer;
        return this_ != null ? this_.getSpeed() : 1.0f;
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void updateHapticParameter(int amplitude, int freq, int interval) {
        void var3_3;
        void var2_2;
        void var1_1;
        e.a.a(TAG, "updateHapticParameter amplitude,freq,interval:" + (int)var1_1 + "," + (int)var2_2 + "," + (int)var3_3);
        IHapticPlayer this_ = ((RichTapPlayer)this_).mPlayer;
        if (this_ == null) {
            e.a.b(TAG, "null == mPlayer!");
            return;
        }
        try {
            this_.updateHapticParameter((int)var1_1, (int)var2_2, (int)var3_3);
        }
        catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    @Override
    public void clearPlayingTask() {
        e.a.a(TAG, "clearPlayingTask()!");
        IHapticPlayer this_ = ((RichTapPlayer)this_).mPlayer;
        if (this_ != null) {
            try {
                this_.clearPlayingTask();
            }
            catch (Throwable throwable) {
                throwable.printStackTrace();
            }
        }
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void setVibrationAttributes(VibrationAttributes attrs) {
        void var1_1;
        this.mPlayer.setVibrationAttributes((VibrationAttributes)var1_1);
    }

    public void playExtPrebakedEffectByHal(int intensity, int freq) {
        int n;
        int n2;
        if (!RichTapPlayer.isPlayerTypeAvailable(2)) {
            if (this.mVibrator == null) {
                return;
            }
            int n3 = (int)((double)n2 * 1.0 / 511.0 * 100.0);
            n2 = b.c(n3, n);
            n = n3 * 255 / 100;
            this.mVibrator.cancel();
            if (Build.VERSION.SDK_INT >= 26) {
                this.mVibrator.vibrate(VibrationEffect.createOneShot((long)n2, (int)Math.max(0, Math.min(n, 255))));
            } else {
                this.mVibrator.vibrate((long)n2);
            }
            return;
        }
        if (this.mRichTapPerformer == null) {
            e.a.b(TAG, "null == mRichTapPerformer");
        }
        d d2 = this.mRichTapPerformer;
        try {
            d2.a((int)((double)n2 * 1.0 / 255.0 * 100.0), n);
        }
        catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    /*
     * WARNING - void declaration
     */
    public void playEnvelope(int[] relativeTimeArr, float[] scaleArr, int[] freqArr, boolean steepMode, int amplitude) {
        if (this.mRichTapPerformer == null) {
            e.a.b(TAG, "null == mRichTapPerformer");
        }
        try {
            void var5_5;
            void var4_4;
            void var3_3;
            void var2_2;
            void var1_1;
            this.mRichTapPerformer.a((int[])var1_1, (float[])var2_2, (int[])var3_3, (boolean)var4_4, (int)var5_5);
        }
        catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    /*
     * WARNING - void declaration
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    public void setGain(int gain) {
        try {
            void var1_1;
            e.a.a(TAG, "setGain:" + (int)var1_1);
            d this_ = ((RichTapPlayer)((Object)this_)).mRichTapPerformer;
            if (this_ == null) {
                e.a.d(TAG, "null == mRichTapPerformer!");
                return;
            }
            this_.setGain((int)var1_1);
            return;
        }
        catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }
}

