/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  android.content.Context
 *  android.os.Build$VERSION
 *  android.os.Process
 *  android.os.VibrationAttributes
 *  android.os.VibrationEffect
 *  android.os.Vibrator
 */
package com.apprichtap.haptic.player;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import com.apprichtap.haptic.base.b;
import com.apprichtap.haptic.base.e;
import com.apprichtap.haptic.player.IHapticEffectPerformer;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

public class d
implements IHapticEffectPerformer {
    private static final String i = "RichTapPerformer";
    public static final int j = 2;
    public static int k = -1;
    public static int l = -1;
    private Vibrator a;
    private Context b;
    private Class<?> c;
    private String d;
    private AtomicInteger e;
    private boolean f;
    private int g;
    private VibrationAttributes h;

    /*
     * WARNING - void declaration
     */
    public d(Context context) {
        void var1_1;
        d d2 = this;
        this.e = new AtomicInteger();
        this.g = 255;
        this.b = var1_1;
        d2.a = (Vibrator)var1_1.getSystemService("vibrator");
        try {
            d2.c = Class.forName("richtap.os.PhonyVibrationEffect");
        }
        catch (ClassNotFoundException classNotFoundException) {
            this.c = null;
            e.a.c(i, "failed to reflect class: \"richtap.os.PhonyVibrationEffect\"!");
        }
        if (this.c == null) {
            try {
                this.c = Class.forName("android.os.RichTapVibrationEffect");
            }
            catch (ClassNotFoundException classNotFoundException) {
                this.c = null;
                e.a.c(i, "failed to reflect class: \"android.os.RichTapVibrationEffect\"!");
            }
        }
        if (this.c == null) {
            try {
                this.c = Class.forName("android.os.VibrationEffect");
            }
            catch (ClassNotFoundException classNotFoundException) {
                e.a.c(i, "failed to reflect class: \"android.os.VibrationEffect\"!");
            }
        }
        this.b();
    }

    /*
     * Unable to fully structure code
     */
    private void b() {
        block12: {
            if (this.b == null) ** GOTO lbl32
            if (this.a == null) ** GOTO lbl32
            var1_2 = Build.VERSION.SDK_INT;
            if (var1_2 >= 26) ** GOTO lbl11
            e.a.b("RichTapPerformer", "getRichTapCoreMajorVersion, android sdk:" + var1_2);
            return;
lbl11:
            // 1 sources

            v0 = this.c.getMethod("checkIfRichTapSupport", new Class[0]);
            var0_1 = (Integer)v0.invoke(null, new Object[0]);
            e.a.a("RichTapPerformer", "getRichTapCoreMajorVersion check framework RichTap version:" + var0_1);
            if (1 != var0_1) break block12;
            return;
        }
        v1 = var0_1;
        var1_3 = "RichTapPerformer";
        v2 = new StringBuilder().append("clientCode:");
        v3 = v2.append((var0_1 & 0xFF0000) >> 16).append(" majorVersion:");
        var0_1 = (var0_1 & 65280) >> 8;
        var2_4 = v3.append(var0_1).append(" minorVersion:");
        var3_5 = v1 & 255;
        try {
            block13: {
                break block13;
lbl32:
                // 2 sources

                e.a.b("RichTapPerformer", "getRichTapCoreMajorVersion mContext or mVibrator null");
                return;
            }
            e.a.a(var1_3, var2_4.append(var3_5).toString());
            com.apprichtap.haptic.player.d.k = var0_1;
            com.apprichtap.haptic.player.d.l = var3_5;
        }
        catch (Throwable v4) {
            v4.printStackTrace();
        }
    }

    /*
     * WARNING - void declaration
     */
    private void a(int interval, int amplitude, int freq) {
        void var3_5;
        void var2_4;
        void var1_3;
        if (Build.VERSION.SDK_INT < 26) {
            e.a.b(i, "applyPatternHeParam(), The system api level is low than 26,cannot support richTap!!");
            return;
        }
        if (k <= 0) {
            e.a.b(i, "applyPatternHeParam, CORE_MAJOR_VERSION:" + k);
            return;
        }
        d d2 = objectArray;
        d d3 = d2;
        e.a.c(i, "applyPatternHeParam, interval:" + (int)var1_3 + ", amplitude:" + (int)var2_4 + ",freq:" + (int)var3_5);
        Class<?> clazz = d2.c;
        String string = "createPatternHeParameter";
        Class[] classArray = new Class[3];
        Object[] objectArray = Integer.TYPE;
        classArray[0] = objectArray;
        classArray[1] = objectArray;
        classArray[2] = objectArray;
        Method method = clazz.getMethod(string, classArray);
        Object[] objectArray2 = new Object[3];
        objectArray = objectArray2;
        int n = 0;
        objectArray[n] = (int)var1_3;
        n = 1;
        objectArray[n] = (int)var2_4;
        n = 2;
        objectArray2[n] = (int)var3_5;
        VibrationEffect vibrationEffect = (VibrationEffect)method.invoke(null, objectArray2);
        try {
            d3.a.vibrate(vibrationEffect);
        }
        catch (Throwable throwable) {
            throwable.printStackTrace();
            e.a.d(i, "The system doesn't integrate RichTap software");
        }
    }

    /*
     * WARNING - void declaration
     */
    private void a(int[] parameters, int length) {
        void var1_3;
        void var2_4;
        if (Build.VERSION.SDK_INT < 26) {
            e.a.b(i, "applyPreBakedEffect(), The system api level is low than 26,cannot support richTap!!");
            return;
        }
        if (k <= 0) {
            e.a.b(i, "applyPreBakedEffect, CORE_MAJOR_VERSION:" + k);
            return;
        }
        d d2 = this;
        d d3 = d2;
        Class<?> clazz = d2.c;
        String string = "createHapticParameter";
        Class[] classArray = new Class[2];
        classArray[0] = int[].class;
        classArray[1] = Integer.TYPE;
        Method method = clazz.getMethod(string, classArray);
        Object[] objectArray = new Object[2];
        void v7 = var2_4;
        objectArray[0] = var1_3;
        int n = 1;
        objectArray[n] = (int)v7;
        VibrationEffect vibrationEffect = (VibrationEffect)method.invoke(null, objectArray);
        try {
            d3.a.vibrate(vibrationEffect);
        }
        catch (Throwable throwable) {
            throwable.printStackTrace();
            e.a.d(i, "The system doesn't integrate RichTap software");
        }
    }

    /*
     * Exception decompiling
     */
    public static boolean a() {
        /*
         * This method has failed to decompile.  When submitting a bug report, please provide this stack trace, and (if you hold appropriate legal rights) the relevant class file.
         * 
         * org.benf.cfr.reader.util.ConfusedCFRException: Back jump on a try block [egrp 1[TRYBLOCK] [2 : 15->22)] java.lang.Throwable
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op02WithProcessedDataAndRefs.insertExceptionBlocks(Op02WithProcessedDataAndRefs.java:2283)
         *     at org.benf.cfr.reader.bytecode.CodeAnalyser.getAnalysisInner(CodeAnalyser.java:415)
         *     at org.benf.cfr.reader.bytecode.CodeAnalyser.getAnalysisOrWrapFail(CodeAnalyser.java:278)
         *     at org.benf.cfr.reader.bytecode.CodeAnalyser.getAnalysis(CodeAnalyser.java:201)
         *     at org.benf.cfr.reader.entities.attributes.AttributeCode.analyse(AttributeCode.java:94)
         *     at org.benf.cfr.reader.entities.Method.analyse(Method.java:531)
         *     at org.benf.cfr.reader.entities.ClassFile.analyseMid(ClassFile.java:1055)
         *     at org.benf.cfr.reader.entities.ClassFile.analyseTop(ClassFile.java:942)
         *     at org.benf.cfr.reader.Driver.doJarVersionTypes(Driver.java:257)
         *     at org.benf.cfr.reader.Driver.doJar(Driver.java:139)
         *     at org.benf.cfr.reader.CfrDriverImpl.analyse(CfrDriverImpl.java:76)
         *     at org.benf.cfr.reader.Main.main(Main.java:54)
         */
        throw new IllegalStateException("Decompilation failed");
    }

    /*
     * WARNING - void declaration
     */
    public void a(int[] relativeTimeArr, float[] scaleArr, int[] freqArr, boolean steepMode, int amplitude) {
        void var5_7;
        void var4_6;
        void var3_5;
        Object[] objectArray;
        int[] nArray;
        e.a.a(i, "applyEnvelope, relative times:" + Arrays.toString(nArray) + ", scales:" + Arrays.toString((float[])objectArray) + ", freqs:" + Arrays.toString((int[])var3_5) + ",steep mode:" + (boolean)var4_6 + ", amplitude:" + (int)var5_7);
        if (((d)this_).b != null && ((d)this_).a != null) {
            int n = Build.VERSION.SDK_INT;
            if (n < 26) {
                e.a.b(i, "applyEnvelope, android sdk:" + n);
                return;
            }
            if (k <= 0) {
                e.a.b(i, "applyEnvelope, mRichTapCoreMajorVersion:" + k);
                return;
            }
            for (n = 0; n < 4; ++n) {
                if (nArray[n] < 0) {
                    e.a.b(i, "applyEnvelope, relative time can not be negative");
                    return;
                }
                if (objectArray[n] < 0.0f) {
                    e.a.b(i, "applyEnvelope, scale can not be negative");
                    return;
                }
                if (var3_5[n] >= 0) continue;
                e.a.b(i, "applyEnvelope,freq must be positive");
                return;
            }
            if (var5_7 >= 0 && var5_7 <= 255) {
                nArray = Arrays.copyOfRange(nArray, 0, 4);
                int[] nArray2 = new int[objectArray.length];
                for (int i2 = 0; i2 < objectArray.length; ++i2) {
                    nArray2[i2] = (int)(objectArray[i2] * 100.0f);
                }
                d d2 = this_;
                Object this_ = Arrays.copyOfRange((int[])var3_5, 0, 4);
                Class<?> clazz = d2.c;
                String string = "createEnvelope";
                Class[] classArray = new Class[5];
                classArray[0] = int[].class;
                classArray[1] = int[].class;
                classArray[2] = int[].class;
                classArray[3] = Boolean.TYPE;
                classArray[4] = Integer.TYPE;
                Method method = clazz.getMethod(string, classArray);
                Object[] objectArray2 = new Object[5];
                objectArray = objectArray2;
                void v6 = var5_7;
                Object[] objectArray3 = objectArray;
                void v8 = var4_6;
                Object[] objectArray4 = objectArray;
                objectArray4[0] = nArray;
                objectArray4[1] = nArray2;
                objectArray[2] = this_;
                int n2 = 3;
                objectArray3[n2] = (boolean)v8;
                n2 = 4;
                objectArray2[n2] = (int)v6;
                VibrationEffect vibrationEffect = (VibrationEffect)method.invoke(null, objectArray2);
                try {
                    d2.a.vibrate(vibrationEffect);
                }
                catch (Throwable throwable) {
                    throwable.printStackTrace();
                }
                return;
            }
            e.a.b(i, "amplitude must either be DEFAULT_AMPLITUDE, or between 0 and 255 inclusive (amplitude=" + (int)var5_7 + ")");
            return;
        }
        e.a.b(i, "applyEnvelop mContext or mVibrator null");
    }

    /*
     * WARNING - void declaration
     */
    public void a(int intensity, int frequency) {
        void var2_3;
        void var1_1;
        if (Build.VERSION.SDK_INT < 26) {
            e.a.b(i, "applyPreBakedEffect(), The system api level is low than 26,cannot support richTap!!");
            return;
        }
        if (k <= 0) {
            e.a.b(i, "applyPreBakedEffect, mRichTapCoreMajorVersion:" + k);
            return;
        }
        d d2 = object;
        Object object = com.apprichtap.haptic.base.b.a((int)var1_1, (int)var2_3);
        Class<?> clazz = d2.c;
        String string = "createPatternHeWithParam";
        Class[] classArray = new Class[5];
        classArray[0] = int[].class;
        Class<Integer> clazz2 = Integer.TYPE;
        classArray[1] = clazz2;
        classArray[2] = clazz2;
        classArray[3] = clazz2;
        classArray[4] = clazz2;
        Method method = clazz.getMethod(string, classArray);
        Object[] objectArray = new Object[5];
        Object[] objectArray2 = objectArray;
        Object[] objectArray3 = objectArray;
        Object[] objectArray4 = objectArray;
        Object[] objectArray5 = objectArray;
        Object[] objectArray6 = objectArray;
        objectArray[0] = object;
        objectArray4[1] = 1;
        objectArray5[2] = 0;
        objectArray6[3] = 255;
        objectArray2[4] = 0;
        object = (VibrationEffect)method.invoke(null, objectArray3);
        try {
            d2.a.vibrate((VibrationEffect)object);
        }
        catch (Throwable throwable) {
            e.a.d(i, "The system doesn't integrate richTap software");
            throwable.printStackTrace();
        }
    }

    @Override
    public int getRichTapCoreMajorVersion() {
        return k;
    }

    /*
     * WARNING - void declaration
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    @Override
    public void start(String patternString) {
        void var5_20;
        void var2_13;
        void var1_3;
        void var2_10;
        String string = this.d;
        if (string != null) {
            int[] nArray = com.apprichtap.haptic.base.d.a(string);
        } else {
            Object var2_9 = null;
        }
        int n = 2;
        int n2 = Build.VERSION.SDK_INT;
        if (n2 < 26) {
            e.a.b(i, "start(), The system api level is low than 26,cannot support richTap!!");
            return;
        }
        int n3 = k;
        if (n3 <= 0) {
            e.a.b(i, "start(), mRichTapCoreMajorVersion:" + k);
            return;
        }
        if (23 >= n3) {
            void var1_1;
            String string2 = com.apprichtap.haptic.base.b.a((String)var1_1, true);
            n = 1;
        } else if (32 <= n3 && var2_10 != null) {
            int cfr_ignored_0 = ((void)var2_10).length;
        }
        if (var2_10 != null && 2 == ((void)var2_10).length) {
            void v0 = var2_10;
            void var2_11 = v0[0];
            void var5_18 = v0[1];
        } else {
            int n4 = Process.myPid();
            int n5 = this.e.incrementAndGet() % Integer.MAX_VALUE;
        }
        int n6 = n2;
        int n7 = n;
        int n8 = k;
        n = l;
        n2 = this.f ? 1 : 0;
        int[] nArray = com.apprichtap.haptic.base.b.a((String)var1_3, n7, n8, n, (int)var2_13, (int)var5_20, n2 != 0);
        try {
            e.a.a(i, "start() mGain:" + this.g + " raw data:" + Arrays.toString(nArray));
            Class[] classArray = new Class[5];
            classArray[0] = int[].class;
            Class<Integer> clazz = Integer.TYPE;
            classArray[1] = clazz;
            classArray[2] = clazz;
            classArray[3] = clazz;
            classArray[4] = clazz;
            VibrationEffect vibrationEffect = (VibrationEffect)this.c.getMethod("createPatternHeWithParam", classArray).invoke(null, nArray, 1, 0, this.g, 0);
            if (n6 >= 33) {
                this.a.vibrate(vibrationEffect, this.h);
                return;
            }
            this.a.vibrate(vibrationEffect);
            return;
        }
        catch (Throwable throwable) {
            e.a.d(i, "The system doesn't integrate richTap software");
            throwable.printStackTrace();
        }
    }

    @Override
    public void stop() {
        e.a.a(i, "stop()");
        this.a(0, 0, 0);
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void updateParameter(int intensity, int freq) {
        if (32 > k) {
            e.a.d(i, "not support updateHapticParam(), core version:" + k);
            return;
        }
        Object object = ((d)object2).d;
        object = object != null ? (Object)com.apprichtap.haptic.base.d.a((String)object) : null;
        if (object != null && 2 == ((Object)object).length) {
            void var2_2;
            void var1_1;
            d d2 = object2;
            e.a.a(i, "updateHapticParams, sender pid:" + (int)object[0] + ",gid:" + (object[1] & 0xFFFF0000) + ",intensity:" + (int)var1_1 + ",freq:" + (int)var2_2);
            int[] nArray = new int[7];
            Object object2 = nArray;
            object2[0] = 256;
            System.arraycopy(object, 0, object2, 1, 2);
            nArray[3] = 513;
            nArray[4] = var1_1;
            nArray[5] = 514;
            nArray[6] = var2_2;
            d2.a(nArray, 7);
            return;
        }
        e.a.b(i, "updateHapticParams, invalid sender, do nothing!");
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void setSenderIdKey(String senderIdKey) {
        void var1_1;
        e.a.a(i, "setSenderIdKey:" + (String)var1_1);
        this.d = var1_1;
    }

    @Override
    public boolean supportRealtimeAdjustment() {
        return 32 <= k;
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void swapVibrationIndex(boolean swap) {
        void var1_1;
        e.a.c(i, "swapVibrationIndex:" + (boolean)var1_1);
        this.f = var1_1;
    }

    @Override
    public void setGain(int gain) {
        int n;
        if (this.b == null) {
            e.a.d(i, "set gain null == mContext");
            return;
        }
        if (n == 0) {
            e.a.a(i, "0 == gain");
            n = 1;
        }
        this.a(0, n, 0);
        this.g = n;
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void setVibrationAttributes(VibrationAttributes attrs) {
        void var1_1;
        this.h = var1_1;
    }

    public static interface a {
        public static final int a = 1;
        public static final int b = 256;
        public static final int c = 513;
        public static final int d = 514;
        public static final int e = 769;
        public static final int f = 1;
        public static final int g = 2;
    }
}

