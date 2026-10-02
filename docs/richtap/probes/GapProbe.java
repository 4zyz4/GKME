import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** 播放恒定音，每 500ms 做一次 stop+start，按 20ms 窗口打印 RMS，观察边界是否掉幅。 */
public class GapProbe {
    static final int RATE = 48000;
    static void log(String s) { System.out.println("[GapProbe] " + s); }
    static void setField(Object o, String n, Object v) {
        try { Field f = o.getClass().getDeclaredField(n); f.setAccessible(true); f.set(o, v); }
        catch (Throwable t) {}
    }
    static String he(int freq, int dur) {
        return "{\"Metadata\":{\"Created\":\"p\",\"Description\":\"hd\",\"Version\":1},"
            + "\"Pattern\":[{\"Event\":{\"Type\":\"continuous\",\"RelativeTime\":0,\"Duration\":" + dur + ","
            + "\"Parameters\":{\"Frequency\":" + freq + ",\"Intensity\":100,\"Curve\":["
            + "{\"Frequency\":0.0,\"Intensity\":1.0,\"Time\":0},"
            + "{\"Frequency\":0.0,\"Intensity\":1.0,\"Time\":" + (dur/8) + "},"
            + "{\"Frequency\":0.0,\"Intensity\":1.0,\"Time\":" + (dur - dur/8) + "},"
            + "{\"Frequency\":0.0,\"Intensity\":1.0,\"Time\":" + dur + "}]}}}]}";
    }
    static double rmsWin(AudioRecord rec, int n) {
        short[] buf = new short[n];
        int got = 0;
        while (got < n) { int r = rec.read(buf, got, n - got); if (r > 0) got += r; }
        double sum = 0;
        for (short s : buf) sum += (double) s * s;
        return Math.sqrt(sum / n);
    }
    public static void main(String[] a) throws Exception {
        Class<?> hp = Class.forName("android.os.HapticPlayer");
        Class<?> de = Class.forName("android.os.DynamicEffect");
        Method create = de.getMethod("create", String.class);
        Method startM = hp.getMethod("start", int.class, int.class, int.class, int.class);
        Method stopM = hp.getMethod("stop");
        java.lang.reflect.Constructor<?> ctor = hp.getConstructor(de);

        AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, RATE * 4);
        rec.startRecording();
        Thread.sleep(300);
        short[] t = new short[4800]; rec.read(t, 0, t.length);

        // 恒定音，每 500ms 重投递；noStop=true 时不调用 stop。
        boolean noStop = a.length > 0 && a[0].equals("nostop");
        Object p = null;
        int win = (int)(RATE * 0.02);
        long nextRefill = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < 80; k++) {
            if (System.currentTimeMillis() >= nextRefill) {
                if (p != null && !noStop) stopM.invoke(p);
                Object e = create.invoke(null, he(56, 1000));
                p = ctor.newInstance(e);
                setField(p, "mPackageName", "com.android.shell");
                startM.invoke(p, 1, 0, 255, 56);
                nextRefill = System.currentTimeMillis() + 500;
            }
            double r = rmsWin(rec, win);
            sb.append(String.format("%d:%d ", k * 20, (int) r));
        }
        log((noStop ? "noStop " : "stop+start ") + sb.toString());
        if (p != null) stopM.invoke(p);
        rec.stop(); rec.release();
        log("done");
        System.exit(0);
    }
}
