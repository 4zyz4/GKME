/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.json.JSONObject
 *  org.json.JSONStringer
 */
package com.apprichtap.haptic.base;

import a.d;
import c.a;
import c.c;
import com.apprichtap.haptic.base.e;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import org.json.JSONObject;
import org.json.JSONStringer;

public class b {
    private static final String a = "HEFileUtils";
    public static final int b = 48;
    public static final String c = "{\n    \"Metadata\": {\n        \"Created\": \"2020-08-10\",\n        \"Description\": \"Haptic editor design\",\n        \"Version\": 2\n    },\n    \"PatternList\": [\n       {\n        \"AbsoluteTime\": 0,\n          ReplaceMe\n       }\n    ]\n}";
    public static final String d = "Pattern";
    public static final String e = "PatternList";
    public static final String f = "AbsoluteTime";
    public static final String g = "Index";
    public static final String h = "continuous";
    public static final String i = "transient";
    public static final String j = "Event";
    public static final String k = "RelativeTime";
    public static final String l = "Duration";
    public static final String m = "Type";
    public static final String n = "Parameters";
    public static final String o = "Intensity";
    public static final String p = "Frequency";
    public static final String q = "Curve";
    public static final String r = "Time";
    public static final String s = "Created";
    public static final String t = "Description";
    public static final String u = "Version";
    public static final String v = "Metadata";
    public static final int w = 4096;
    public static final int x = 4097;
    public static final int y = 16;
    public static final int z = 4;
    public static final int A = 2;
    public static final int B = 1;
    public static final int C = 255;
    public static final int D = 0;
    public static final int E = 100;
    public static final int F = -200;
    public static final int G = 200;
    public static final int H = 17;
    public static final int I = 100;
    public static final int J = 16;
    public static final int K = 5000;
    public static final int L = 48;
    private static final long M = 48L;
    private static final boolean N = false;

    public static int e(String heString) {
        String string;
        int n = 0;
        try {
            n = new JSONObject(string).getJSONObject(v).getInt(u);
        }
        catch (Exception exception) {
            e.a.b(a, "getHeVersion ERROR, heString:" + string);
            exception.printStackTrace();
        }
        return n;
    }

    public static a.c e(String object, int n) {
        e.a.a(a, "getHeRootFromHeString, HE version:" + n);
        if (n != 1) {
            if (n != 2) {
                return null;
            }
            String string = object;
            object = null;
            try {
                object = com.apprichtap.haptic.base.b.d(string);
            }
            catch (Exception exception) {
                exception.printStackTrace();
            }
            return object;
        }
        String string = object;
        object = null;
        try {
            object = com.apprichtap.haptic.base.b.c(string);
        }
        catch (Exception exception) {
            exception.printStackTrace();
        }
        return object;
    }

    /*
     * Unable to fully structure code
     */
    public static b.a c(String var0) {
        v0 = new JSONObject((String)var0);
        var0 = v1;
        try {
            new b.a().a = new b.b();
            new b.a().b = new ArrayList<E>();
            var1_1 = v0.getJSONArray("Pattern");
            var2_2 = 0;
        }
        catch (Exception v11) {
            v11.printStackTrace();
            return null;
        }
        while (true) {
            block17: {
                if (var2_2 >= var1_1.length()) break;
                v2 = (JSONObject)var1_1.get(var2_2);
                var3_3 = v3;
                new a.e().a = new a.b();
                var4_4 = v2.getJSONObject("Event");
                var3_3.a.a = var4_4.getString("Type");
                if (!"continuous".equals(var3_3.a.a)) ** GOTO lbl23
                var3_3.a.c = var4_4.getInt("Duration");
lbl23:
                // 2 sources

                v4 = var3_3;
                v5 = v4;
                v6 = v4;
                v7 = v4;
                v8 = v4;
                var3_3.a.b = var4_4.getInt("RelativeTime");
                var4_4 = var4_4.getJSONObject("Parameters");
                v5.a.e = new d();
                v6.a.e.b = var4_4.getInt("Frequency");
                v7.a.e.a = var4_4.getInt("Intensity");
                v8.a.e.c = new ArrayList<E>();
                if (!"continuous".equals(var3_3.a.a)) break block17;
                var4_4 = var4_4.getJSONArray("Curve");
                var5_5 = 0;
                while (true) {
                    if (var5_5 >= var4_4.length()) break;
                    v9 = var3_3;
                    var6_6 = (JSONObject)var4_4.get(var5_5);
                    var7_7 = v10;
                    var7_7();
                    var7_7.c = var6_6.getInt("Frequency");
                    var7_7.b = var6_6.getDouble("Intensity");
                    v10.a = var6_6.getInt("Time");
                    v9.a.e.c.add(var7_7);
                    ++var5_5;
                }
            }
            var0.b.add(var3_3);
            ++var2_2;
        }
        return var0;
    }

