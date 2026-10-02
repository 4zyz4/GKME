/*
 * Decompiled with CFR 0.152.
 */
package c;

import a.b;
import a.e;
import java.util.ArrayList;
import java.util.Iterator;

public class c {
    public int a;
    public ArrayList<e> b;

    public int a() {
        Iterator<e> iterator;
        int n = 0;
        try {
            iterator = this.b.iterator();
        }
        catch (Exception exception) {
            exception.printStackTrace();
            return 0;
        }
        while (true) {
            int n2;
            block10: {
                Object object;
                block9: {
                    if (!iterator.hasNext()) break;
                    object = iterator.next();
                    if (!((e)object).a.a.equals("continuous")) break block9;
                    object = ((e)object).a;
                    n2 = ((b)object).b + ((b)object).c;
                    break block10;
                }
                n2 = ((e)object).a.b + 48;
            }
            if (n2 <= n) continue;
            n = n2;
        }
        return n;
    }
}

