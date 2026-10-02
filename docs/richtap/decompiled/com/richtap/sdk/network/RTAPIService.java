/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  android.util.Log
 *  org.json.JSONObject
 */
package com.richtap.sdk.network;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public final class RTAPIService {
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 15000;
    private static final String CONTENT_TYPE = "application/json; charset=UTF-8";
    private static final String URL_TRACKING = "sys/eventTrackingSdk/saveTrackingSdk";
    private ExecutorService mExecutorService = Executors.newSingleThreadExecutor();

    private RTAPIService() {
    }

    public static String getServerUrl() {
        return "https://platform.richtap-haptics.com/richtap/";
    }

    public static RTAPIService create() {
        return ServiceHolder.INSTANCE;
    }

    private static String map2JsonString(Map<String, String> jSONObject) {
        JSONObject jSONObject2;
        JSONObject jSONObject3 = jSONObject;
        jSONObject = jSONObject2;
        jSONObject2 = new JSONObject();
        for (Map.Entry entry : jSONObject3.entrySet()) {
            String string = (String)entry.getKey();
            jSONObject.put(string, entry.getValue());
        }
        return jSONObject.toString();
    }

    public /* synthetic */ RTAPIService(1 var1_1) {
        this();
    }

    public static /* synthetic */ String access$200(Map map) {
        return RTAPIService.map2JsonString(map);
    }

    public void exit() {
        this.mExecutorService.shutdown();
    }

    public void track(final Map<String, String> map) {
        this.mExecutorService.execute(new Runnable(){

            /*
             * Unable to fully structure code
             */
            @Override
            public void run() {
                block20: {
                    block19: {
                        block18: {
                            var1_5 = "Server returned non-OK status: ";
                            var2_6 = null;
                            var2_6 = (HttpURLConnection)new URL(RTAPIService.getServerUrl() + "sys/eventTrackingSdk/saveTrackingSdk").openConnection();
                            v0 = var2_6;
                            v0.setRequestMethod("POST");
                            v0.setConnectTimeout(15000);
                            v0.setReadTimeout(15000);
                            v0.setDoOutput(true);
                            v0.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                            this = RTAPIService.access$200(map);
                            v1 = var2_6.getOutputStream();
                            v1.write(this.getBytes(StandardCharsets.UTF_8));
                            v1.flush();
                            v1.close();
                            var0_1 = var2_6.getResponseCode();
                            if (var0_1 != 200) break block18;
                            var0_2 = var2_6.getInputStream();
                            var1_5 = v2;
                            var3_7 = v3;
                            v3 = new InputStreamReader((InputStream)var0_2, StandardCharsets.UTF_8);
                            v2 = new BufferedReader((Reader)var3_7);
                            var0_2 = v4;
                            v4 = new StringBuilder();
                            ** while ((var3_7 = var1_5.readLine()) != null)
lbl-1000:
                            // 1 sources

                            {
                                var0_2.append((String)var3_7);
                                continue;
                            }
lbl36:
                            // 2 sources

                            var1_5.close();
                            v5 = Log.d((String)"API_REPO", (String)("Response: " + var0_2.toString()));
lbl38:
                            // 2 sources

                            while (true) {
                                break block19;
                                break;
                            }
                        }
                        v6 = "API_REPO";
                        try {
                            v5 = Log.w((String)v6, (String)((String)var1_5 + var0_1));
                            ** continue;
                        }
                        catch (Throwable var0_3) {
                            v7 = var2_6;
                            Log.w((String)"API_REPO", (String)("Failed to transmitting: " + var0_3.getMessage()));
                            if (v7 == null) break block20;
                        }
                    }
                    var2_6.disconnect();
                }
                return;
                {
                    catch (Throwable var0_4) {
                        if (var2_6 != null) {
                            var2_6.disconnect();
                        }
                        throw var0_4;
                    }
                }
            }
        });
    }

    public static final class ServiceHolder {
        private static final RTAPIService INSTANCE = new RTAPIService(null);
    }
}