    public static String a(b.a object) {
        JSONStringer jSONStringer;
        JSONStringer jSONStringer2;
        b.a a2 = object;
        JSONStringer jSONStringer3 = jSONStringer2;
        new JSONStringer().object();
        JSONStringer jSONStringer4 = jSONStringer3.key(v).object().key(s).value((Object)((b.a)object).a.b).key(t).value((Object)((b.a)object).a.c).key(u);
        long l = ((b.a)object).a.a;
        jSONStringer4.value(l).endObject();
        jSONStringer3.key(d).array();
        object = a2.b.iterator();
        while (true) {
            block33: {
                Object object2;
                block32: {
                    if (!object.hasNext()) break;
                    JSONStringer jSONStringer5 = jSONStringer3;
                    JSONStringer jSONStringer6 = jSONStringer5;
                    JSONStringer jSONStringer7 = jSONStringer5;
                    object2 = (a.e)object.next();
                    jSONStringer6.object();
                    JSONStringer jSONStringer8 = jSONStringer7.key(j).object().key(m).value((Object)((a.e)object2).a.a).key(k);
                    long l2 = ((a.e)object2).a.b;
                    jSONStringer8.value(l2);
                    if (!h.equals(((a.e)object2).a.a)) break block32;
                    JSONStringer jSONStringer9 = jSONStringer3.key(l);
                    long l3 = ((a.e)object2).a.c;
                    jSONStringer9.value(l3);
                }
                JSONStringer jSONStringer10 = jSONStringer3.key(n).object().key(p);
                long l4 = ((a.e)object2).a.e.b;
                JSONStringer jSONStringer11 = jSONStringer10.value(l4).key(o);
                long l5 = ((a.e)object2).a.e.a;
                jSONStringer11.value(l5);
                if (!h.equals(((a.e)object2).a.a)) break block33;
                a.e e2 = object2;
                jSONStringer3.key(q).array();
                object2 = e2.a.e.c.iterator();
                while (true) {
                    if (!object2.hasNext()) break;
                    JSONStringer jSONStringer12 = jSONStringer3;
                    a.a a3 = (a.a)object2.next();
                    JSONStringer jSONStringer13 = jSONStringer12.object().key(p);
                    long l6 = a3.c;
                    JSONStringer jSONStringer14 = jSONStringer13.value(l6).key(o).value(a3.b).key(r);
                    long l7 = a3.a;
                    jSONStringer14.value(l7).endObject();
                }
                jSONStringer3.endArray();
            }
            jSONStringer3.endObject().endObject().endObject();
        }
        try {
            JSONStringer jSONStringer15 = jSONStringer3;
            jSONStringer = jSONStringer15;
            jSONStringer15.endArray().endObject();
        }
        catch (Exception exception) {
            exception.printStackTrace();
            return null;
        }
        return jSONStringer.toString();
    }

    /*
     * Unable to fully structure code
     */
    public static a d(String var0) {
        if (2 != com.apprichtap.haptic.base.b.e((String)var0)) {
            return null;
        }
        v0 = new JSONObject((String)var0);
        var0 = v1;
        try {
            new a().a = new c.b();
            new a().b = new ArrayList<E>();
            var1_1 = v0.getJSONArray("PatternList");
            var2_2 = 0;
        }
        catch (Exception v13) {
            v13.printStackTrace();
            return null;
        }
        while (true) {
            if (var2_2 >= var1_1.length()) break;
            var3_3 = (JSONObject)var1_1.get(var2_2);
            var4_4 = v2;
            var4_4();
            v2.a = var3_3.getInt("AbsoluteTime");
            v2.b = new ArrayList<E>();
            var3_3 = var3_3.getJSONArray("Pattern");
            var5_5 = 0;
            while (true) {
                block25: {
                    if (var5_5 >= var3_3.length()) break;
                    v3 = (JSONObject)var3_3.get(var5_5);
                    var6_6 = v4;
                    new a.e().a = new a.b();
                    var7_7 = v3.getJSONObject("Event");
                    var6_6.a.a = var7_7.getString("Type");
                    if (!"continuous".equals(var6_6.a.a)) ** GOTO lbl38
                    var6_6.a.c = var7_7.getInt("Duration");
                    ** GOTO lbl41
lbl38:
                    // 1 sources

                    if (!"transient".equals(var6_6.a.a)) ** GOTO lbl41
                    var6_6.a.c = 48;
lbl41:
                    // 3 sources

                    v5 = var6_6;
                    v6 = v5;
                    v7 = v5;
                    v8 = v5;
                    v9 = v5;
                    v10 = var6_6;
                    v10.a.b = var7_7.getInt("RelativeTime");
                    v10.a.d = var7_7.getInt("Index");
                    var7_7 = var7_7.getJSONObject("Parameters");
                    v6.a.e = new d();
                    v7.a.e.b = var7_7.getInt("Frequency");
                    v8.a.e.a = var7_7.getInt("Intensity");
                    v9.a.e.c = new ArrayList<E>();
                    if (!"continuous".equals(var6_6.a.a)) break block25;
                    var7_7 = var7_7.getJSONArray("Curve");
                    var8_8 = 0;
                    while (true) {
                        if (var8_8 >= var7_7.length()) break;
                        v11 = var6_6;
                        var9_9 = (JSONObject)var7_7.get(var8_8);
                        var10_10 = v12;
                        var10_10();
                        var10_10.c = var9_9.getInt("Frequency");
                        var10_10.b = var9_9.getDouble("Intensity");
                        v12.a = var9_9.getInt("Time");
                        v11.a.e.c.add(var10_10);
                        ++var8_8;
                    }
                }
                var4_4.b.add(var6_6);
                ++var5_5;
            }
            var0.b.add(var4_4);
            ++var2_2;
        }
        return var0;
    }

