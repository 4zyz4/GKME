/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  android.annotation.SuppressLint
 *  android.content.Context
 *  android.os.Build
 *  android.os.Build$VERSION
 *  android.os.VibrationAttributes
 *  android.os.VibrationEffect
 *  android.os.Vibrator
 *  android.util.Log
 */
package com.apprichtap.haptic;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import com.apprichtap.haptic.RichTapPlayer;
import com.apprichtap.haptic.base.ApiInfo;
import com.apprichtap.haptic.base.c;
import com.apprichtap.haptic.base.e;
import com.apprichtap.haptic.player.PlayerEventCallback;
import com.apprichtap.haptic.sync.SyncCallback;
import com.richtap.sdk.network.RTAPIService;
import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RichTapUtils
extends ApiInfo {
    private static final String TAG = "RichTapUtils";
    @SuppressLint(value={"StaticFieldLeak"})
    private static RichTapUtils sInstance;
    private Context mContext;
    private Vibrator mVibrator;
    private int mPlayerType = -1;
    private RichTapPlayer mPlayer;
    private com.apprichtap.haptic.player.c mPlayerObjectPool;
    private int mRichTapCoreMajorVersion = -1;
    private ExecutorService mThreadExecutor = Executors.newSingleThreadExecutor();

    @SuppressLint(value={"PrivateApi"})
    private RichTapUtils() {
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     * Enabled force condition propagation
     * Lifted jumps to return sites
     */
    public static RichTapUtils getInstance() {
        if (Build.VERSION.SDK_INT < 26) {
            e.a.b(TAG, "OS is lower than Android O, NOT SUPPORTED!");
            return null;
        }
        if (sInstance != null) return sInstance;
        Class<RichTapUtils> clazz = RichTapUtils.class;
        synchronized (RichTapUtils.class) {
            if (sInstance != null) return sInstance;
            sInstance = new RichTapUtils();
            // ** MonitorExit[var0] (shouldn't be in output)
            return sInstance;
        }
    }

    public static long getDuration(String json) {
        String string = json;
        return com.apprichtap.haptic.base.b.d(string, com.apprichtap.haptic.base.b.e(string));
    }

    public static long getDuration(int id) {
        return RichTapUtils.getDuration(c.a(id));
    }

    @Deprecated
    public static String getPrebakedEffectNameById(int id) {
        return RichTapPlayer.getPrebakedEffectNameById(id);
    }

    @Deprecated
    public static void enableDebugLog(boolean enable) {
        com.apprichtap.haptic.base.e.a(enable);
    }

    /*
     * WARNING - void declaration
     */
    private static void invoke(String method, Class<?> paramType, Object ... params) {
        Object object;
        Class<RTAPIService> clazz = RTAPIService.class;
        Class<RTAPIService> clazz2 = RTAPIService.class;
        String string = object;
        Method method2 = clazz.getDeclaredMethod("create", new Class[0]);
        object = method2.invoke(null, new Object[0]);
        try {
            void var2_3;
            void var1_2;
            clazz2.getDeclaredMethod(string, new Class[]{var1_2}).invoke(object, (Object[])var2_3);
        }
        catch (Throwable throwable) {
            Log.d((String)"API_REPO", (String)("Error: " + throwable.getMessage()));
        }
    }

    /*
     * WARNING - void declaration
     */
    static /* synthetic */ com.apprichtap.haptic.player.c access$202(RichTapUtils x0, com.apprichtap.haptic.player.c x1) {
        void var1_1;
        var0.mPlayerObjectPool = var1_1;
        return x1;
    }

    /*
     * WARNING - void declaration
     */
    static /* synthetic */ RichTapPlayer access$302(RichTapUtils x0, RichTapPlayer x1) {
        void var1_1;
        var0.mPlayer = var1_1;
        return x1;
    }

    /*
     * WARNING - void declaration
     */
    static /* synthetic */ int access$102(RichTapUtils x0, int x1) {
        void var1_1;
        var0.mPlayerType = var1_1;
        return x1;
    }

    /*
     * Unable to fully structure code
     */
    public RichTapUtils init(Context context) {
        block15: {
            block14: {
                block16: {
                    if (context == null) break block15;
                    var2_2 = this.mPlayer;
                    if (var2_2 == null) ** GOTO lbl9
                    var2_2.stop();
                    this.mPlayer.release();
                    this.mPlayer = null;
lbl9:
                    // 2 sources

                    if ((var2_2 = this.mPlayerObjectPool) == null) ** GOTO lbl12
                    var2_2.a();
lbl12:
                    // 2 sources

                    v0 = var1_1.getApplicationContext();
                    this.mContext = v0;
                    if (v0 != null) ** GOTO lbl19
                    e.a.d("RichTapUtils", "fail to get application context!");
                    this.mContext = var1_1;
lbl19:
                    // 2 sources

                    this.mVibrator = (Vibrator)this.mContext.getSystemService("vibrator");
                    this.mRichTapCoreMajorVersion = this.getRichTapCoreMajorVersion();
                    if (!RichTapPlayer.isPlayerTypeAvailable(2)) ** GOTO lbl34
                    var2_3 = this.mRichTapCoreMajorVersion;
                    if (32 > var2_3) ** GOTO lbl30
                    var3_5 = v1;
                    v1 = new com.apprichtap.haptic.player.c(this.mContext, var2_3);
                    this.mPlayerObjectPool = var3_5;
                    break block16;
lbl30:
                    // 1 sources

                    this.mPlayer = RichTapPlayer.create(this.mContext, 2);
                }
                this.mPlayerType = 2;
                break block14;
lbl34:
                // 1 sources

                if (!RichTapPlayer.isPlayerTypeAvailable(1)) ** GOTO lbl39
                this.mPlayer = RichTapPlayer.create(this.mContext, 1);
                this.mPlayerType = 1;
                break block14;
lbl39:
                // 1 sources

                this.mPlayer = RichTapPlayer.create(this.mContext, 0);
                this.mPlayerType = 0;
            }
            var2_4 = v2;
            new HashMap<String, String>().put("applicationName", com.apprichtap.haptic.base.a.a(var1_1.getPackageName()));
            v3 = Build.MANUFACTURER + "," + Build.MODEL;
            var1_1 = "deviceModel";
            var2_4.put(var1_1, com.apprichtap.haptic.base.a.a(v3));
            try {
                RichTapUtils.invoke("track", Map.class, new Object[]{var2_4});
            }
            catch (Throwable v4) {
                v4.printStackTrace();
            }
            e.a.c("RichTapUtils", "init , sdk version:" + ApiInfo.VERSION_NAME + " versionCode:" + ApiInfo.VERSION_CODE + ", RichTap Core Major Version:" + this.mRichTapCoreMajorVersion);
            return RichTapUtils.sInstance;
        }
        throw new IllegalArgumentException("context shouldn't be null");
    }

    /*
     * WARNING - void declaration
     */
    public void registerPlayerEventCallback(PlayerEventCallback callback) {
        void var1_1;
        RichTapPlayer richTapPlayer = ((RichTapUtils)this).mPlayer;
        if (richTapPlayer != null && var1_1 != null) {
            richTapPlayer.registerPlayerEventCallback((PlayerEventCallback)var1_1);
        }
        if ((this = ((RichTapUtils)this).mPlayerObjectPool) != null) {
            ((com.apprichtap.haptic.player.c)this).a((PlayerEventCallback)var1_1);
        }
    }

    /*
     * Unable to fully structure code
     */
    public void quit() {
        e.a.a("RichTapUtils", "quit()");
        var1_1 = this.mThreadExecutor;
        if (var1_1 == null) ** GOTO lbl7
        var1_1.shutdown();
lbl7:
        // 2 sources

        if ((var1_1 = this.mVibrator) == null) ** GOTO lbl10
        var1_1.cancel();
lbl10:
        // 2 sources

        if ((var1_1 = this.mPlayer) == null) ** GOTO lbl16
        v0 = this;
        var1_1.stop();
        v0.mPlayer.unregisterPlayerEventCallback();
        v0.mPlayer.release();
lbl16:
        // 2 sources

        if ((var1_1 = this.mPlayerObjectPool) == null) ** GOTO lbl19
        try {
            var1_1.a();
lbl19:
            // 2 sources

            RichTapUtils.sInstance = null;
            this.mContext = null;
            RichTapUtils.invoke("exit", Void.class, new Object[0]);
        }
        catch (Throwable v1) {
            v1.printStackTrace();
        }
    }

    /*
     * WARNING - void declaration
     */
    public void playHaptic(String pattern, int loop, int interval, int amplitude, int freq, SyncCallback callback) {
        if (this.mContext != null && sInstance != null) {
            void var6_6;
            void var5_5;
            void var4_4;
            void var3_3;
            void var2_2;
            void var1_1;
            this.playHaptic((String)var1_1, (int)var2_2, (int)var3_3, (int)var4_4, (int)var5_5, 0, true, (SyncCallback)var6_6, null);
            return;
        }
        e.a.d(TAG, "Not initialized!");
    }

    /*
     * WARNING - void declaration
     */
    public void playHaptic(String pattern, int loop, int interval, int amplitude, int freq, int offsetMillis, SyncCallback callback) {
        void var7_7;
        void var6_6;
        void var5_5;
        void var4_4;
        void var3_3;
        void var2_2;
        void var1_1;
        this.playHaptic((String)var1_1, (int)var2_2, (int)var3_3, (int)var4_4, (int)var5_5, (int)var6_6, true, (SyncCallback)var7_7, null);
    }

    /*
     * WARNING - void declaration
     */
    public void playHaptic(String pattern, int loop, int interval, int amplitude, int freq, int offsetMillis, boolean offsetRepeat, SyncCallback callback) {
        void var8_8;
        void var7_7;
        void var6_6;
        void var5_5;
        void var4_4;
        void var3_3;
        void var2_2;
        void var1_1;
        this.playHaptic((String)var1_1, (int)var2_2, (int)var3_3, (int)var4_4, (int)var5_5, (int)var6_6, (boolean)var7_7, (SyncCallback)var8_8, null);
    }

    /*
     * WARNING - void declaration
     */
    public void playHaptic(String pattern, int loop, int interval, int amplitude, int freq, SyncCallback callback, VibrationAttributes attrs) {
        void var7_7;
        void var6_6;
        void var5_5;
        void var4_4;
        void var3_3;
        void var2_2;
        void var1_1;
        this.playHaptic((String)var1_1, (int)var2_2, (int)var3_3, (int)var4_4, (int)var5_5, 0, true, (SyncCallback)var6_6, (VibrationAttributes)var7_7);
    }

    /*
     * WARNING - void declaration
     */
    public void playHaptic(String pattern, int loop, int interval, int amplitude, int freq, int offsetMillis, SyncCallback callback, VibrationAttributes attrs) {
        void var8_8;
        void var7_7;
        void var6_6;
        void var5_5;
        void var4_4;
        void var3_3;
        void var2_2;
        void var1_1;
        this.playHaptic((String)var1_1, (int)var2_2, (int)var3_3, (int)var4_4, (int)var5_5, (int)var6_6, true, (SyncCallback)var7_7, (VibrationAttributes)var8_8);
    }

    /*
     * WARNING - void declaration
     */
    public void playHaptic(String pattern, int loop, int interval, int amplitude, int freq, int offsetMillis, boolean offsetRepeat, SyncCallback callback, VibrationAttributes attrs) {
        void var1_1;
        if (pattern != null && !var1_1.isEmpty()) {
            void var7_7;
            void var6_6;
            void var9_9;
            void var8_8;
            void var5_5;
            void var4_4;
            void var3_3;
            void var2_2;
            if (com.apprichtap.haptic.base.e.b()) {
                String string = TAG;
                StringBuilder stringBuilder = new StringBuilder().append("playHaptic: loop:").append((int)var2_2).append(",interval:").append((int)var3_3).append(",amplitude:").append((int)var4_4).append(",freq:").append((int)var5_5).append(",callback is null:");
                boolean bl = var8_8 == null;
                stringBuilder = stringBuilder.append(bl).append(",attrs:");
                String string2 = var9_9 == null ? "null" : var9_9.toString();
                e.a.a(string, stringBuilder.append(string2).toString());
            }
            if (this.mThreadExecutor.isShutdown()) {
                e.a.d(TAG, "executor down!");
                return;
            }
            this.mThreadExecutor.execute(new Runnable(){
                final /* synthetic */ String a;
                final /* synthetic */ int b;
                final /* synthetic */ int c;
                final /* synthetic */ int d;
                final /* synthetic */ int e;
                final /* synthetic */ int f;
                final /* synthetic */ boolean g;
                final /* synthetic */ SyncCallback h;
                final /* synthetic */ VibrationAttributes i;
                final /* synthetic */ RichTapUtils j;
                {
                    void var1_1;
                    this.j = var1_1;
                    this.a = string;
                    this.b = n;
                    this.c = n2;
                    this.d = n3;
                    this.e = n4;
                    this.f = n5;
                    this.g = bl;
                    this.h = syncCallback;
                    this.i = vibrationAttributes;
                }

                @Override
                public void run() {
                    block6: {
                        block5: {
                            if (32 > this.j.mRichTapCoreMajorVersion) break block5;
                            if (2 != this.j.mPlayerType) break block5;
                            this.j.mPlayerObjectPool.a(this.a, this.b, this.c, this.d, this.e, this.f, this.g, this.h, this.i);
                            break block6;
                        }
                        try {
                            this.j.mPlayer.reset();
                            this.j.mPlayer.setVibrationAttributes(this.i);
                            this.j.mPlayer.setDataSource(this.a, this.d, this.e, this.b, this.c, this.h);
                            this.j.mPlayer.setStartOffsetMillis(this.f);
                            this.j.mPlayer.setOffsetRepeat(this.g);
                            this.j.mPlayer.prepare();
                            this.j.mPlayer.start();
                        }
                        catch (Throwable throwable) {
                            throwable.printStackTrace();
                        }
                    }
                }
            });
            return;
        }
        throw new IllegalArgumentException("Wrong parameter {string: " + (String)var1_1 + "} is null!");
    }

    /*
     * WARNING - void declaration
     */
    public void playExtPrebaked(int prebakedId, int strength) {
        if (this.mContext != null && sInstance != null) {
            void var1_1;
            void var2_2;
            if (var2_2 == false) {
                e.a.c(TAG, "strength == 0, do nothing!");
                return;
            }
            if (var1_1 >= 10001 && var1_1 <= 10050) {
                this.playHaptic(c.a((int)var1_1), 0, (int)var2_2);
            } else {
                if (this.mThreadExecutor.isShutdown()) {
                    e.a.d(TAG, "executor down!");
                    return;
                }
                this.mThreadExecutor.execute(new Runnable(){
                    final /* synthetic */ int a;
                    final /* synthetic */ int b;
                    final /* synthetic */ RichTapUtils c;
                    {
                        void var1_1;
                        this.c = var1_1;
                        this.a = n;
                        this.b = n2;
                    }

                    @Override
                    public void run() {
                        RichTapPlayer richTapPlayer = this.c.mPlayer;
                        d d2 = this;
                        d d3 = d2;
                        int n = d2.a;
                        try {
                            richTapPlayer.playExtPrebakedEffectByHal(n, d3.b);
                        }
                        catch (Throwable throwable) {
                            throwable.printStackTrace();
                        }
                    }
                });
            }
            return;
        }
        e.a.d(TAG, "Not initialized!");
    }

    /*
     * WARNING - void declaration
     */
    public void sendLoopParameter(int amplitude, int interval, int freq) {
        if (this.mContext != null && sInstance != null) {
            void var2_2;
            void var3_3;
            void var1_1;
            if (this.mThreadExecutor.isShutdown()) {
                e.a.d(TAG, "executor down!");
                return;
            }
            this.mThreadExecutor.execute(new Runnable(){
                final /* synthetic */ int a;
                final /* synthetic */ int b;
                final /* synthetic */ int c;
                final /* synthetic */ RichTapUtils d;
                {
                    void var1_1;
                    this.d = var1_1;
                    this.a = n;
                    this.b = n2;
                    this.c = n3;
                }

                /*
                 * Enabled aggressive block sorting
                 * Enabled unnecessary exception pruning
                 * Enabled aggressive exception aggregation
                 */
                @Override
                public void run() {
                    try {
                        if (32 <= this.d.mRichTapCoreMajorVersion && 2 == this.d.mPlayerType) {
                            e e2 = this;
                            int n = e2.a;
                            int n2 = e2.b;
                            int n3 = e2.c;
                            this.d.mPlayerObjectPool.a(n, n2, n3);
                            return;
                        }
                        e e3 = this;
                        int n = e3.a;
                        int n4 = e3.b;
                        int n5 = e3.c;
                        this.d.mPlayer.updateHapticParameter(n, n4, n5);
                        return;
                    }
                    catch (Throwable throwable) {
                        throwable.printStackTrace();
                    }
                }
            });
            return;
        }
        e.a.d(TAG, "Not initialized!");
    }

    public void stop() {
        e.a.a(TAG, "stop()");
        if (this.mContext != null && sInstance != null) {
            if (this.mThreadExecutor.isShutdown()) {
                e.a.d(TAG, "executor down!");
                return;
            }
            this.mThreadExecutor.execute(new Runnable(this){
                final /* synthetic */ RichTapUtils a;
                {
                    void var1_1;
                    this.a = var1_1;
                }

                /*
                 * Enabled aggressive block sorting
                 * Enabled unnecessary exception pruning
                 * Enabled aggressive exception aggregation
                 */
                @Override
                public void run() {
                    try {
                        if (this.a.mVibrator != null) {
                            this.a.mVibrator.cancel();
                        }
                        if (this.a.mPlayerObjectPool != null) {
                            this.a.mPlayerObjectPool.g();
                        }
                        if (this.a.mPlayer == null) return;
                        this.a.mPlayer.stop();
                        return;
                    }
                    catch (Throwable throwable) {
                        throwable.printStackTrace();
                    }
                }
            });
            return;
        }
        e.a.d(TAG, "Not initialized!");
    }

    public boolean isPlaying() {
        if (32 <= ((RichTapUtils)((Object)this_)).mRichTapCoreMajorVersion && 2 == ((RichTapUtils)((Object)this_)).mPlayerType) {
            return ((RichTapUtils)((Object)this_)).mPlayerObjectPool.e();
        }
        RichTapPlayer this_ = ((RichTapUtils)((Object)this_)).mPlayer;
        if (this_ != null) {
            return this_.isPlaying();
        }
        return false;
    }

    public boolean isSupportedRichTap() {
        return RichTapPlayer.isPlayerTypeAvailable(2) || RichTapPlayer.isPlayerTypeAvailable(1);
    }

    /*
     * WARNING - void declaration
     */
    public void switchHaptic(boolean flag) {
        if (this.mContext != null && sInstance != null) {
            void var1_1;
            if (this.mThreadExecutor.isShutdown()) {
                e.a.d(TAG, "executor down!");
                return;
            }
            this.mThreadExecutor.execute(new Runnable(){
                final /* synthetic */ boolean a;
                final /* synthetic */ RichTapUtils b;
                {
                    void var1_1;
                    this.b = var1_1;
                    this.a = bl;
                }

                /*
                 * Enabled aggressive block sorting
                 * Enabled unnecessary exception pruning
                 * Enabled aggressive exception aggregation
                 */
                @Override
                public void run() {
                    try {
                        if (32 <= this.b.mRichTapCoreMajorVersion && 2 == this.b.mPlayerType) {
                            this.b.mPlayerObjectPool.a(this.a);
                            return;
                        }
                        if (this.b.mPlayer == null) {
                            e.a.b(RichTapUtils.TAG, "null == mPlayer");
                            return;
                        }
                        this.b.mPlayer.setSwitching(this.a);
                        return;
                    }
                    catch (Throwable throwable) {
                        throwable.printStackTrace();
                    }
                }
            });
            return;
        }
        e.a.d(TAG, "Not initialized!");
    }

    public boolean isHapticSwitched() {
        Object this_;
        if (32 <= ((RichTapUtils)this_).mRichTapCoreMajorVersion && 2 == ((RichTapUtils)this_).mPlayerType) {
            this_ = ((RichTapUtils)this_).mPlayerObjectPool;
            return this_ == null ? false : ((com.apprichtap.haptic.player.c)this_).d();
        }
        this_ = ((RichTapUtils)this_).mPlayer;
        return this_ == null ? false : ((RichTapPlayer)this_).getSwitching();
    }

    /*
     * WARNING - void declaration
     */
    public void setGain(int gain) {
        void var1_1;
        if (this.mThreadExecutor.isShutdown()) {
            e.a.d(TAG, "executor down!");
            return;
        }
        this.mThreadExecutor.execute(new Runnable(){
            final /* synthetic */ int a;
            final /* synthetic */ RichTapUtils b;
            {
                void var1_1;
                this.b = var1_1;
                this.a = n;
            }

            /*
             * Enabled aggressive block sorting
             * Enabled unnecessary exception pruning
             * Enabled aggressive exception aggregation
             */
            @Override
            public void run() {
                try {
                    if (32 <= this.b.mRichTapCoreMajorVersion && 2 == this.b.mPlayerType) {
                        this.b.mPlayerObjectPool.a(this.a);
                        return;
                    }
                    this.b.mPlayer.setGain(this.a);
                    return;
                }
                catch (Throwable throwable) {
                    throwable.printStackTrace();
                }
            }
        });
    }

    /*
     * WARNING - void declaration
     */
    public void playHaptic(String string, int loop, int interval, int amplitude, int freq) {
        void var5_5;
        void var4_4;
        void var3_3;
        void var2_2;
        void var1_1;
        this.playHaptic((String)var1_1, (int)var2_2, (int)var3_3, (int)var4_4, (int)var5_5, 0, true, null, null);
    }

    /*
     * WARNING - void declaration
     */
    public void playHaptic(String pattern, int amplitude, int freq, SyncCallback callback) {
        void var4_4;
        void var3_3;
        void var2_2;
        void var1_1;
        this.playHaptic((String)var1_1, 0, 0, (int)var2_2, (int)var3_3, 0, true, (SyncCallback)var4_4, null);
    }

    /*
     * WARNING - void declaration
     */
    public void playHaptic(File file, int loop) {
        void var2_2;
        void var1_1;
        this.playHaptic((File)var1_1, (int)var2_2, 0, 255, 0);
    }

    /*
     * WARNING - void declaration
     */
    public void playHaptic(File file, int loop, int amplitude) {
        void var3_3;
        void var2_2;
        void var1_1;
        this.playHaptic((File)var1_1, (int)var2_2, 0, (int)var3_3, 0);
    }

    /*
     * WARNING - void declaration
     */
    public void playHaptic(File file, int loop, int interval, int amplitude, int freq) {
        void var5_5;
        void var4_4;
        void var3_3;
        void var2_2;
        void var1_1;
        this.playHaptic(com.apprichtap.haptic.base.e.a((File)var1_1), (int)var2_2, (int)var3_3, (int)var4_4, (int)var5_5, 0, true, null, null);
    }

    /*
     * WARNING - void declaration
     */
    public void playHaptic(File file, int amplitude, int freq, SyncCallback callback) {
        void var4_4;
        void var3_3;
        void var2_2;
        void var1_1;
        this.playHaptic(com.apprichtap.haptic.base.e.a((File)var1_1), 0, 0, (int)var2_2, (int)var3_3, 0, true, (SyncCallback)var4_4, null);
    }

    /*
     * WARNING - void declaration
     */
    public void playHaptic(File file, int loop, int interval, int amplitude, int freq, SyncCallback callback, VibrationAttributes attrs) {
        void var7_7;
        void var6_6;
        void var5_5;
        void var4_4;
        void var3_3;
        void var2_2;
        void var1_1;
        this.playHaptic(com.apprichtap.haptic.base.e.a((File)var1_1), (int)var2_2, (int)var3_3, (int)var4_4, (int)var5_5, 0, true, (SyncCallback)var6_6, (VibrationAttributes)var7_7);
    }

    /*
     * WARNING - void declaration
     */
    public void playHaptic(String string, int loop) {
        void var2_2;
        void var1_1;
        this.playHaptic((String)var1_1, (int)var2_2, 255);
    }

    /*
     * WARNING - void declaration
     */
    public void playHaptic(String string, int loop, int amplitude) {
        void var3_3;
        void var2_2;
        void var1_1;
        this.playHaptic((String)var1_1, (int)var2_2, 0, (int)var3_3, 0, 0, true, null, null);
    }

    /*
     * WARNING - void declaration
     */
    public void sendLoopParameter(int amplitude, int interval) {
        void var2_2;
        void var1_1;
        this.sendLoopParameter((int)var1_1, (int)var2_2, 0);
    }

    @Deprecated
    public boolean isNonRichTapMode() {
        return this.mPlayerType == 0;
    }

    /*
     * WARNING - void declaration
     */
    @Deprecated
    public void selectPlayer(int type) {
        if (this.mContext != null && sInstance != null) {
            void var1_1;
            if (this.mThreadExecutor.isShutdown()) {
                e.a.d(TAG, "executor down!");
                return;
            }
            this.mThreadExecutor.execute(new Runnable(){
                final /* synthetic */ int a;
                final /* synthetic */ RichTapUtils b;
                {
                    void var1_1;
                    this.b = var1_1;
                    this.a = n;
                }

                /*
                 * Unable to fully structure code
                 */
                @Override
                public void run() {
                    block18: {
                        block20: {
                            block19: {
                                block17: {
                                    e.a.a("RichTapUtils", "selectPlayer request type:" + this.a);
                                    if (RichTapUtils.access$300(this.b) == null) ** GOTO lbl8
                                    v0 = this;
                                    RichTapUtils.access$300(v0.b).stop();
                                    RichTapUtils.access$300(v0.b).release();
lbl8:
                                    // 2 sources

                                    if (RichTapUtils.access$200(this.b) == null) break block17;
                                    RichTapUtils.access$200(this.b).a();
                                }
                                if (2 != this.a) break block18;
                                if (!RichTapPlayer.isPlayerTypeAvailable(2)) break block18;
                                if (32 > RichTapUtils.access$000(this.b)) break block19;
                                var1_1 = this.b;
                                RichTapUtils.access$202(var1_1, new com.apprichtap.haptic.player.c(RichTapUtils.access$500(var1_1), RichTapUtils.access$000(this.b)));
                                break block20;
                            }
                            v1 = this.b;
                            RichTapUtils.access$302(v1, RichTapPlayer.create(RichTapUtils.access$500(v1), 2));
                        }
                        v2 = RichTapUtils.access$102(this.b, 2);
lbl32:
                        // 3 sources

                        while (true) {
                            ** GOTO lbl56
                            break;
                        }
                    }
                    if (1 != this.a) ** GOTO lbl48
                    if (!RichTapPlayer.isPlayerTypeAvailable(1)) ** GOTO lbl48
                    v3 = this;
                    v4 = v3;
                    v5 = v3.b;
                    RichTapUtils.access$302(v5, RichTapPlayer.create(RichTapUtils.access$500(v5), 1));
                    v2 = RichTapUtils.access$102(v4.b, 1);
                    ** GOTO lbl32
lbl48:
                    // 2 sources

                    v6 = this;
                    v7 = v6;
                    v8 = v6.b;
                    RichTapUtils.access$302(v8, RichTapPlayer.create(RichTapUtils.access$500(v8), 0));
                    try {
                        v2 = RichTapUtils.access$102(v7.b, 0);
                        ** continue;
lbl56:
                        // 1 sources

                        e.a.a("RichTapUtils", "selectPrayer got type:" + RichTapUtils.access$100(this.b));
                    }
                    catch (Throwable v9) {
                        v9.printStackTrace();
                    }
                }
            });
            return;
        }
        e.a.d(TAG, "Not initialized!");
    }

    /*
     * WARNING - void declaration
     */
    @Deprecated
    public void playOneShot(long milliseconds, int amplitude) {
        if (this.mContext != null && sInstance != null) {
            void var3_2;
            void var1_1;
            if (this.mThreadExecutor.isShutdown()) {
                e.a.d(TAG, "executor down!");
                return;
            }
            this.mThreadExecutor.execute(new Runnable(){
                final /* synthetic */ long a;
                final /* synthetic */ int b;
                final /* synthetic */ RichTapUtils c;
                {
                    void var1_1;
                    this.c = var1_1;
                    this.a = l;
                    this.b = n;
                }

                /*
                 * Enabled aggressive block sorting
                 * Enabled unnecessary exception pruning
                 * Enabled aggressive exception aggregation
                 */
                @Override
                public void run() {
                    try {
                        if (this.c.mVibrator == null) {
                            e.a.b(RichTapUtils.TAG, "Please call the init method");
                            return;
                        }
                        e.a.a(RichTapUtils.TAG, "playOneShot: milliseconds,amplitude:" + this.a + "," + this.b);
                        if (Build.VERSION.SDK_INT >= 26) {
                            this.c.mVibrator.vibrate(VibrationEffect.createOneShot((long)this.a, (int)this.b));
                            return;
                        }
                        this.c.mVibrator.vibrate(this.a);
                        return;
                    }
                    catch (Throwable throwable) {
                        throwable.printStackTrace();
                    }
                }
            });
            return;
        }
        e.a.d(TAG, "Not initialized!");
    }

    /*
     * WARNING - void declaration
     */
    @Deprecated
    public void playWaveform(long[] timings, int repeat) {
        if (this.mContext != null && sInstance != null) {
            void var2_2;
            void var1_1;
            if (this.mThreadExecutor.isShutdown()) {
                e.a.d(TAG, "executor down!");
                return;
            }
            this.mThreadExecutor.execute(new Runnable(){
                final /* synthetic */ long[] a;
                final /* synthetic */ int b;
                final /* synthetic */ RichTapUtils c;
                {
                    void var1_1;
                    this.c = var1_1;
                    this.a = lArray;
                    this.b = n;
                }

                /*
                 * Enabled aggressive block sorting
                 * Enabled unnecessary exception pruning
                 * Enabled aggressive exception aggregation
                 */
                @Override
                public void run() {
                    try {
                        if (this_.c.mVibrator == null) {
                            e.a.b(RichTapUtils.TAG, "Please call the init method");
                            return;
                        }
                        if (Build.VERSION.SDK_INT >= 26) {
                            this_.c.mVibrator.vibrate(VibrationEffect.createWaveform((long[])this_.a, (int)this_.b));
                            return;
                        }
                        k k2 = this_;
                        Object this_ = k2.a;
                        this_.c.mVibrator.vibrate((long[])this_, k2.b);
                        return;
                    }
                    catch (Throwable throwable) {
                        throwable.printStackTrace();
                    }
                }
            });
            return;
        }
        e.a.d(TAG, "Not initialized!");
    }

    /*
     * WARNING - void declaration
     */
    @Deprecated
    public void playWaveform(long[] timings, int[] amplitudes, int repeat) {
        if (this.mContext != null && sInstance != null) {
            void var3_3;
            void var2_2;
            void var1_1;
            if (this.mThreadExecutor.isShutdown()) {
                e.a.d(TAG, "executor down!");
                return;
            }
            this.mThreadExecutor.execute(new Runnable(){
                final /* synthetic */ long[] a;
                final /* synthetic */ int[] b;
                final /* synthetic */ int c;
                final /* synthetic */ RichTapUtils d;
                {
                    void var1_1;
                    this.d = var1_1;
                    this.a = lArray;
                    this.b = nArray;
                    this.c = n;
                }

                /*
                 * Enabled aggressive block sorting
                 * Enabled unnecessary exception pruning
                 * Enabled aggressive exception aggregation
                 */
                @Override
                public void run() {
                    try {
                        Object this_;
                        if (this_.d.mVibrator == null) {
                            e.a.b(RichTapUtils.TAG, "Please call the init method");
                            return;
                        }
                        if (Build.VERSION.SDK_INT >= 26) {
                            a a2 = this_;
                            this_ = a2.b;
                            this_.d.mVibrator.vibrate(VibrationEffect.createWaveform((long[])this_.a, (int[])this_, (int)a2.c));
                            return;
                        }
                        a a3 = this_;
                        this_ = a3.a;
                        this_.d.mVibrator.vibrate((long[])this_, a3.c);
                        return;
                    }
                    catch (Throwable throwable) {
                        throwable.printStackTrace();
                    }
                }
            });
            return;
        }
        e.a.d(TAG, "Not initialized!");
    }

    /*
     * WARNING - void declaration
     */
    @Deprecated
    public void useNonRichTap(boolean nonRichTap) {
        void var1_1;
        e.a.a(TAG, "useNonRichTap:" + (boolean)var1_1);
        this.selectPlayer(0);
    }

    /*
     * WARNING - void declaration
     */
    @Deprecated
    public void playEnvelope(int[] relativeTimeArr, float[] scaleArr, int[] freqArr, boolean steepMode) {
        void var4_4;
        void var3_3;
        void var2_2;
        void var1_1;
        this.playEnvelope((int[])var1_1, (float[])var2_2, (int[])var3_3, (boolean)var4_4, 255);
    }

    /*
     * WARNING - void declaration
     */
    @Deprecated
    public void playEnvelope(int[] relativeTimeArr, float[] scaleArr, int[] freqArr, boolean steepMode, int amplitude) {
        if (this.mContext != null && sInstance != null) {
            void var5_5;
            void var4_4;
            void var3_3;
            void var2_2;
            void var1_1;
            int n = this.mRichTapCoreMajorVersion;
            if (22 <= n && 240 > com.apprichtap.haptic.player.d.l) {
                if (32 > n) {
                    this.stop();
                }
                if (this.mThreadExecutor.isShutdown()) {
                    e.a.d(TAG, "executor down!");
                    return;
                }
                this.mThreadExecutor.execute(new Runnable(){
                    final /* synthetic */ int[] a;
                    final /* synthetic */ float[] b;
                    final /* synthetic */ int[] c;
                    final /* synthetic */ boolean d;
                    final /* synthetic */ int e;
                    final /* synthetic */ RichTapUtils f;
                    {
                        void var1_1;
                        this.f = var1_1;
                        this.a = nArray;
                        this.b = fArray;
                        this.c = nArray2;
                        this.d = bl;
                        this.e = n;
                    }

                    /*
                     * Enabled aggressive block sorting
                     * Enabled unnecessary exception pruning
                     * Enabled aggressive exception aggregation
                     */
                    @Override
                    public void run() {
                        try {
                            Object object = RichTapPlayer.create(this_.f.mContext, 2);
                            if (object == null) {
                                e.a.b(RichTapUtils.TAG, "playEnvelope, null == player, seems no RichTap Core!");
                                return;
                            }
                            RichTapPlayer richTapPlayer = object;
                            b b2 = this_;
                            Object this_ = b2.a;
                            object = b2.b;
                            int[] nArray = b2.c;
                            boolean bl = b2.d;
                            int n = b2.e;
                            richTapPlayer.playEnvelope((int[])this_, (float[])object, nArray, bl, n);
                            return;
                        }
                        catch (Throwable throwable) {
                            throwable.printStackTrace();
                        }
                    }
                });
                return;
            }
            this.playHaptic(com.apprichtap.haptic.base.b.a((int[])var1_1, (float[])var2_2, (int[])var3_3, (boolean)var4_4, (int)var5_5), 0);
            return;
        }
        e.a.d(TAG, "Not initialized!");
    }

    /*
     * WARNING - void declaration
     */
    @Deprecated
    public void playPattern(String pattern, int loop, int interval, int amplitude, int freq, SyncCallback callback) {
        void var6_6;
        void var5_5;
        void var4_4;
        void var3_3;
        void var2_2;
        void var1_1;
        this.playHaptic((String)var1_1, (int)var2_2, (int)var3_3, (int)var4_4, (int)var5_5, (SyncCallback)var6_6);
    }

    /*
     * WARNING - void declaration
     */
    @Deprecated
    public void playPattern(String string, int loop, int interval, int amplitude, int freq) {
        void var5_5;
        void var4_4;
        void var3_3;
        void var2_2;
        void var1_1;
        this.playHaptic((String)var1_1, (int)var2_2, (int)var3_3, (int)var4_4, (int)var5_5);
    }

    /*
     * WARNING - void declaration
     */
    @Deprecated
    public void playPattern(String pattern, int amplitude, int freq, SyncCallback callback) {
        void var4_4;
        void var3_3;
        void var2_2;
        void var1_1;
        this.playHaptic((String)var1_1, (int)var2_2, (int)var3_3, (SyncCallback)var4_4);
    }

    /*
     * WARNING - void declaration
     */
    @Deprecated
    public void playPattern(File file, int amplitude, int freq, SyncCallback callback) {
        void var4_4;
        void var3_3;
        void var2_2;
        void var1_1;
        this.playHaptic((File)var1_1, (int)var2_2, (int)var3_3, (SyncCallback)var4_4);
    }

    /*
     * WARNING - void declaration
     */
    @Deprecated
    public void playPattern(File file, int loop) {
        void var2_2;
        void var1_1;
        this.playHaptic((File)var1_1, (int)var2_2);
    }

    /*
     * WARNING - void declaration
     */
    @Deprecated
    public void playPattern(File file, int loop, int amplitude) {
        void var3_3;
        void var2_2;
        void var1_1;
        this.playHaptic((File)var1_1, (int)var2_2, (int)var3_3);
    }

    /*
     * WARNING - void declaration
     */
    @Deprecated
    public void playPattern(File file, int loop, int interval, int amplitude, int freq) {
        void var5_5;
        void var4_4;
        void var3_3;
        void var2_2;
        void var1_1;
        this.playHaptic((File)var1_1, (int)var2_2, (int)var3_3, (int)var4_4, (int)var5_5);
    }

    /*
     * WARNING - void declaration
     */
    @Deprecated
    public void playPattern(String string, int loop) {
        void var2_2;
        void var1_1;
        this.playHaptic((String)var1_1, (int)var2_2);
    }

    /*
     * WARNING - void declaration
     */
    @Deprecated
    public void playPattern(String string, int loop, int amplitude) {
        void var3_3;
        void var2_2;
        void var1_1;
        this.playHaptic((String)var1_1, (int)var2_2, (int)var3_3);
    }

    @Deprecated
    public int getRichTapCoreMajorVersion() {
        return RichTapPlayer.getRichTapCoreMajorVersion(this.mContext);
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    @Deprecated
    public void clearPlayingTask() {
        e.a.a(TAG, "clearPlayingTask()");
        try {
            RichTapPlayer richTapPlayer = ((RichTapUtils)this).mPlayer;
            if (richTapPlayer != null) {
                richTapPlayer.clearPlayingTask();
            }
            if ((this = ((RichTapUtils)this).mPlayerObjectPool) == null) return;
            ((com.apprichtap.haptic.player.c)this).b();
            return;
        }
        catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }
}

