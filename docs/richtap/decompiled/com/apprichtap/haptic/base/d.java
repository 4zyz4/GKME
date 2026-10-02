/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  android.os.Process
 */
package com.apprichtap.haptic.base;

import android.os.Process;
import com.apprichtap.haptic.base.e;
import java.util.Hashtable;

public class d {
    private static final String a = "SenderIdManager";
    private static short b = 0;
    private static Hashtable<String, int[]> c = new Hashtable();

    public static synchronized int[] a(String tidAndTime) {
        String string;
        if (tidAndTime == null) {
            e.a.d(a, "getSenderId, param == null");
            return null;
        }
        int[] nArray = c.get(string);
        if (nArray != null && 2 == nArray.length) {
            int[] nArray2 = nArray;
            short s = (short)(Math.abs((short)((short)(nArray2[1] & 0xFFFF) + 1)) % Short.MAX_VALUE);
            nArray2[1] = nArray[1] & 0xFFFF0000 | s;
            e.a.a(a, "getSenderId, tidAndTime:" + string + " pid:" + nArray[0] + ", gid:" + (nArray[1] & 0xFFFF0000) + ", seq:" + (nArray[1] & 0xFFFF));
            return nArray;
        }
        e.a.d(a, "getSenderId, param: " + string + " fail!");
        return null;
    }

    public static synchronized void b(String tidAndTime) {
        String string;
        if (tidAndTime == null) {
            e.a.d(a, "registerSender, param == null");
            return;
        }
        int[] nArray = new int[2];
        int[] nArray2 = nArray;
        nArray[0] = Process.myPid();
        e.a.a(a, "registerSender groupId:" + b);
        b = (short)(b + 1);
        b = (short)(Math.abs(b) % Short.MAX_VALUE);
        nArray[1] = b << 16;
        e.a.a(a, "registerSender, tidAndTime:" + string + " senderId:" + nArray2[0] + "," + nArray2[1]);
        c.put(string, nArray2);
    }

    public static synchronized void c(String tidAndTime) {
        String string;
        if (tidAndTime == null) {
            e.a.d(a, "registerSender, param == null");
            return;
        }
        c.remove(string);
    }
}

