import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Looper;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;

/** 播放 HE JSON，同时用加速度计采集马达振动（加速度计与马达刚性耦合，SNR 远好于麦克风）。 */
public class AccProbe {
    static void log(String s) { System.out.println("[AccProbe] " + s); }
    static void setField(Object o, String n, Object v) {
        try { Field f = o.getClass().getDeclaredField(n); f.setAccessible(true); f.set(o, v); } catch (Throwable t) {}
    }

    public static void main(String[] a) throws Exception {
        final String jsonPath = a[0];
        final String out = a[1];
        final int recMs = Integer.parseInt(a[2]);
        final int loop = a.length > 3 ? Integer.parseInt(a[3]) : 0;

        Class<?> hp = Class.forName("android.os.HapticPlayer");
        Class<?> de = Class.forName("android.os.DynamicEffect");
        Method create = de.getMethod("create", String.class);
        Constructor<?> ctor = hp.getConstructor(de);
        final String json = new String(Files.readAllBytes(Paths.get(jsonPath)), java.nio.charset.StandardCharsets.UTF_8);

        Class<?> at = Class.forName("android.app.ActivityThread");
        Object atThread = at.getMethod("systemMain").invoke(null);
        Context ctx = (Context) at.getMethod("getSystemContext").invoke(atThread);
        SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        int delay = 5000; // 200Hz (us)
        log("sensor=" + acc + " minDelayUs=" + acc.getMinDelay());

        final BufferedWriter bw = new BufferedWriter(new FileWriter(out));
        final long[] n = {0};
        final long[] t0 = {0};
        SensorEventListener l = new SensorEventListener() {
            public void onSensorChanged(SensorEvent e) {
                if (t0[0] == 0) t0[0] = e.timestamp;
                try { bw.write((e.timestamp - t0[0]) + " " + e.values[0] + " " + e.values[1] + " " + e.values[2] + "\n"); }
                catch (Exception ex) {}
                n[0]++;
            }
            public void onAccuracyChanged(Sensor s, int acc2) {}
        };
        sm.registerListener(l, acc, delay);

        final Method createC = create;
        final Constructor<?> ctorC = ctor;
        Thread player = new Thread(new Runnable() { public void run() {
            try {
                Thread.sleep(400);
                log("playing...");
                Object effect = createC.invoke(null, json);
                Object pl = ctorC.newInstance(effect);
                setField(pl, "mPackageName", "com.android.shell");
                long p0 = System.nanoTime();
                if (a.length > 4 && a[4].equals("args")) hp.getMethod("start", int.class, int.class, int.class, int.class).invoke(pl, loop, 0, 255, 56);
                else hp.getMethod("start").invoke(pl);
                Thread.sleep(recMs);
                long p1 = System.nanoTime();
                try { hp.getMethod("stop").invoke(pl); } catch (Throwable t) {}
                log("playRealMs=" + ((p1-p0)/1000000));
            } catch (Throwable t) { log("player ERR " + t); }
            new android.os.Handler(Looper.getMainLooper()).post(new Runnable() { public void run() {
                Looper.myLooper().quit();
            }});
        }});
        player.start();
        Looper.loop();
        sm.unregisterListener(l);
        bw.flush(); bw.close();
        log("samples=" + n[0] + " -> " + out);
        System.exit(0);
    }
}
