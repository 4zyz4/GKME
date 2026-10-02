/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  android.util.Base64
 *  android.util.Log
 */
package com.apprichtap.haptic.base;

import android.util.Base64;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public final class a {
    private static final String a = "AES/ECB/PKCS5Padding";
    private static final String b = "richtap@12345678";
    private static final int c = 16;
    private static final String d = "0";
    private static final String e = "AES";

    private a() throws Exception {
        throw new IllegalAccessException("Access denied!");
    }

    public static String a(String value) {
        String string;
        Cipher cipher;
        SecretKeySpec secretKeySpec;
        SecretKeySpec secretKeySpec2 = secretKeySpec;
        try {
            secretKeySpec = new SecretKeySpec(b.getBytes(StandardCharsets.UTF_8), e);
            cipher = Cipher.getInstance(a);
        }
        catch (Exception exception) {
            Log.e((String)"EncryptionUtils", (String)"encrypt: ", (Throwable)exception);
            return null;
        }
        cipher.init(1, secretKeySpec2);
        return Base64.encodeToString((byte[])cipher.doFinal(string.getBytes(StandardCharsets.UTF_8)), (int)2);
    }
}

