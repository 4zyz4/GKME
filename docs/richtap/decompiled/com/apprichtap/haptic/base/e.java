/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  android.content.Context
 *  android.util.Log
 */
package com.apprichtap.haptic.base;

import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

public class e {
    private static final String a = "Util";
    private static boolean b = true;
    public static final int c = 26;
    public static Context d;

    /*
     * Exception decompiling
     */
    public static void a(String fileName, String str) {
        /*
         * This method has failed to decompile.  When submitting a bug report, please provide this stack trace, and (if you hold appropriate legal rights) the relevant class file.
         * 
         * org.benf.cfr.reader.util.ConfusedCFRException: Back jump on a try block [egrp 11[TRYBLOCK] [21 : 165->175)] java.lang.Throwable
         *     at org.benf.cfr.reader.bytecode.analysis.opgraph.Op02WithProcessedDataAndRefs.insertExceptionBlocks(Op02WithProcessedDataAndRefs.java:2283)
         *     at org.benf.cfr.reader.bytecode.CodeAnalyser.getAnalysisInner(CodeAnalyser.java:415)
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
     * WARNING - Removed back jump from a try to a catch block - possible behaviour change.
     * Unable to fully structure code
     * Enabled aggressive block sorting
     * Enabled unnecessary exception pruning
     * Enabled aggressive exception aggregation
     */
    public static String a(File file) {
        block18: {
            block19: {
                if (file == null) return "";
                if (!var0.exists()) {
                    return "";
                }
                var1_4 = v0;
                v0 = new StringBuilder();
                var2_5 = null;
                var3_6 = v1;
                var4_7 = v2;
                var5_8 = v3;
                v3 = new FileInputStream((File)var0);
                v2 = new InputStreamReader(var5_8);
                v1 = new BufferedReader(var4_7);
                while (true) {
                    var0 = var3_6.readLine();
                    if (var0 == null) break;
                    var1_4.append(var0);
                }
                try {}
                catch (Exception v4) {
                    v4.printStackTrace();
                    return var1_4.toString();
                }
                break block19;
                catch (Throwable var0_2) {
                    break block18;
                }
                catch (Exception v5) {
                    // empty catch block
                    ** GOTO lbl-1000
                }
                catch (Throwable var0_1) {
                    var2_5 = var3_6;
                    break block18;
                }
                catch (Exception v5) {
                    var2_5 = var3_6;
                    ** GOTO lbl-1000
                }
            }
            var3_6.close();
            return var1_4.toString();
lbl-1000:
            // 2 sources

            {
                v5.printStackTrace();
            }
            {
                var2_5.close();
                return var1_4.toString();
            }
        }
        try {
            var2_5.close();
            throw var0_3;
        }
        catch (Exception v6) {
            v6.printStackTrace();
        }
        throw var0_3;
    }

    public static void a(boolean enable) {
        b = enable;
    }

    public static boolean b() {
        return b;
    }

    public static class a {
        /*
         * WARNING - void declaration
         */
        public static void a(String tag, String info) {
            if (b) {
                void var1_1;
                String string;
                Log.d((String)string, (String)var1_1);
            }
        }

        /*
         * WARNING - void declaration
         */
        public static void c(String tag, String info) {
            void var1_1;
            Log.i((String)tag, (String)var1_1);
        }

        /*
         * WARNING - void declaration
         */
        public static void d(String tag, String info) {
            void var1_1;
            Log.w((String)tag, (String)var1_1);
        }

        /*
         * WARNING - void declaration
         */
        public static void b(String tag, String info) {
            void var1_1;
            Log.e((String)tag, (String)var1_1);
        }
    }
}

