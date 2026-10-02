/*
 * Decompiled with CFR 0.152.
 */
package b;

import a.c;
import a.e;
import b.b;
import java.util.ArrayList;

public class a
implements c {
    public b a;
    public ArrayList<e> b;

    @Override
    public int a() {
        return this.a.a;
    }

    @Override
    public int getDuration() {
        a.b b2;
        int n;
        block6: {
            n = 0;
            ArrayList<e> arrayList = this.b;
            ArrayList<e> arrayList2 = arrayList;
            int n2 = arrayList.size();
            b2 = arrayList2.get((int)(n2 - 1)).a;
            if (!"continuous".equals(b2.a)) break block6;
            n = b2.b + b2.c;
        }
        try {
            n = b2.b + 48;
        }
        catch (Exception exception) {
            exception.printStackTrace();
        }
        return n;
    }
}

