/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  android.content.Context
 *  android.os.VibrationAttributes
 */
package com.apprichtap.haptic.player;

import android.content.Context;
import android.os.VibrationAttributes;
import com.apprichtap.haptic.RichTapPlayer;
import com.apprichtap.haptic.base.e;
import com.apprichtap.haptic.player.PlayerEventCallback;
import com.apprichtap.haptic.sync.SyncCallback;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class c {
    private static final int f = 4;
    private static final String g = "PlayerObjectPool";
    private AtomicInteger a;
    private LinkedHashMap<Integer, RichTapPlayer> b;
    private boolean c;
    private Context d;
    private int e;

    /*
     * WARNING - void declaration
     */
    public c(Context context, int coreVersion) {
        void var2_2;
        void var1_1;
        LinkedHashMap linkedHashMap;
        AtomicInteger atomicInteger;
        Object this_ = atomicInteger;
        atomicInteger = new AtomicInteger();
        v1.a = this_;
        this_ = linkedHashMap;
        linkedHashMap = new LinkedHashMap();
        v1.b = this_;
        v1.d = var1_1;
        v1.e = var2_2;
    }

    private synchronized int c() {
        c c2 = this;
        c2.f();
        int n = c2.a.incrementAndGet() % Integer.MAX_VALUE;
        RichTapPlayer richTapPlayer = RichTapPlayer.create(c2.d, 2);
        if (richTapPlayer != null) {
            ((AbstractMap)this.b).put(n, richTapPlayer);
        }
        return n;
    }

    private synchronized void f() {
        Object this_;
        e.a.a(g, "PlayerObjectPool shrink() mPlayers.size():" + ((AbstractMap)((c)object3).b).size() + ", MAX_SIZE:" + 4);
        if (4 <= ((AbstractMap)((c)this_).b).size()) {
            ArrayList arrayList;
            ArrayList arrayList2;
            Object object;
            Object object2;
            if (com.apprichtap.haptic.base.e.b()) {
                object2 = "";
                object = ((c)object3).b.keySet().iterator();
                while (object.hasNext()) {
                    object2 = object2 + (Integer)object.next() + " ";
                }
                e.a.a(g, "PlayerObjectPool mPlayers keys before shrink:" + object2);
            }
            object2 = arrayList2;
            arrayList2 = new ArrayList();
            for (Map.Entry object3 : ((c)object3).b.entrySet()) {
                if (((RichTapPlayer)object3.getValue()).isPlaying()) continue;
                ((ArrayList)object2).add((Integer)((Integer)object3.getKey()));
            }
            object2 = ((ArrayList)object2).iterator();
            while (object2.hasNext()) {
                c c2 = object3;
                int n = (Integer)object2.next();
                e.a.a(g, "PlayerObjectPool will remove :" + n + " as not playing!");
                c2.b.get(n).release();
                ((AbstractMap)c2.b).remove(n);
            }
            int n = ((AbstractMap)((c)object3).b).size() - 4 + 1;
            if (n <= 0) {
                e.a.a(g, "PlayerObjectPool, no need to shrink the playing ones!");
                return;
            }
            object = arrayList;
            arrayList = new ArrayList();
            for (Map.Entry<Integer, RichTapPlayer> entry : ((c)object3).b.entrySet()) {
                entry.getValue().stop();
                entry.getValue().release();
                ((ArrayList)object).add(entry.getKey());
                if ((n += -1) != 0) continue;
            }
            Iterator<Object> iterator = ((ArrayList)object).iterator();
            while (iterator.hasNext()) {
                int n2 = (Integer)iterator.next();
                e.a.a(g, "PlayerObjectPool will remove :" + n2 + " as oversize!");
                ((AbstractMap)((c)object3).b).remove(n2);
            }
            if (com.apprichtap.haptic.base.e.b()) {
                c c3 = object3;
                Object object3 = "";
                iterator = c3.b.keySet().iterator();
                while (iterator.hasNext()) {
                    object3 = (String)object3 + (Integer)iterator.next() + " ";
                }
                e.a.a(g, "PlayerObjectPool mPlayers current keys after shrink:" + (String)object3);
            }
        }
    }

    public synchronized void a() {
        for (Map.Entry<Integer, RichTapPlayer> entry : this.b.entrySet()) {
            entry.getValue().stop();
            entry.getValue().unregisterPlayerEventCallback();
            entry.getValue().release();
        }
        this.b.clear();
    }

    public synchronized void g() {
        for (Map.Entry entry : ((c)this).b.entrySet()) {
            if (entry.getValue() == null) continue;
            ((RichTapPlayer)entry.getValue()).stop();
        }
    }

    /*
     * WARNING - void declaration
     */
    public synchronized int a(String pattern, int loop, int interval, int amplitude, int freq, SyncCallback callback, VibrationAttributes attrs) {
        void var7_7;
        void var6_6;
        void var5_5;
        void var4_4;
        void var3_3;
        void var2_2;
        void var1_1;
        return this.a((String)var1_1, (int)var2_2, (int)var3_3, (int)var4_4, (int)var5_5, 0, true, (SyncCallback)var6_6, (VibrationAttributes)var7_7);
    }

    /*
     * WARNING - void declaration
     */
    public synchronized int a(String pattern, int loop, int interval, int amplitude, int freq, int offsetMillis, SyncCallback callback, VibrationAttributes attrs) {
        void var8_8;
        void var7_7;
        void var6_6;
        void var5_5;
        void var4_4;
        void var3_3;
        void var2_2;
        void var1_1;
        return this.a((String)var1_1, (int)var2_2, (int)var3_3, (int)var4_4, (int)var5_5, (int)var6_6, true, (SyncCallback)var7_7, (VibrationAttributes)var8_8);
    }

    /*
     * WARNING - void declaration
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    public synchronized int a(String pattern, int loop, int interval, int amplitude, int freq, int offsetMillis, boolean offsetRepeat, SyncCallback callback, VibrationAttributes attrs) {
        try {
            void var7_8;
            void var6_7;
            void var8_9;
            void var3_4;
            void var2_3;
            void var5_6;
            void var4_5;
            void var1_2;
            void var9_10;
            if (32 > this.e) {
                this.g();
            }
            c c2 = this;
            int n = c2.c();
            RichTapPlayer richTapPlayer = c2.b.get(n);
            if (richTapPlayer == null) {
                e.a.b(g, "PlayerObjectPool, playHaptic, null == player");
                return -1;
            }
            RichTapPlayer richTapPlayer2 = richTapPlayer;
            richTapPlayer2.setVibrationAttributes((VibrationAttributes)var9_10);
            richTapPlayer2.setDataSource((String)var1_2, (int)var4_5, (int)var5_6, (int)var2_3, (int)var3_4, (SyncCallback)var8_9);
            richTapPlayer2.setStartOffsetMillis((int)var6_7);
            richTapPlayer2.setOffsetRepeat((boolean)var7_8);
            richTapPlayer2.prepare();
            richTapPlayer2.start();
            return n;
        }
        catch (Throwable throwable) {
            return -1;
        }
    }

    /*
     * WARNING - void declaration
     */
    public synchronized void a(PlayerEventCallback callback) {
        for (Map.Entry entry : ((c)this).b.entrySet()) {
            void var1_1;
            if (entry.getValue() == null) continue;
            ((RichTapPlayer)entry.getValue()).registerPlayerEventCallback((PlayerEventCallback)var1_1);
        }
    }

    /*
     * WARNING - void declaration
     */
    public synchronized void a(int amplitude, int freq, int interval) {
        for (Map.Entry entry : ((c)this).b.entrySet()) {
            void var3_3;
            void var2_2;
            void var1_1;
            if (entry.getValue() == null) {
                e.a.b(g, "null == entry.getValue(), PlayerObjectPool, updateHapticParameter(int amplitude, int freq, int interval)");
                continue;
            }
            ((RichTapPlayer)entry.getValue()).updateHapticParameter((int)var1_1, (int)var2_2, (int)var3_3);
        }
    }

    /*
     * WARNING - void declaration
     */
    public synchronized void a(int gain) {
        for (Map.Entry entry : ((c)this).b.entrySet()) {
            void var1_1;
            if (entry.getValue() == null) {
                e.a.b(g, "null == entry.getValue(), PlayerObjectPool, setGain(int)");
                continue;
            }
            ((RichTapPlayer)entry.getValue()).setGain((int)var1_1);
        }
    }

    /*
     * WARNING - void declaration
     */
    public synchronized void a(boolean flag) {
        void var1_1;
        ((c)this).c = var1_1;
        for (Map.Entry entry : ((c)this).b.entrySet()) {
            if (entry.getValue() == null) {
                e.a.b(g, "null == entry.getValue(), PlayerObjectPool, setGain(int)");
                continue;
            }
            ((RichTapPlayer)entry.getValue()).setSwitching((boolean)var1_1);
        }
    }

    public synchronized boolean d() {
        return this.c;
    }

    public synchronized boolean e() {
        for (Map.Entry entry : ((c)this).b.entrySet()) {
            if (entry.getValue() == null || !((RichTapPlayer)entry.getValue()).isPlaying()) continue;
            return true;
        }
        return false;
    }

    public synchronized void b() {
        for (Map.Entry entry : ((c)this).b.entrySet()) {
            if (entry.getValue() == null) {
                e.a.b(g, "null == entry.getValue(), PlayerObjectPool, clearPlayingTask");
                continue;
            }
            ((RichTapPlayer)entry.getValue()).clearPlayingTask();
        }
    }
}

