/*
 * Decompiled with CFR 0.152.
 */
package a;

import a.e;
import c.a;
import java.util.ArrayList;

public interface c {
    public static boolean a(c c2) {
        if (c2 == null) {
            return false;
        }
        if (1 == c2.a()) {
            c2 = (b.a)c2;
            ArrayList<e> arrayList = ((b.a)c2).b;
            return arrayList != null && arrayList.size() >= 1 && ((b.a)c2).b.get((int)0).a != null;
            {
            }
        }
        if (2 == c2.a()) {
            c2 = (a)c2;
            ArrayList<c.c> arrayList = ((a)c2).b;
            return arrayList != null && arrayList.size() >= 1 && ((a)c2).b.get((int)0).b != null && ((a)c2).b.get((int)0).b.size() >= 1 && ((a)c2).b.get((int)0).b.get((int)0).a != null;
            {
            }
        }
        return false;
    }

    public int a();

    public int getDuration();
}