    public static String a(a object) {
        JSONStringer jSONStringer;
        JSONStringer jSONStringer2;
        a a2 = object;
        JSONStringer jSONStringer3 = jSONStringer2;
        new JSONStringer().object();
        JSONStringer jSONStringer4 = jSONStringer3.key(v).object().key(s).value((Object)((a)object).a.b).key(t).value((Object)((a)object).a.c).key(u);
        long l = ((a)object).a.a;
        jSONStringer4.value(l).endObject();
        jSONStringer3.key(e).array();
        object = a2.b.iterator();
        while (true) {
            if (!object.hasNext()) break;
            c c2 = (c)object.next();
            JSONStringer jSONStringer5 = jSONStringer3.object().key(f);
            long l2 = c2.a;
            jSONStringer5.value(l2).key(d).array();
            Iterator<a.e> iterator = c2.b.iterator();
            while (true) {
                block41: {
                    Object object2;
                    block40: {
                        if (!iterator.hasNext()) break;
                        JSONStringer jSONStringer6 = jSONStringer3;
                        JSONStringer jSONStringer7 = jSONStringer6;
                        JSONStringer jSONStringer8 = jSONStringer6;
                        object2 = iterator.next();
                        jSONStringer7.object();
                        JSONStringer jSONStringer9 = jSONStringer8.key(j).object().key(g);
                        long l3 = ((a.e)object2).a.d;
                        JSONStringer jSONStringer10 = jSONStringer9.value(l3).key(k);
                        long l4 = ((a.e)object2).a.b;
                        jSONStringer10.value(l4).key(m).value((Object)((a.e)object2).a.a);
                        if (!h.equals(((a.e)object2).a.a)) break block40;
                        JSONStringer jSONStringer11 = jSONStringer3.key(l);
                        long l5 = ((a.e)object2).a.c;
                        jSONStringer11.value(l5);
                    }
                    JSONStringer jSONStringer12 = jSONStringer3.key(n).object().key(p);
                    long l6 = ((a.e)object2).a.e.b;
                    JSONStringer jSONStringer13 = jSONStringer12.value(l6).key(o);
                    long l7 = ((a.e)object2).a.e.a;
                    jSONStringer13.value(l7);
                    if (!h.equals(((a.e)object2).a.a)) break block41;
                    a.e e2 = object2;
                    jSONStringer3.key(q).array();
                    object2 = e2.a.e.c.iterator();
                    while (true) {
                        if (!object2.hasNext()) break;
                        JSONStringer jSONStringer14 = jSONStringer3;
                        a.a a3 = (a.a)object2.next();
                        JSONStringer jSONStringer15 = jSONStringer14.object().key(p);
                        long l8 = a3.c;
                        JSONStringer jSONStringer16 = jSONStringer15.value(l8).key(o).value(a3.b).key(r);
                        long l9 = a3.a;
                        jSONStringer16.value(l9).endObject();
                    }
                    jSONStringer3.endArray();
                }
                jSONStringer3.endObject().endObject().endObject();
            }
            jSONStringer3.endArray().endObject();
        }
        try {
            JSONStringer jSONStringer17 = jSONStringer3;
            jSONStringer = jSONStringer17;
            jSONStringer17.endArray().endObject();
        }
        catch (Exception exception) {
            exception.printStackTrace();
            return null;
        }
        return jSONStringer.toString();
    }

    public static String a(String he10String) {
        Object object;
        Object object2 = null;
        try {
            object2 = com.apprichtap.haptic.base.b.c(he10String);
        }
        catch (Exception exception) {
            exception.printStackTrace();
        }
        if (object2 != null && (object = ((b.a)object2).b) != null && ((ArrayList)object).size() != 0) {
            c c2;
            a a2 = new a();
            a2.a = new c.b();
            a2.b = new ArrayList();
            object = c2;
            ((c)object)();
            c2.b = ((b.a)object2).b;
            c2.a = 0;
            a2.b.add((c)object);
            return com.apprichtap.haptic.base.b.a(a2);
        }
        e.a.d(a, " , convertHe10ToHe20, invalid HE1.0 string!");
        return "";
    }

    public static String a(c c2, boolean bl) {
        ArrayList arrayList;
        c.b b2;
        a a2;
        a a3 = a2;
        Object object = b2;
        b2 = new c.b();
        new a().a = object;
        object = arrayList;
        arrayList = new ArrayList();
        new a().b = object;
        if (bl) {
            c c3;
            object = c3;
            ((c)object)();
            ((c)object).a = 0;
            c3.b = c2.b;
            a3.b.add((c)object);
        } else {
            ((ArrayList)object).add(c2);
        }
        return com.apprichtap.haptic.base.b.a(a3);
    }

    public static int b(a a2) {
        if (com.apprichtap.haptic.player.a.a(a2)) {
            return a2.b.size();
        }
        return -1;
    }

    public static int b(String heString) {
        a a2 = com.apprichtap.haptic.base.b.d(heString);
        if (a2 != null) {
            return a2.getDuration();
        }
        return 0;
    }

    /*
     * WARNING - void declaration
     */
    public static int d(String heString, int heVersion) {
        Object object;
        if (heString != null && ((String)object).length() != 0) {
            void var1_1;
            if (!com.apprichtap.haptic.player.a.a((a.c)(object = com.apprichtap.haptic.base.b.e((String)object, (int)var1_1)))) {
                return 0;
            }
            return object.getDuration();
        }
        return 0;
    }

