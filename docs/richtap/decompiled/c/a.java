/*
 * Decompiled with CFR 0.152.
 */
package c;

import a.e;
import c.b;
import c.c;
import java.util.ArrayList;
import java.util.Iterator;

public class a
implements a.c {
    public b a;
    public ArrayList<c> b;

    @Override
    public int a() {
        return this.a.a;
    }

    @Override
    public int getDuration() {
        int n = 0;
        ArrayList<c> arrayList = this.b;
        ArrayList<c> arrayList2 = arrayList;
        int n2 = arrayList.size();
        c c2 = arrayList2.get(n2 - 1);
        int n3 = 0;
        Iterator<e> iterator = c2.b.iterator();
        while (true) {
            int n4;
            block13: {
                Object object;
                block12: {
                    if (!iterator.hasNext()) break;
                    object = iterator.next();
                    if (!((e)object).a.a.equals("continuous")) break block12;
                    object = ((e)object).a;
                    n4 = ((a.b)object).b + ((a.b)object).c;
                    break block13;
                }
                n4 = ((e)object).a.b + 48;
            }
            if (n4 <= n3) continue;
            n3 = n4;
        }
        try {
            return n3 + c2.a;
        }
        catch (Exception exception) {
            exception.printStackTrace();
            return n;
        }
    }

    /*
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    public int a(int n) {
        try {
            for (int n2 = 0; n2 < this.b.size(); ++n2) {
                for (int n3 = 0; n3 < this.b.get((int)n2).b.size(); ++n3) {
                    if (this.b.get((int)n2).a <= n && this.b.get((int)n2).a + this.b.get((int)n2).b.get((int)n3).a.b >= n) {
                        return n2;
                    }
                    if (this.b.get((int)n2).a <= n) continue;
                    return n2 - 1;
                }
            }
            return Integer.MIN_VALUE;
        }
        catch (Throwable throwable) {
            throwable.printStackTrace();
        }
        return Integer.MIN_VALUE;
    }
}

