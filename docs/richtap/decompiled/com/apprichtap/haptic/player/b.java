/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  android.os.Build$VERSION
 *  android.os.Handler
 *  android.os.Handler$Callback
 *  android.os.HandlerThread
 *  android.os.Looper
 *  android.os.Message
 *  android.os.VibrationAttributes
 *  android.os.VibrationEffect
 *  android.os.VibrationEffect$Composition
 *  android.os.Vibrator
 *  android.text.TextUtils
 *  android.util.Log
 *  android.util.Pair
 *  org.json.JSONObject
 */
package com.apprichtap.haptic.player;

import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import com.apprichtap.haptic.base.e;
import com.apprichtap.haptic.player.IHapticEffectPerformer;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

public class b
implements IHapticEffectPerformer {
    private static final String h = "GooglePerformer";
    private static final int i = 60001;
    private Vibrator a;
    private VibrationAttributes b;
    private Handler c;
    private Handler.Callback d;
    private HandlerThread e;
    private List<Pair<Integer, VibrationEffect>> f;
    long g;

    /*
     * WARNING - void declaration
     */
    public b(Vibrator vibrator) {
        void var1_1;
        if (vibrator == null) {
            e.a.b(h, "GooglePerformer(Vibrator), vibrator == null");
            return;
        }
        b4.a = var1_1;
        if (Build.VERSION.SDK_INT >= 31) {
            b b2 = b4;
            b2.d = new Handler.Callback(b4){
                final /* synthetic */ b a;
                {
                    void var1_1;
                    this.a = var1_1;
                }

                /*
                 * WARNING - void declaration
                 */
                public boolean handleMessage(Message msg) {
                    if (msg.what == 60001) {
                        void var1_1;
                        com.apprichtap.haptic.player.b.a(this.a, (VibrationAttributes)var1_1.obj);
                        return true;
                    }
                    return false;
                }
            };
            b2.f = new ArrayList<Pair<Integer, VibrationEffect>>();
            b4.e = new HandlerThread(h);
            b4.e.start();
            b b3 = b4;
            b b4 = b3.e.getLooper();
            b2.c = new Handler((Looper)b4, b3.d);
        }
    }

    private void a(VibrationAttributes attributes) {
        Message message;
        if (this_.f.isEmpty()) {
            return;
        }
        Pair<Integer, VibrationEffect> pair = this_.f.get(0);
        int n = Build.VERSION.SDK_INT;
        if (n >= 33 && message != null) {
            this_.a.vibrate((VibrationEffect)pair.second, (VibrationAttributes)message);
        } else if (n >= 26) {
            this_.a.vibrate((VibrationEffect)pair.second);
        }
        Pair<Integer, VibrationEffect> pair2 = this_;
        pair2.f.remove(pair);
        if (pair2.f.isEmpty()) {
            return;
        }
        Pair<Integer, VibrationEffect> pair3 = this_;
        Pair<Integer, VibrationEffect> this_ = pair3.f.get(0);
        message = Message.obtain((Handler)pair3.c, (int)60001, (Object)message);
        Log.d((String)h, (String)("Delay: " + ((Integer)this_.first - (Integer)pair.first)));
        pair3.c.sendMessageDelayed(message, (long)((Integer)this_.first - (Integer)pair.first));
    }

    private static List<Pair<Integer, VibrationEffect>> a(String json) throws Exception {
        String string;
        ArrayList<Pair<Integer, VibrationEffect>> arrayList;
        ArrayList<Pair<Integer, VibrationEffect>> arrayList2 = arrayList;
        arrayList = new ArrayList<Pair<Integer, VibrationEffect>>();
        if (Build.VERSION.SDK_INT < 30) {
            return arrayList2;
        }
        string = new JSONObject(string).optJSONArray("Pattern");
        long l = 0L;
        for (int i2 = 0; i2 < string.length(); ++i2) {
            ArrayList<Integer> arrayList3;
            ArrayList<Long> arrayList4;
            JSONObject jSONObject = string.optJSONObject(i2).optJSONObject("Event");
            VibrationEffect.Composition composition = null;
            ArrayList<Long> arrayList5 = arrayList4;
            arrayList4 = new ArrayList<Long>();
            ArrayList<Integer> arrayList6 = arrayList3;
            arrayList3 = new ArrayList<Integer>();
            Object object = jSONObject.optString("Type");
            JSONObject jSONObject2 = jSONObject;
            int n = jSONObject2.optInt("RelativeTime");
            int n2 = jSONObject2.optInt("Duration", 48);
            JSONObject jSONObject3 = jSONObject2.optJSONObject("Parameters");
            int n3 = jSONObject3.optInt("Intensity");
            int n4 = jSONObject3.optInt("Frequency");
            if (TextUtils.equals((CharSequence)object, (CharSequence)"continuous")) {
                arrayList5.add(0L);
                arrayList6.add(0);
                long l2 = n;
                if (l2 > l) {
                    ArrayList<Long> arrayList7 = arrayList5;
                    arrayList5.add(l2 - l);
                    arrayList6.add(0);
                    l += ((Long)arrayList7.get(arrayList7.size() - 1)).longValue();
                }
                ArrayList<Long> arrayList8 = arrayList5;
                com.apprichtap.haptic.player.b.a(arrayList8, arrayList6, n3, n4, n2);
                l += ((Long)arrayList8.get(arrayList8.size() - 1)).longValue();
            } else {
                if (n3 >= 0 && n3 <= 10) continue;
                composition = VibrationEffect.startComposition();
                com.apprichtap.haptic.player.b.a(composition, n3, n4, 0);
            }
            n2 = n + n2;
            for (n3 = i2 + 1; n3 < string.length(); ++n3) {
                JSONObject jSONObject4 = string.optJSONObject(n3).optJSONObject("Event");
                String string2 = jSONObject4.optString("Type");
                JSONObject jSONObject5 = jSONObject4;
                n4 = jSONObject5.optInt("RelativeTime");
                int n5 = jSONObject5.optInt("Duration", 48);
                JSONObject jSONObject6 = jSONObject5.optJSONObject("Parameters");
                int n6 = jSONObject6.optInt("Intensity");
                int n7 = jSONObject6.optInt("Frequency");
                if (!TextUtils.equals((CharSequence)string2, (CharSequence)object)) break;
                if (TextUtils.equals((CharSequence)string2, (CharSequence)"continuous")) {
                    long l3 = n4;
                    if (l3 > l) {
                        ArrayList<Long> arrayList9 = arrayList5;
                        arrayList5.add(l3 - l);
                        arrayList6.add(0);
                        l += ((Long)arrayList9.get(arrayList9.size() - 1)).longValue();
                    }
                    ArrayList<Long> arrayList10 = arrayList5;
                    com.apprichtap.haptic.player.b.a(arrayList10, arrayList6, n6, n7, n5);
                    l += ((Long)arrayList10.get(arrayList10.size() - 1)).longValue();
                } else if (n6 > 10) {
                    com.apprichtap.haptic.player.b.a(composition, n6, n7, n4 - n2 + 36);
                }
                ++i2;
                n2 = n4 + n5;
            }
            if (TextUtils.equals((CharSequence)object, (CharSequence)"continuous")) {
                int n8 = arrayList5.size();
                object = new long[n8];
                int[] nArray = new int[n8];
                n3 = 0;
                for (n4 = 0; n4 < n8; ++n4) {
                    object[n4] = (Long)arrayList5.get(n4);
                    nArray[n4] = (Integer)arrayList6.get(n4);
                    int n9 = (Long)arrayList5.get(n4) != 0L ? 1 : 0;
                    n3 |= n9;
                }
                if (n3 == 0) {
                    object[0] = 1L;
                }
                composition = VibrationEffect.createWaveform((long[])object, (int[])nArray, (int)-1);
            } else {
                composition = composition.compose();
            }
            arrayList2.add((Pair<Integer, VibrationEffect>)new Pair((Object)n, (Object)composition));
        }
        return arrayList2;
    }

    /*
     * WARNING - void declaration
     */
    private static void a(VibrationEffect.Composition composition, int intensity, int frequency, int delay) {
        void var3_4;
        VibrationEffect.Composition composition2;
        void var2_3;
        void var1_1;
        if (Build.VERSION.SDK_INT < 30) {
            return;
        }
        float f2 = (float)(var1_1 - 10) / 90.0f;
        if (var2_3 >= 69 && var2_3 <= 100) {
            composition2.addPrimitive(7, f2, (int)var3_4);
        } else if (var2_3 >= 41 && var2_3 <= 100) {
            composition2.addPrimitive(1, f2, (int)var3_4);
        } else {
            composition2.addPrimitive(8, f2, (int)var3_4);
        }
    }

    /*
     * WARNING - void declaration
     */
    private static void a(List<Long> times, List<Integer> amplitudes, int intensity, int frequency, int duration) {
        void var3_3;
        void var2_2;
        void var4_4;
        List<Long> list;
        list.add(Long.valueOf(com.apprichtap.haptic.base.b.a((int)var4_4)));
        amplitudes.add((int)((double)com.apprichtap.haptic.base.b.b((int)var2_2, (int)var3_3) * 1.0 / 100.0 * 255.0));
    }

    /*
     * WARNING - void declaration
     */
    static /* synthetic */ void a(b x0, VibrationAttributes x1) {
        void var1_1;
        x0.a((VibrationAttributes)var1_1);
    }

    /*
     * Unable to fully structure code
     */
    @Override
    public void start(String patternString) {
        block27: {
            block26: {
                block24: {
                    block25: {
                        var1_3 = com.apprichtap.haptic.base.b.a(patternString, true);
                        if (Build.VERSION.SDK_INT < 31) break block24;
                        if (!this.a.areAllPrimitivesSupported(new int[]{7, 1, 8})) break block24;
                        var1_3 = com.apprichtap.haptic.player.b.a((String)var1_3);
                        this.f.addAll(var1_3);
                        if (var1_3.size() <= 0) break block25;
                        v0 = this;
                        v1 = v0;
                        v2 = v0.c;
                        var0_1 = 60001;
                        var0_2 = Message.obtain((Handler)v2, (int)var0_1, (Object)this.b);
                        v3 = v1.c;
                        v4 = var0_2;
                        v5 = ((Integer)var1_3.get((int)0).first).intValue();
                        v3.sendMessageDelayed(v4, v5);
                    }
                    return;
                }
                v6 = var1_3;
                var1_3 = v7;
                v7 = new ArrayList<Long>();
                var2_5 = v8;
                v8 = new ArrayList<Integer>();
                com.apprichtap.haptic.base.b.a(v6, v7, var2_5);
                if (var1_3.size() == 0) ** GOTO lbl75
                if (var1_3.size() != var2_5.size()) ** GOTO lbl75
                v9 = var2_5;
                e.a.a("GooglePerformer", "timings:" + var1_3.toString() + ",amplitudes:" + var2_5.toString());
                var3_6 = new long[var1_3.size()];
                var4_7 = new int[v9.size()];
                var5_8 = 0;
                while (true) {
                    if (var5_8 >= var1_3.size()) break;
                    var3_6[var5_8] = (Long)var1_3.get(var5_8);
                    var4_7[var5_8] = var2_5.get(var5_8);
                    ++var5_8;
                    continue;
                    break;
                }
                var1_4 = Build.VERSION.SDK_INT;
                if (var1_4 < 33) break block26;
                v10 = this.a;
                v11 = this;
                this = VibrationEffect.createWaveform((long[])var3_6, (int[])var4_7, (int)-1);
                v10.vibrate((VibrationEffect)this, v11.b);
            }
            if (var1_4 < 26) ** GOTO lbl73
            try {
                this.a.vibrate(VibrationEffect.createWaveform((long[])var3_6, (int[])var4_7, (int)-1));
                break block27;
lbl73:
                // 1 sources

                this.a.vibrate(var3_6, -1);
                break block27;
lbl75:
                // 2 sources

                e.a.b("GooglePerformer", "start(String), invalid timings and amplitudes!");
                return;
            }
            catch (Throwable v12) {
                v12.printStackTrace();
            }
        }
    }

    @Override
    public void stop() {
        if (this.a == null) {
            e.a.b(h, "stop(), null == mVibrator!");
            return;
        }
        Handler handler = this.c;
        if (handler != null) {
            handler.removeMessages(60001);
            this.f.clear();
        }
        this.a.cancel();
    }

    /*
     * WARNING - void declaration
     */
    @Override
    public void setVibrationAttributes(VibrationAttributes attrs) {
        void var1_1;
        this.b = var1_1;
    }

    @Override
    public void release() {
        Handler handler = this.c;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if ((handler = this.e) != null) {
            handler.quit();
        }
        b b2 = this;
        b2.c = null;
        b2.d = null;
        b2.e = null;
        b2.a = null;
    }
}