    /*
     * Exception decompiling
     */
    public static String a(String originalString, double multiple) {
        /*
         * This method has failed to decompile.  When submitting a bug report, please provide this stack trace, and (if you hold appropriate legal rights) the relevant class file.
         * 
         * org.benf.cfr.reader.util.ConfusedCFRException: Tried to end blocks [26[UNCONDITIONALDOLOOP]], but top level block is 21[TRYBLOCK]
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement.processEndingBlocks(Op04StructuredStatement.java:435)
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement.buildNestedBlocks(Op04StructuredStatement.java:484)
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op03SimpleStatement.createInitialStructuredBlock(Op03SimpleStatement.java:736)
         *     at org.benf.cfr.reader.bytecode.CodeAnalyser.getAnalysisInner(CodeAnalyser.java:850)
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
    public static String a(String he20String, int targetDuration) {
        String string;
        void var1_1;
        e.a.a(a, "alignHE20DurationToMedia, target duration:" + (int)var1_1);
        if (48 > var1_1) {
            e.a.d(a, "alignHE20DurationToMedia, target duration:" + (int)var1_1 + ", do nothing!");
            return string;
        }
        int n = com.apprichtap.haptic.base.b.d(string, 2);
        if (var1_1 - n > 48) {
            return com.apprichtap.haptic.base.b.b(string, (int)var1_1);
        }
        if (n - var1_1 > 48) {
            return com.apprichtap.haptic.base.b.g(string, (int)var1_1);
        }
        return string;
    }

    /*
     * WARNING - void declaration
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    private static String b(String he20String, int targetDuration) {
        ArrayList<Object> arrayList;
        void var1_1;
        c c2;
        a.e e2;
        a a2;
        a a3;
        Object object;
        String string;
        try {
            if (targetDuration - com.apprichtap.haptic.base.b.b(string) <= 48) {
                e.a.d(a, "extendHe20String, too closed!");
                return string;
            }
            object = com.apprichtap.haptic.base.b.d(string);
            if (!com.apprichtap.haptic.player.a.a((a.c)object)) {
                e.a.b(a, "extendHe20String(), invalid HE20");
                return null;
            }
            a a4 = object;
            a3 = a4;
            a2 = a4;
            e.a.a(a, "extendHe20String()original pattern count:" + ((a)object).b.size());
        }
        catch (Throwable throwable) {
            throwable.printStackTrace();
            return null;
        }
        object = e2;
        e2 = new a.e();
        ((a.e)object).a = new a.b();
        new a.b().a = i;
        new a.b().b = 0;
        new a.b().e = new d();
        d d2 = e2.a.e;
        d2.b = 0;
        d2.a = 0;
        c c3 = c2;
        void v7 = var1_1;
        c3();
        c2.a = v7 - 48;
        ArrayList<Object> arrayList2 = arrayList;
        ArrayList<Object> arrayList3 = arrayList2;
        arrayList3();
        c3.b = arrayList3;
        arrayList.add(object);
        a3.b.add(c3);
        String string2 = com.apprichtap.haptic.base.b.a(a2);
        com.apprichtap.haptic.base.e.a("extend20string_in.he", string);
        com.apprichtap.haptic.base.e.a("extend20string_out.he", string2);
        return string2;
    }

    /*
     * Unable to fully structure code
     * Could not resolve type clashes
     */
    private static String g(String he20String, int targetDuration) {
        block44: {
            block47: {
                block46: {
                    block45: {
                        block43: {
                            block42: {
                                e.a.a("HEFileUtils", "shrinkHe20String:" + (int)var1_1);
                                if (com.apprichtap.haptic.base.b.b(he20String) - var1_1 > 48) ** GOTO lbl7
                                e.a.d("HEFileUtils", "shrinkHe20String, too closed!");
                                return var0;
lbl7:
                                // 1 sources

                                var2_3 = com.apprichtap.haptic.base.b.d(var0);
                                if (com.apprichtap.haptic.player.a.a((a.c)var2_3)) break block42;
                                e.a.b("HEFileUtils", "shrinkHe20String(), invalid HE20");
                                return null;
                            }
                            v0 = var2_3;
                            e.a.a("HEFileUtils", "shrinkHe20String() original pattern count:" + var2_3.b.size());
                            var3_4 = -1;
                            var4_6 = -1;
                            var5_7 = v0.b.iterator();
                            block39: while (true) {
                                if (!var5_7.hasNext()) break block43;
                                var6_8 = var5_7.next();
                                var7_9 = var6_8.b;
                                if (var7_9 == null) continue;
                                var7_9 = var7_9.iterator();
                                while (true) {
                                    if (var7_9.hasNext()) ** break;
                                    continue block39;
                                    var8_10 = (a.e)var7_9.next();
                                    var9_11 = var8_10.a;
                                    if (var9_11 == null) continue;
                                    v1 = var9_11.b + var9_11.c;
                                    if (v1 + var6_8.a >= var1_1) break block39;
                                    continue;
                                    break;
                                }
                                break;
                            }
                            v2 = var2_3;
                            var4_6 = var6_8.b.indexOf(var8_10);
                            var3_4 = v2.b.indexOf(var6_8);
                        }
                        e.a.a("HEFileUtils", "shrinkHe20String targetPatternListItemIndex:" + var3_4 + ", targetPatternItemIndex:" + var4_6);
                        if (-1 == var3_4 || -1 == var4_6) break block44;
                        if (var4_6 != 0 || var3_4 != 0) break block45;
                        v3 = var2_3.b;
                        try {
                            v4 /* !! */  = v3.subList(var3_4, v3.size());
lbl59:
                            // 4 sources

                            while (true) {
                                v4 /* !! */ .clear();
                                ** GOTO lbl114
                                break;
                            }
                        }
                        catch (Throwable v5) {
                            v5.printStackTrace();
                            return null;
                        }
                    }
                    if (var4_6 != 0 || var3_4 <= 0) break block46;
                    v6 = var2_3.b;
                    v4 /* !! */  = v6.subList(var3_4, v6.size());
                    ** GOTO lbl59
                }
                if (var4_6 <= 0 || var3_4 != 0) break block47;
                v7 = var2_3;
                v8 = v7;
                v9 = v7.b;
                ++var3_4;
                v9.subList(var3_4, v9.size()).clear();
                v10 = v8.b;
                v11 = v10;
                v12 = v10.size();
                v13 = v11.get((int)(v12 - 1)).b;
                v14 = var4_6;
                v15 = var2_3.b;
                v16 = v15;
                v17 = v15.size();
                v4 /* !! */  = v13.subList(v14, v16.get((int)(v17 - 1)).b.size());
                ** GOTO lbl59
            }
            if (var4_6 <= 0 || var3_4 <= 0) ** GOTO lbl114
            v18 = var2_3;
            v19 = v18;
            v20 = v18.b;
            ++var3_4;
            v20.subList(var3_4, v20.size()).clear();
            v21 = v19.b;
            v22 = v21;
            v23 = v21.size();
            v24 = v22.get((int)(v23 - 1)).b;
            v25 = var4_6;
            v26 = var2_3.b;
            v27 = v26;
            v28 = v26.size();
            v4 /* !! */  = v24.subList(v25, v27.get((int)(v28 - 1)).b.size());
            ** continue;
lbl114:
            // 2 sources

            v29 = var2_3;
            v30 = v29;
            v31 = v29;
            e.a.a("HEFileUtils", "shrinkHe20String()  pattern count:" + var2_3.b.size());
            var2_3 = v32;
            v33 = var1_1;
            var2_3();
            v32.a = v33 - 48;
            v32.b = new ArrayList<E>();
            var1_2 = v34;
            v34 = new a.b();
            var1_2.e = new d();
            new d().a = 0;
            new d().b = 0;
            v34.a = "transient";
            var3_5 = v35;
            var3_5();
            v35.a = var1_2;
            v32.b.add(var3_5);
            v30.b.add((c)var2_3);
            v36 = com.apprichtap.haptic.base.b.a(v31);
            com.apprichtap.haptic.base.e.a("shrink20string_in.he", var0);
            com.apprichtap.haptic.base.e.a("shrink20string_out.he", v36);
            return v36;
        }
        return null;
    }

    /*
     * Exception decompiling
     */
    public static String f(String he20String, int millTime) {
        /*
         * This method has failed to decompile.  When submitting a bug report, please provide this stack trace, and (if you hold appropriate legal rights) the relevant class file.
         * 
         * org.benf.cfr.reader.util.ConfusedCFRException: Tried to end blocks [24[UNCONDITIONALDOLOOP]], but top level block is 12[TRYBLOCK]
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement.processEndingBlocks(Op04StructuredStatement.java:435)
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement.buildNestedBlocks(Op04StructuredStatement.java:484)
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op03SimpleStatement.createInitialStructuredBlock(Op03SimpleStatement.java:736)
         *     at org.benf.cfr.reader.bytecode.CodeAnalyser.getAnalysisInner(CodeAnalyser.java:850)
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
    public static String c(String he20String, int afterTime) {
        ArrayList<c> arrayList;
        Object object;
        if (afterTime == 0) {
            return object;
        }
        String string = object;
        object = null;
        try {
            object = com.apprichtap.haptic.base.b.d(string);
        }
        catch (Exception exception) {
            exception.printStackTrace();
        }
        if (object != null && (arrayList = ((a)object).b) != null && arrayList.size() != 0) {
            void var1_1;
            int n = -1;
            int n2 = -1;
            block2: for (c c2 : ((a)object).b) {
                ArrayList<a.e> arrayList2 = c2.b;
                if (arrayList2 == null) continue;
                for (a.e e2 : arrayList2) {
                    a.b b2 = e2.a;
                    if (b2 == null || b2.b + c2.a < var1_1) continue;
                    n2 = c2.b.indexOf(e2);
                    n = ((a)object).b.indexOf(c2);
                    break block2;
                }
            }
            if (n >= 0 && n2 >= 0) {
                Object object2 = object;
                ((a)object2).b.subList(0, n).clear();
                ((a)object2).b.get((int)0).b.subList(0, n2).clear();
                e.a.a(a, "  generatePartialHe20String, targetPatternListItemIndex:" + n + ",targetPatterItemIndex:" + n2 + ", PatternList size:" + ((a)object).b.size() + ",Pattern size:" + ((a)object).b.get((int)0).b.size());
                for (c c3 : ((a)object2).b) {
                    ArrayList<a.e> arrayList3 = c3.b;
                    if (arrayList3 == null) continue;
                    int n3 = c3.a;
                    if (n3 < var1_1) {
                        Iterator<a.e> iterator = arrayList3.iterator();
                        while (iterator.hasNext()) {
                            a.b b3 = iterator.next().a;
                            if (b3 == null) continue;
                            b3.b = b3.b + c3.a - var1_1;
                        }
                        c3.a = 0;
                        continue;
                    }
                    c3.a = n3 - var1_1;
                }
                if (n2 == 0 && ((a)object).b.get((int)0).a != 0) {
                    a.e e3;
                    a.b b4;
                    c c4;
                    c c3 = c4;
                    new c().a = 0;
                    c3.b = new ArrayList();
                    a.b b5 = b4;
                    b5.e = new d();
                    new d().a = 0;
                    new d().b = 0;
                    new a.b().a = i;
                    a.e e4 = e3;
                    e4();
                    e3.a = b5;
                    c3.b.add(e4);
                    ((a)object).b.add(0, c3);
                }
                return com.apprichtap.haptic.base.b.a((a)object);
            }
            return "";
        }
        e.a.d(a, "  generatePartialHe20String, source HE invalid!");
        return "";
    }

    public static String f(String he20String) {
        Object object = null;
        try {
            object = com.apprichtap.haptic.base.b.d(he20String);
        }
        catch (Exception exception) {
            exception.printStackTrace();
        }
        if (!com.apprichtap.haptic.player.a.a((a.c)object)) {
            e.a.d(a, " , trim16pTo4p, invalid HE2.0 string!");
            return "";
        }
        Iterator<c> iterator = ((a)object).b.iterator();
        while (iterator.hasNext()) {
            for (a.e e2 : iterator.next().b) {
                e2.a.e.c = com.apprichtap.haptic.base.b.a(e2.a.e.c);
                if (!e2.a.a.equals(i)) continue;
                d d2 = e2.a.e;
                int n = d2.b;
                if (n < 0) {
                    d2.b = 0;
                    continue;
                }
                if (n <= 100) continue;
                d2.b = 100;
            }
        }
        return com.apprichtap.haptic.base.b.a((a)object);
    }

    private static ArrayList<a.a> a(ArrayList<a.a> curveItems) {
        ArrayList<a.a> arrayList;
        if (curveItems != null && arrayList.size() != 0) {
            a.a a2;
            a.a a3;
            int n = arrayList.size();
            e.a.a(a, "trimTo4p size:" + n);
            if (n > 0 && n <= 4) {
                return arrayList;
            }
            a.a a4 = a3;
            a3 = new a.a();
            int n2 = n - 2;
            int n3 = n2 / 2;
            for (int i2 = 1; i2 <= n3; ++i2) {
                a.a a5 = a4;
                a5.a += arrayList.get((int)i2).a;
                a5.b += arrayList.get((int)i2).b;
                a5.c += arrayList.get((int)i2).c;
            }
            a.a a6 = a4;
            a6.a /= n3;
            a6.b /= (double)n3;
            a6.c /= n3;
            a.a a7 = a2;
            a2 = new a.a();
            for (int i3 = n3 + 1; i3 <= n2; ++i3) {
                a.a a8 = a7;
                a8.a += arrayList.get((int)i3).a;
                a8.b += arrayList.get((int)i3).b;
                a8.c += arrayList.get((int)i3).c;
            }
            ArrayList<a.a> arrayList2 = arrayList;
            int n4 = n;
            a.a a9 = a7;
            n = n2 - n3;
            a9.a /= n;
            a9.b /= (double)n;
            a9.c /= n;
            e.a.a(a, "trimTo4p size:" + arrayList.size());
            arrayList2.subList(1, n4 - 1).clear();
            arrayList2.add(1, a4);
            arrayList2.add(2, a7);
            return arrayList2;
        }
        return null;
    }

    /*
     * Exception decompiling
     */
    public static String g(String he20String) {
        /*
         * This method has failed to decompile.  When submitting a bug report, please provide this stack trace, and (if you hold appropriate legal rights) the relevant class file.
         * 
         * org.benf.cfr.reader.util.ConfusedCFRException: Tried to end blocks [44[UNCONDITIONALDOLOOP], 41[UNCONDITIONALDOLOOP]], but top level block is 38[TRYBLOCK]
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement.processEndingBlocks(Op04StructuredStatement.java:435)
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement.buildNestedBlocks(Op04StructuredStatement.java:484)
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op03SimpleStatement.createInitialStructuredBlock(Op03SimpleStatement.java:736)
         *     at org.benf.cfr.reader.bytecode.CodeAnalyser.getAnalysisInner(CodeAnalyser.java:850)
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
    public static void a(String heString, ArrayList<Long> timings, ArrayList<Integer> amplitudes) {
        void var2_2;
        void var1_1;
        Object object;
        if (heString != null && ((String)object).length() != 0 && var1_1 != null && var2_2 != null) {
            if (!a.c.a((a.c)(object = com.apprichtap.haptic.base.b.e((String)object, 1)))) {
                e.a.d(a, "convertHE10StringToGoogleWaveform, invalid heRoot!");
                return;
            }
            if (1 != object.a()) {
                e.a.d(a, "convertHE10StringToGoogleWaveform, invalid HE version:" + object.a());
                return;
            }
            var1_1.clear();
            var2_2.clear();
            var1_1.add(new Long(0L));
            var2_2.add(0);
            long l = 0L;
            for (a.e e2 : ((b.a)object).b) {
                ArrayList<a.a> arrayList;
                if (e2 == null || (arrayList = e2.a) == null || ((a.b)((Object)arrayList)).e == null) continue;
                if (i.equals(((a.b)((Object)arrayList)).a)) {
                    int n = e2.a.b;
                    if ((long)n > l) {
                        void v0 = var1_1;
                        var1_1.add(new Long(n) - l);
                        var2_2.add(0);
                        l += ((Long)v0.get(v0.size() - 1)).longValue();
                    }
                    void v1 = var1_1;
                    d d2 = e2.a.e;
                    var1_1.add(new Long(com.apprichtap.haptic.base.b.c(d2.a, d2.b)));
                    var2_2.add((int)((double)e2.a.e.a * 1.0 / 100.0 * 255.0));
                    l += ((Long)v1.get(v1.size() - 1)).longValue();
                    continue;
                }
                if (h.equals(e2.a.a)) {
                    arrayList = e2.a.e.c;
                    if (arrayList == null || 4 != arrayList.size()) continue;
                    int n = e2.a.b;
                    if ((long)n > l) {
                        void v2 = var1_1;
                        var1_1.add(new Long(n) - l);
                        var2_2.add(0);
                        l += ((Long)v2.get(v2.size() - 1)).longValue();
                    }
                    void v3 = var1_1;
                    var1_1.add(new Long(com.apprichtap.haptic.base.b.a(e2.a.c)));
                    d d3 = e2.a.e;
                    var2_2.add((int)((double)com.apprichtap.haptic.base.b.b(d3.a, d3.b) * 1.0 / 100.0 * 255.0));
                    l += ((Long)v3.get(v3.size() - 1)).longValue();
                    continue;
                }
                e.a.b(a, "unknown type!");
            }
            if (com.apprichtap.haptic.base.e.b()) {
                object = "";
                String string = "";
                Iterator iterator = var1_1.iterator();
                while (iterator.hasNext()) {
                    object = (String)object + ((Long)iterator.next()).toString() + ",";
                }
                iterator = var2_2.iterator();
                while (iterator.hasNext()) {
                    string = string + ((Integer)iterator.next()).toString() + ",";
                }
                e.a.a(a, "timings size:" + var1_1.size() + ",amplitudes size:" + var2_2.size() + "\n timings:" + (String)object + "\n amplitudes:" + string);
            }
            return;
        }
        e.a.b(a, "convertHE10StringToWaveformParams(), invalid parameters.");
    }

    /*
     * WARNING - void declaration
     */
    public static int c(int intensity, int freq) {
        int n;
        void var1_1;
        if (freq >= 41 && var1_1 <= 68) {
            if (n > 0 && n < 50) {
                return 15;
            }
            if (n >= 50 && n < 75) {
                return 20;
            }
            if (n >= 75 && n <= 100) {
                return 30;
            }
        } else {
            if (n > 0 && n < 50) {
                return 10;
            }
            if (n >= 50 && n <= 100) {
                return 15;
            }
        }
        return 0;
    }

    public static int a(int durationOnRichTap) {
        int n;
        if (durationOnRichTap > 50 && n < 100) {
            n = 50;
        } else if (n > 100) {
            n -= 50;
        }
        return n;
    }

    /*
     * WARNING - void declaration
     * Enabled force condition propagation
     * Lifted jumps to return sites
     */
    public static int b(int intensityOnRichTap, int freqOnRichTap) {
        int n;
        void var1_1;
        if (-1 == var1_1) {
            return 100;
        }
        if (30 > var1_1) {
            return 0;
        }
        if (100 < var1_1) return 100;
        return n;
    }

    public static String a(String stringHE20, boolean trimCurvePoints) {
        Object object;
        if (trimCurvePoints) {
            object = com.apprichtap.haptic.base.b.f((String)object);
        }
        if ((object = com.apprichtap.haptic.base.b.g((String)object)) != null && ((String)object).length() != 0) {
            b.a a2;
            if (!a.c.a((a.c)(object = com.apprichtap.haptic.base.b.d((String)object)))) {
                e.a.b(a, "convertHE20ToHE10, empty HeRoot");
                return null;
            }
            Iterator<a.e> iterator = ((a)object).b.get((int)0).b.iterator();
            while (iterator.hasNext()) {
                iterator.next().a.b += ((a)object).b.get((int)0).a;
            }
            Object object2 = object;
            object = a2;
            new b.a().a = new b.b();
            ((b.a)object).b = ((a)object2).b.get((int)0).b;
            for (a.e e2 : ((b.a)object).b) {
                a.b b2 = e2.a;
                if (b2 == null || !h.equals(b2.a)) continue;
                d d2 = e2.a.e;
                if (-1 != d2.b) continue;
                d2.b = 56;
                Iterator<a.a> iterator2 = d2.c.iterator();
                while (iterator2.hasNext()) {
                    iterator2.next().c = 0;
                }
            }
            String string = com.apprichtap.haptic.base.b.a((b.a)object);
            e.a.a(a, "convertHE20ToHE10 result:" + string);
            return string;
        }
        e.a.b(a, "convertHE20ToHE10, null after trim");
        return null;
    }

    /*
     * WARNING - void declaration
     */
    public static void b(String heString, ArrayList<Long> timings, ArrayList<Integer> amplitudes) {
        void var2_2;
        void var1_1;
        Object object;
        if (heString != null && ((String)object).length() != 0 && var1_1 != null && var2_2 != null) {
            if (!com.apprichtap.haptic.player.a.a((a.c)(object = com.apprichtap.haptic.base.b.e((String)object, 2)))) {
                return;
            }
            var1_1.clear();
            var2_2.clear();
            var1_1.add(new Long(0L));
            var2_2.add(0);
            int n = object.a();
            if (n != 1) {
                if (n == 2) {
                    long l = 0L;
                    for (c c2 : ((a)object).b) {
                        for (a.e e2 : c2.b) {
                            int n2;
                            a.b b2;
                            if (e2 == null || (b2 = e2.a) == null || b2.e == null || 2 == b2.d) continue;
                            if (i.equals(b2.a)) {
                                int n3 = e2.a.b;
                                n2 = c2.a;
                                if ((long)(n3 + n2) > l) {
                                    void v0 = var1_1;
                                    var1_1.add(new Long(n3 + n2) - l);
                                    var2_2.add(0);
                                    l += ((Long)v0.get(v0.size() - 1)).longValue();
                                }
                                if (100 == e2.a.e.a) {
                                    Long l2;
                                    Long l3 = l2;
                                    l2 = new Long(75L);
                                    var1_1.add(l3);
                                } else {
                                    Long l4;
                                    Long l5 = l4;
                                    l4 = new Long(30L);
                                    var1_1.add(l5);
                                }
                                void v3 = var1_1;
                                var2_2.add(255);
                                l += ((Long)v3.get(v3.size() - 1)).longValue();
                                continue;
                            }
                            if (h.equals(e2.a.a)) {
                                ArrayList<a.a> arrayList;
                                int n4 = e2.a.b;
                                n2 = c2.a;
                                if ((long)(n4 + n2) > l) {
                                    void v4 = var1_1;
                                    var1_1.add(new Long(n4 + n2) - l);
                                    var2_2.add(0);
                                    l += ((Long)v4.get(v4.size() - 1)).longValue();
                                }
                                if ((arrayList = e2.a.e.c) == null || 4 > arrayList.size()) continue;
                                var1_1.add(new Long(e2.a.c));
                                if (4 == e2.a.e.c.size()) {
                                    var2_2.add(153);
                                } else if (6 == e2.a.e.c.size()) {
                                    var2_2.add(255);
                                } else {
                                    var2_2.add(127);
                                }
                                void v5 = var1_1;
                                l += ((Long)v5.get(v5.size() - 1)).longValue();
                                continue;
                            }
                            e.a.b(a, "unknown type!");
                        }
                    }
                }
            } else {
                e.a.c(a, "convertM2VHeStringToWaveformParams, HE VERSION == 1, NOT A M2V HE, do nothing!");
            }
            var1_1.remove(0);
            var2_2.remove(0);
            if (com.apprichtap.haptic.base.e.b()) {
                object = "";
                String string = "";
                Iterator iterator = var1_1.iterator();
                while (iterator.hasNext()) {
                    object = (String)object + ((Long)iterator.next()).toString() + ",";
                }
                iterator = var2_2.iterator();
                while (iterator.hasNext()) {
                    string = string + ((Integer)iterator.next()).toString() + ",";
                }
                e.a.a(a, "timings size:" + var1_1.size() + ",amplitudes size:" + var2_2.size() + "\n timings:" + (String)object + "\n amplitudes:" + string);
            }
            return;
        }
        e.a.b(a, "convertM2VHeStringToWaveformParams(), invalid parameters.");
    }

    /*
     * WARNING - void declaration
     */
    public static String a(String he20String, int deltaIntensity, int deltaFreq, int majorCoreVersion) {
        Iterator<c> iterator;
        void var3_4;
        void var1_1;
        void var2_3;
        e.a.a(a, "overwriteBaseFrequencyAndIntensityOfHe20String, deltaFreq:" + (int)var2_3 + ", deltaIntensity:" + (int)var1_1 + ", majorCoreVersion:" + (int)var3_4);
        if (deltaFreq == 0 && (255 == var1_1 || 256 == var1_1)) {
            e.a.a(a, "overwriteBaseFrequencyAndIntensityOfHe20String, do nothing!");
            return iterator;
        }
        double d2 = 255 != var1_1 && 256 != var1_1 ? ((double)var1_1 - 255.5) / 255.5 : 0.0;
        double d3 = (double)(var2_3 - false) / 100.0;
        e.a.a(a, "overwriteBaseFrequencyAndIntensityOfHe20String, freqRatio:" + d3 + ", intensityRatio:" + d2);
        a a2 = com.apprichtap.haptic.base.b.d((String)((Object)iterator));
        if (!com.apprichtap.haptic.player.a.a(a2)) {
            e.a.a(a, "overwriteBaseFrequencyAndIntensityOfHe20String, do nothing as invalid he20String");
            return iterator;
        }
        iterator = a2.b.iterator();
        while (iterator.hasNext()) {
            for (a.e e2 : ((c)iterator.next()).b) {
                double d4;
                int n;
                int n2;
                d d5;
                if (i.equals(e2.a.a)) {
                    d5 = e2.a.e;
                    n2 = d5.b;
                    if (n2 == 0 && d5.a == 0) {
                        e.a.a(a, "overwriteBaseFrequencyAndIntensityOfHe20String, ignore placeholder event!");
                        continue;
                    }
                    d5.b = var3_4 >= 24 ? (int)(d3 >= 0.0 ? (double)n2 + (double)(150 - n2) * d3 : (double)n2 + (double)(n2 - -50) * d3) : (int)(d3 >= 0.0 ? (double)n2 + (double)(100 - n2) * d3 : (double)n2 + (double)(n2 - 0) * d3);
                } else if (h.equals(e2.a.a)) {
                    double d6;
                    d5 = e2.a.e;
                    if (d3 >= 0.0) {
                        n2 = d5.b;
                        d6 = (double)n2 + (double)(100 - n2) * d3;
                    } else {
                        n2 = d5.b;
                        d6 = (double)n2 + (double)(n2 - 0) * d3;
                    }
                    d5.b = (int)d6;
                }
                d d7 = e2.a.e;
                if (d2 >= 0.0) {
                    n = d7.a;
                    d4 = (double)n + (double)(100 - n) * d2;
                } else {
                    n = d7.a;
                    d4 = (double)n + (double)(n - 0) * d2;
                }
                d7.a = (int)d4;
            }
        }
        String string = com.apprichtap.haptic.base.b.a(a2);
        e.a.a(a, "overwriteBaseFrequencyAndIntensityOfHe20String, result:" + string);
        return string;
    }

    /*
     * WARNING - void declaration
     */
    public static int[] a(int intensity, int frequency) {
        void var1_1;
        int n;
        int[] nArray = new int[17];
        Arrays.fill(nArray, 0);
        nArray[0] = 1;
        nArray[1] = 4097;
        nArray[2] = 0;
        nArray[3] = n;
        nArray[4] = var1_1;
        nArray[5] = 0;
        return nArray;
    }

    /*
     * Exception decompiling
     */
    public static int[] a(String stringHE, int versionHE, int coreMajorVersion, int minorCoreVersion, int pid, int sid, boolean vibrationIdSwapped) {
        /*
         * This method has failed to decompile.  When submitting a bug report, please provide this stack trace, and (if you hold appropriate legal rights) the relevant class file.
         * 
         * org.benf.cfr.reader.util.ConfusedCFRException: Tried to end blocks [39[TRYBLOCK]], but top level block is 104[UNCONDITIONALDOLOOP]
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement.processEndingBlocks(Op04StructuredStatement.java:435)
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op04StructuredStatement.buildNestedBlocks(Op04StructuredStatement.java:484)
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op03SimpleStatement.createInitialStructuredBlock(Op03SimpleStatement.java:736)
         *     at org.benf.cfr.reader.bytecode.CodeAnalyser.getAnalysisInner(CodeAnalyser.java:850)
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
    public static String a(int[] relativeTimeArr, float[] scaleArr, int[] freqArr, boolean steepMode, int amplitude) {
        b.a a2;
        Object object;
        ArrayList arrayList;
        d d2;
        a.b b2;
        a.e e2;
        ArrayList arrayList2;
        b.b b3;
        b.a a3;
        b.a a4 = a3;
        a3 = new b.a();
        Object object2 = b3;
        b3 = new b.b();
        a3.a = object2;
        object2 = arrayList2;
        arrayList2 = new ArrayList();
        a3.b = object2;
        object2 = e2;
        e2 = new a.e();
        a.b b4 = b2;
        b2 = new a.b();
        d d3 = d2;
        new d().a = 100;
        new d().b = 50;
        ArrayList arrayList3 = arrayList;
        arrayList = new ArrayList();
        d2.c = arrayList3;
        for (int i2 = 0; i2 < 4; ++i2) {
            void var4_5;
            void var1_1;
            void var2_2;
            a.a a5;
            d d4 = d3;
            a.a a6 = a5;
            a6();
            a6.a = object[i2];
            a6.c = var2_2[i2];
            void v9 = var1_1[i2];
            a5.b = (double)(v9 * (float)var4_5 / 255.0f);
            d4.c.add(a6);
        }
        try {
            b.a a7 = a4;
            a2 = a7;
            b4.a = h;
            b4.e = d3;
            b4.c = object[3];
            ((a.e)object2).a = b4;
            a7.b.add((a.e)object2);
        }
        catch (Throwable throwable) {
            throwable.printStackTrace();
            return "";
        }
        String string = com.apprichtap.haptic.base.b.a(a2);
        object = string;
        e.a.a(a, "getEnvelopeHE:" + (String)object);
        return string;
    }

    public String a(File file) {
        return com.apprichtap.haptic.base.e.a(file);
    }
}

