/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  android.os.Process
 *  android.os.SystemClock
 *  android.util.Log
 */
package com.apprichtap.haptic.player;

import a.c;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import com.apprichtap.haptic.base.d;
import com.apprichtap.haptic.player.PlayerEventCallback;
import com.apprichtap.haptic.sync.SyncCallback;

public class a {
    private static final String y = "CurrentPlayingInfo";
    public String a;
    public long b;
    public int c;
    public int d;
    public int e;
    public int f;
    public c.a g;
    public SyncCallback h;
    public int i;
    public int j;
    public boolean k = true;
    public int l;
    private int m;
    public float n = 1.0f;
    public String o;
    public String p;
    public boolean q;
    public int r;
    public int s;
    public String t;
    public int u;
    public int v;
    public String w;
    public PlayerEventCallback x;

    public static boolean a(c c2) {
        return a.c.a(c2);
    }

    /*
     * WARNING - void declaration
     */
    private boolean a(int status) {
        void var1_1;
        return status >= 0 && var1_1 <= 9;
    }

    /*
     * WARNING - void declaration
     */
    private boolean b(int from, int to) {
        void var2_2;
        void var1_1;
        if (this.a((int)var1_1) && this.a((int)var2_2)) {
            switch (var1_1) {
                default: {
                    return false;
                }
                case 9: {
                    return 2 == var2_2 || var2_2 == false || true == var2_2 || 6 == var2_2 || 7 == var2_2 || 9 == var2_2;
                    {
                    }
                }
                case 8: {
                    return 2 == var2_2 || var2_2 == false || true == var2_2 || 4 == var2_2 || 5 == var2_2 || 8 == var2_2;
                    {
                    }
                }
                case 6: 
                case 7: {
                    return 2 == var2_2 || var2_2 == false || true == var2_2 || 6 == var2_2 || 8 == var2_2 || 7 == var2_2 || 9 == var2_2;
                    {
                    }
                }
                case 5: {
                    return 2 == var2_2 || var2_2 == false || true == var2_2 || 5 == var2_2 || 6 == var2_2 || 8 == var2_2;
                    {
                    }
                }
                case 4: {
                    return 2 == var2_2 || var2_2 == false || true == var2_2 || 4 == var2_2 || 5 == var2_2;
                    {
                    }
                }
                case 3: {
                    return var2_2 == false || 2 == var2_2 || true == var2_2 || 3 == var2_2 || 4 == var2_2 || 5 == var2_2;
                    {
                    }
                }
                case 2: {
                    return 2 == var2_2;
                }
                case 1: {
                    return true == var2_2;
                }
                case 0: 
            }
            return var2_2 == false || 2 == var2_2 || true == var2_2 || 3 == var2_2;
            {
            }
        }
        return false;
    }

    public void d() {
        Log.d((String)y, (String)"reset!");
        this.a = null;
        this.b = 0L;
        this.c = 0;
        this.d = 255;
        this.e = 0;
        this.f = 0;
        this.g = null;
        this.h = null;
        this.i = 0;
        this.j = 0;
        this.k = true;
        this.m = 0;
        this.n = 1.0f;
        this.o = null;
        this.r = 0;
        this.t = null;
        this.u = 0;
        this.v = 0;
        this.w = null;
        String string = this.p;
        if (string != null && string.length() != 0) {
            com.apprichtap.haptic.base.d.c(this.p);
        }
        this.p = null;
    }

    public void c() {
        String string = this.p;
        if (string != null && string.length() != 0) {
            com.apprichtap.haptic.base.d.c(this.p);
        }
        this.p = Process.myTid() + "," + SystemClock.elapsedRealtime();
        com.apprichtap.haptic.base.d.b(this.p);
    }

    public String toString() {
        return "CurrentPlayingHeInfo{mHeString='" + this.a + '\'' + ", mStartTime=" + this.b + ", mLoop=" + this.c + ", mAmplitude=" + this.d + ", mFreq=" + this.e + ", mHeRoot=" + this.g + ", mSyncCallback=" + this.h + ", mStartPosition=" + this.i + ", mDelayMillis=" + this.j + ", mOffsetRepeat=" + this.k + ", mStatus:" + this.m + ", mSpeedMultiple:" + this.n + '}';
    }

    public int a() {
        c.a a2 = this.g;
        if (a2 == null) {
            return -1;
        }
        if (2 == a2.a()) {
            return this.g.b.size();
        }
        if (1 == this.g.a()) {
            return 1;
        }
        Log.w((String)y, (String)"getPatternCount(), invalid HE version!");
        return -1;
    }

    /*
     * WARNING - void declaration
     */
    public void a(int status, int code) {
        void var2_2;
        void var1_1;
        a a2 = this;
        Log.d((String)y, (String)("changePlayerStatus, from:" + this.m + ",to:" + (int)var1_1 + ", code:" + (int)var2_2));
        if (!a2.b(a2.m, (int)var1_1)) {
            Log.w((String)y, (String)("changePlayerStatus, invalid transition, from " + this.m + " to " + (int)var1_1));
            return;
        }
        if (var1_1 == this.m && var2_2 != false) {
            Log.w((String)y, (String)"changePlayerStatus, needn't update");
            return;
        }
        this.m = var1_1;
        PlayerEventCallback playerEventCallback = this.x;
        if (playerEventCallback != null) {
            playerEventCallback.onPlayerStateChanged((int)var1_1);
            if (2 == this.m) {
                this.x.onError((int)var2_2);
            }
        }
    }

    /*
     * WARNING - void declaration
     */
    public boolean b(int status) {
        void var1_1;
        a a2 = this;
        boolean bl = a2.b(a2.m, (int)var1_1);
        if (!bl) {
            Log.w((String)y, (String)("not invalid change status from " + this.m + " to " + (int)var1_1));
        }
        return bl;
    }

    public int b() {
        return this.m;
    }
}

