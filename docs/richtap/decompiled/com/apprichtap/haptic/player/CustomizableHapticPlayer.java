/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  android.os.Handler
 *  android.os.Handler$Callback
 *  android.os.HandlerThread
 *  android.os.Looper
 *  android.os.Message
 *  android.os.SystemClock
 *  android.os.VibrationAttributes
 */
package com.apprichtap.haptic.player;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import com.apprichtap.haptic.base.c;
import com.apprichtap.haptic.base.e;
import com.apprichtap.haptic.player.IHapticEffectPerformer;
import com.apprichtap.haptic.player.IHapticPlayer;
import com.apprichtap.haptic.player.PlayerEventCallback;
import com.apprichtap.haptic.player.a;
import com.apprichtap.haptic.sync.SyncCallback;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CustomizableHapticPlayer
implements IHapticPlayer {
    private static final String f = "Customizable-Player";
    public static final int g = 1001;
    public static final int h = 1002;
    public static final int i = 1003;
    public static final int j = 1005;
    public static final int k = 1006;
    public static final int l = 1007;
    public static final int m = 1008;
    public static final int n = 1020;
    public static final int o = 1099;
    public static final int p = 20;
    private final ExecutorService a = Executors.newFixedThreadPool(1);
    private final a b;
    private final IHapticEffectPerformer c;
    private final HandlerThread d;
    private final b e;

    public CustomizableHapticPlayer(IHapticEffectPerformer performer) {
        PlayerHandlerCallback playerHandlerCallback;
        b b2;
        HandlerThread handlerThread;
        HandlerThread handlerThread2;
        a a2;
        Object object = a2;
        this.b = new a();
        e.a.c(f, "initialize!");
        if (performer == null) {
            e.a.b(f, "CustomizableHapticPlayer() null == performer");
            object.a(2, 0);
        }
        CustomizableHapticPlayer customizableHapticPlayer = this;
        customizableHapticPlayer.c = handlerThread2;
        handlerThread2 = handlerThread;
        handlerThread2(f);
        this.d = handlerThread2;
        handlerThread.start();
        object = b2;
        handlerThread2 = handlerThread2.getLooper();
        PlayerHandlerCallback playerHandlerCallback2 = playerHandlerCallback;
        playerHandlerCallback = new PlayerHandlerCallback();
        b2 = new b((Looper)handlerThread2, playerHandlerCallback2);
        customizableHapticPlayer.e = object;
    }

    static /* synthetic */ a b(CustomizableHapticPlayer x0) {
        return x0.b;
    }

    static /* synthetic */ b c(CustomizableHapticPlayer x0) {
        return x0.e;
    }

    static /* synthetic */ ExecutorService d(CustomizableHapticPlayer x0) {
        return x0.a;
    }

    @Override
    public void release() {
        e.a.c(f, " released!");
        if (((CustomizableHapticPlayer)this).isPlaying()) {
            b b2 = ((CustomizableHapticPlayer)this).e;
            b2.a(b2.obtainMessage(1003), 0, true);
        }
        CustomizableHapticPlayer customizableHapticPlayer = this;
        customizableHapticPlayer.b.d();
        customizableHapticPlayer.b.a(1, 0);
        Object object = customizableHapticPlayer.d;
        if (object != null) {
            object.quit();
        }
        if ((object = ((CustomizableHapticPlayer)this).c) != null) {
            object.release();
        }
        if ((this = ((CustomizableHapticPlayer)this).a) != null) {
            this.shutdown();
        }
    }

    @Override
    public void pause() {
        if (6 != this.b.b()) {
            e.a.a(f, "  pause() return, not STARTED");
            return;
        }
        CustomizableHapticPlayer customizableHapticPlayer = this;
        e.a.a(f, "  pause() in, mStartPosition:" + this.b.i + "mCurrentPosition:" + this.b.l);
        b b2 = customizableHapticPlayer.e;
        b2.a(b2.obtainMessage(1003), 0, true);
        b b3 = customizableHapticPlayer.e;
        b3.a(b3.obtainMessage(1006), 0, false);
    }

    @Override
    public void start() {
        if (5 != this.b.b() && 7 != this.b.b() && 9 != this.b.b()) {
            e.a.d(f, "call start() in invalid status:" + this.b.b() + ", do nothing!");
            return;
        }
        e.a.a(f, "  start() in, mCurrentHePausePosition:" + this.b.i);
        if (9 == this.b.b()) {
            e.a.a(f, "  start() return, already COMPLETED, start from 0");
            this.b.i = 0;
        }
        CustomizableHapticPlayer customizableHapticPlayer = this;
        customizableHapticPlayer.b.a(6, 0);
        int n = customizableHapticPlayer.b.j;
        if (n > 0) {
            this.seekTo(n);
        } else {
            b b2 = this.e;
            b2.a(b2.obtainMessage(1005), 0, true);
        }
    }

    @Override
    public void stop() {
        e.a.a(f, "stop!");
        if (6 != this.b.b() && 7 != this.b.b() && 9 != this.b.b()) {
            e.a.d(f, "call stop() in invalid status:" + this.b.b() + ", do nothing!");
            return;
        }
        CustomizableHapticPlayer customizableHapticPlayer = this;
        b b2 = customizableHapticPlayer.e;
        b2.a(b2.obtainMessage(1003), 0, true);
        customizableHapticPlayer.b.d();
        customizableHapticPlayer.b.a(8, 0);
    }

    @Override
    public void reset() {
        if (this.isPlaying()) {
            b b2 = this.e;
            b2.a(b2.obtainMessage(1003), 0, true);
        }
        CustomizableHapticPlayer customizableHapticPlayer = this;
        customizableHapticPlayer.b.d();
        customizableHapticPlayer.b.a(0, 0);
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void setDataSource(String he, int amplitude, int freq, SyncCallback syncCallback) {
        void var4_4;
        void var3_3;
        void var2_2;
        void var1_1;
        this.setDataSource((String)var1_1, (int)var2_2, (int)var3_3, 0, 0, (SyncCallback)var4_4);
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void setDataSource(String he, int amplitude, int freq, int loopCount, int interval, SyncCallback syncCallback) {
        void var5_5;
        void var4_4;
        void var6_6;
        void var3_3;
        void var2_2;
        void var1_1;
        if (this.b.b() != 0) {
            e.a.d(f, "setDataSource in invalid status:" + this.b.b() + ",do nothing!");
            return;
        }
        CustomizableHapticPlayer customizableHapticPlayer = this;
        customizableHapticPlayer.b.d();
        a a2 = customizableHapticPlayer.b;
        a2.a = var1_1;
        a2.d = var2_2;
        customizableHapticPlayer.b.e = var3_3;
        if (var2_2 > 511) {
            a2.d = 511;
        } else if (var2_2 < 0) {
            a2.d = 0;
        }
        if (var3_3 > 100) {
            a2.e = 100;
        } else if (var3_3 < -100) {
            a2.e = -100;
        }
        a2.h = var6_6;
        if (var4_4 >= 0) {
            a2.c = var4_4 + true;
        }
        if (-1 == var4_4) {
            a2.c = Integer.MAX_VALUE;
        }
        if (var5_5 >= 0) {
            a2.f = var5_5;
        }
        e.a.a(f, "will change to initialized!");
        this.b.a(3, 0);
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void setDataSource(int prebakeId, int amplitude) {
        void var2_2;
        void var1_1;
        this.setDataSource(com.apprichtap.haptic.base.c.a((int)var1_1), (int)var2_2, 0, 0, 0, null);
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void setDataSource(File heFile, int amplitude, int freq, SyncCallback syncCallback) {
        void var4_4;
        void var3_3;
        void var2_2;
        void var1_1;
        this.setDataSource(com.apprichtap.haptic.base.e.a((File)var1_1), (int)var2_2, (int)var3_3, (SyncCallback)var4_4);
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void setStartOffsetMillis(int offsetMillis) {
        void var1_1;
        this.b.j = var1_1;
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void setOffsetRepeat(boolean repeat) {
        void var1_1;
        this.b.k = var1_1;
    }

    @Override
    public void prepare() {
        if (3 != this.b.b() && 8 != this.b.b()) {
            e.a.d(f, "call prepare() in invalid status:" + this.b.b() + ", do nothing!");
            return;
        }
        if (1 == com.apprichtap.haptic.base.b.e(this.b.a)) {
            this.b.a = com.apprichtap.haptic.base.b.a(this.b.a);
        }
        Object object = this.b;
        String string = ((a)object).a;
        if (string != null && ((a)object).h != null) {
            int n = com.apprichtap.haptic.base.b.d(string, 2);
            int n2 = this.b.h.getDuration();
            e.a.a(f, "prepare() heDuration:" + n + ",mediaDuration:" + n2);
            if (n2 <= 0) {
                e.a.b(f, "prepare() , SyncCallback getDuration <= 0, invalid value and may not work!");
            }
            this.b.a = com.apprichtap.haptic.base.b.a(this.b.a, n2);
        }
        object = this.b;
        ((a)object).o = ((a)object).a;
        object = com.apprichtap.haptic.base.b.d(((a)object).o);
        if (!com.apprichtap.haptic.player.a.a((a.c)object)) {
            CustomizableHapticPlayer customizableHapticPlayer = this;
            e.a.b(f, "prepare error, invalid HE");
            customizableHapticPlayer.b.d();
            customizableHapticPlayer.b.a(2, 4097);
            return;
        }
        CustomizableHapticPlayer customizableHapticPlayer = this;
        customizableHapticPlayer.b.g = object;
        customizableHapticPlayer.b.c();
        customizableHapticPlayer.c.setSenderIdKey(this.b.p);
        customizableHapticPlayer.c.setGain(255);
        customizableHapticPlayer.b.a(5, 0);
        com.apprichtap.haptic.base.e.a("prepared.he", customizableHapticPlayer.b.a);
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void seekTo(int position) {
        void var1_1;
        e.a.a(f, "  seekTo() in,  to position:" + (int)var1_1 + ",speed\uff1a" + this.b.n);
        if (!com.apprichtap.haptic.player.a.a(this.b.g)) {
            e.a.b(f, "  seekTo() return - HE invalid or prepare() not be called.");
            return;
        }
        a a2 = this.b;
        int n = (int)((float)var1_1 / a2.n);
        if (n >= 0 && n <= a2.g.getDuration()) {
            CustomizableHapticPlayer customizableHapticPlayer = this;
            b b2 = customizableHapticPlayer.e;
            b2.a(b2.obtainMessage(1003), 0, true);
            b b3 = customizableHapticPlayer.e;
            b3.a(b3.obtainMessage(1008, n, (int)var1_1), 0, false);
            return;
        }
        e.a.a(f, "  seekTo() return, position invalid, position:" + n);
    }

    @Override
    public int getCurrentPosition() {
        Object this_;
        a a2 = ((CustomizableHapticPlayer)this_).b;
        SyncCallback syncCallback = a2.h;
        if (syncCallback != null) {
            return syncCallback.getCurrentPosition();
        }
        int n = a2.b();
        if (n != 6) {
            if (n != 7) {
                if (n != 9) {
                    return 0;
                }
                return ((CustomizableHapticPlayer)this_).getDuration();
            }
            this_ = ((CustomizableHapticPlayer)this_).b;
            return (int)((float)((a)this_).i * ((a)this_).n);
        }
        this_ = ((CustomizableHapticPlayer)this_).b;
        return (int)((float)(SystemClock.elapsedRealtime() - ((a)this_).b + (long)((a)this_).i) * ((a)this_).n);
    }

    @Override
    public int getDuration() {
        String this_ = ((CustomizableHapticPlayer)((Object)this_)).b.o;
        if (this_ == null) {
            return 0;
        }
        return com.apprichtap.haptic.base.b.d(this_, 2);
    }

    @Override
    public boolean isPlaying() {
        return 6 == this.b.b();
    }

    @Override
    public void setLooping(boolean looping) {
        this.b.c = looping ? Integer.MAX_VALUE : 1;
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void registerPlayerEventCallback(PlayerEventCallback cb) {
        void var1_1;
        this.b.x = var1_1;
    }

    @Override
    public void unregisterPlayerEventCallback() {
        this.b.x = null;
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void setSpeed(float multiple) {
        void var1_1;
        if (5 != this.b.b() && 7 != this.b.b() && 6 != this.b.b() && 9 != this.b.b()) {
            e.a.b(f, "failed to setSpeedMultiple, status:" + this.b.b());
            return;
        }
        if (var1_1 == this.b.n) {
            return;
        }
        CustomizableHapticPlayer customizableHapticPlayer = this;
        int n = customizableHapticPlayer.getCurrentPosition();
        int n2 = com.apprichtap.haptic.base.b.b(this.b.o);
        double d2 = (double)n * 1.0 / (double)n2;
        e.a.a(f, "setSpeedMultiple, positionOfOriginal:" + n + ",durationOfOriginal:" + n2 + ",progress\uff1a" + d2);
        n = customizableHapticPlayer.b.b();
        if (6 == n) {
            this.pause();
        }
        CustomizableHapticPlayer customizableHapticPlayer2 = this;
        customizableHapticPlayer2.b.n = var1_1;
        customizableHapticPlayer2.b.a = com.apprichtap.haptic.base.b.a(customizableHapticPlayer2.b.o, (double)var1_1);
        com.apprichtap.haptic.base.e.a("speed_up_" + (float)var1_1, this.b.a);
        customizableHapticPlayer2.b.g = com.apprichtap.haptic.base.b.d(customizableHapticPlayer2.b.a);
        int n3 = com.apprichtap.haptic.base.b.b(customizableHapticPlayer2.b.a);
        customizableHapticPlayer2.b.i = (int)(d2 * (double)n3);
        e.a.a(f, "setSpeedUpMultiple, speedUpDuration:" + n3 + ",mStartPosition:" + this.b.i);
        if (6 == n) {
            this.start();
        }
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void updateHapticParameter(int amplitude, int freq, int interval) {
        void var3_3;
        void var2_2;
        void var1_1;
        if (amplitude >= 0 && 511 >= var1_1) {
            this.b.d = var1_1;
        } else {
            e.a.c(f, "updateHapticParameter, ignore invalid intensity:" + (int)var1_1);
        }
        if (-100 <= var2_2 && 100 >= var2_2) {
            this.b.e = var2_2;
        } else {
            e.a.c(f, "updateHapticParameter, ignore invalid freq:" + (int)var2_2);
        }
        if (var3_3 >= 0) {
            this.b.f = var3_3;
        } else {
            e.a.c(f, "updateHapticParameter, ignore invalid interval:" + (int)var3_3);
        }
    }

    @Override
    public void clearPlayingTask() {
        this.e.a();
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void setVibrationAttributes(VibrationAttributes attrs) {
        void var1_1;
        this.c.setVibrationAttributes((VibrationAttributes)var1_1);
    }

    @Override
    public float getSpeed() {
        return this.b.n;
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void setSwitching(boolean switching) {
        void var1_1;
        this.b.q = var1_1;
        this.c.swapVibrationIndex((boolean)var1_1);
    }

    @Override
    public boolean getSwitching() {
        return this.b.q;
    }

    private static class b
    extends Handler {
        /*
         * WARNING - void declaration
         */
        b(Looper looper, Handler.Callback callback) {
            super((Looper)var1_1, (Handler.Callback)var2_2);
            void var2_2;
            void var1_1;
        }

        /*
         * WARNING - void declaration
         */
        public boolean a(Message msg, int mill, boolean flush) {
            void var2_2;
            void var1_1;
            if (flush) {
                this.a();
            }
            return this.sendMessageDelayed((Message)var1_1, (long)var2_2);
        }

        public void a() {
            b b2 = this;
            b2.removeMessages(1099);
            b2.removeMessages(1007);
            b2.removeMessages(1003);
            b2.removeMessages(1001);
            b2.removeMessages(1020);
            b2.removeMessages(1002);
        }
    }

    private class PlayerHandlerCallback
    implements Handler.Callback {
        private PlayerHandlerCallback() {
        }

        /*
         * Exception decompiling
         */
        public boolean handleMessage(Message msg) {
            /*
             * This method has failed to decompile.  When submitting a bug report, please provide this stack trace, and (if you hold appropriate legal rights) the relevant class file.
             * 
             * org.benf.cfr.reader.util.ConfusedCFRException: Tried to end blocks [57[CASE]], but top level block is 5[TRYBLOCK]
             *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement.processEndingBlocks(Op04StructuredStatement.java:435)
             *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement.buildNestedBlocks(Op04StructuredStatement.java:484)
             *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op03SimpleStatement.createInitialStructuredBlock(Op03SimpleStatement.java:736)
             *     at org.benf.cfr.reader.bytecode.CodeAnalyser.getAnalysisInner(CodeAnalyser.java:850)
             *     at org.benf.cfr.reader.bytecode.CodeAnalyser.getAnalysisOrWrapFail(CodeAnalyser.java:278)
             *     at org.benf.cfr.reader.bytecode.CodeAnalyser.getAnalysis(CodeAnalyser.java:201)
             *     at org.benf.cfr.reader.entities.attributes.AttributeCode.analyse(AttributeCode.java:94)
             *     at org.benf.cfr.reader.entities.Method.analyse(Method.java:531)
             *     at org.benf.cfr.reader.entities.ClassFile.analyseMid(ClassFile.java:1055)
             *     at org.benf.cfr.reader.entities.ClassFile.analyseInnerClassesPass1(ClassFile.java:923)
             *     at org.benf.cfr.reader.entities.ClassFile.analyseMid(ClassFile.java:1035)
             *     at org.benf.cfr.reader.entities.ClassFile.analyseTop(ClassFile.java:942)
             *     at org.benf.cfr.reader.Driver.doJarVersionTypes(Driver.java:257)
             *     at org.benf.cfr.reader.Driver.doJar(Driver.java:139)
             *     at org.benf.cfr.reader.CfrDriverImpl.analyse(CfrDriverImpl.java:76)
             *     at org.benf.cfr.reader.Main.main(Main.java:54)
             */
            throw new IllegalStateException("Decompilation failed");
        }
    }

    class PerformRunnable
    implements Runnable {
        String mPattern;
        int mIntensityDelta;
        int mFreqFactor;
        int mCoreVersion;
        final /* synthetic */ CustomizableHapticPlayer this$0;

        /*
         * WARNING - void declaration
         */
        PerformRunnable(CustomizableHapticPlayer this$0, String pattern, int intensityDelta, int freqRatio, int coreVersion) {
            void var5_5;
            void var4_4;
            void var3_3;
            void var2_2;
            void var1_1;
            this.this$0 = var1_1;
            this.mPattern = var2_2;
            this.mIntensityDelta = var3_3;
            this.mFreqFactor = var4_4;
            this.mCoreVersion = var5_5;
        }

        @Override
        public void run() {
            PerformRunnable performRunnable = this;
            int n = performRunnable.mIntensityDelta;
            int n2 = performRunnable.mFreqFactor;
            int n3 = performRunnable.mCoreVersion;
            this.this$0.c.start(com.apprichtap.haptic.base.b.a(this.mPattern, n, n2, n3));
        }
    }
}

