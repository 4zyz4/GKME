import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 验证 HE 曲线 Frequency 偏移是否按时间改变音高。
 * 效果：Duration=2400，Parameters.Frequency=56(mid)，
 * Curve 频率偏移在 +40 / -40 之间交替；用 300ms 窗测每个时间段的真实音高。
 */
public class CurveProbe {
    static final int RATE = 48000;
    static void log(String s) { System.out.println("[CurveProbe] " + s); }
    static void setField(Object o, String n, Object v) {
        try { Field f = o.getClass().getDeclaredField(n); f.setAccessible(true); f.set(o, v); }
        catch (Throwable t) {}
    }
    static String he() {
        // 8 点：+40 +20 -40 -20 +40 +20 -40 0
        return "{\"Metadata\":{\"Created\":\"p\",\"Description\":\"hd\",\"Version\":1},"
            + "\"Pattern\":[{\"Event\":{\"Type\":\"continuous\",\"RelativeTime\":0,\"Duration\":2400,"
            + "\"Parameters\":{\"Frequency\":56,\"Intensity\":100,\"Curve\":["
            + "{\"Frequency\":40.0,\"Intensity\":1.0,\"Time\":0},"
            + "{\"Frequency\":20.0,\"Intensity\":1.0,\"Time\":300},"
            + "{\"Frequency\":-40.0,\"Intensity\":1.0,\"Time\":600},"
            + "{\"Frequency\":-20.0,\"Intensity\":1.0,\"Time\":900},"
            + "{\"Frequency\":40.0,\"Intensity\":1.0,\"Time\":1200},"
            + "{\"Frequency\":20.0,\"Intensity\":1.0,\"Time\":1500},"
            + "{\"Frequency\":-40.0,\"Intensity\":1.0,\"Time\":1800},"
            + "{\"Frequency\":0.0,\"Intensity\":1.0,\"Time\":2400}]}}}]}";
    }
    static double goertzel(double[] x, double hz) {
        double w = 2.0 * Math.PI * hz / RATE;
        double c = 2.0 * Math.cos(w);
        double s1 = 0, s2 = 0;
        for (double v : x) { double s0 = v + c * s1 - s2; s2 = s1; s1 = s0; }
        double re = s1 - s2 * Math.cos(w), im = s2 * Math.sin(w);
        return Math.sqrt(re * re + im * im);
    }
    static double peakHz(double[] x) {
        double best = 0; int bh = 0;
        for (int hz = 60; hz <= 500; hz += 2) { double m = goertzel(x, hz); if (m > best) { best = m; bh = hz; } }
        return bh;
    }
    public static void main(String[] a) throws Exception {
        Class<?> hp = Class.forName("android.os.HapticPlayer");
        Class<?> de = Class.forName("android.os.DynamicEffect");
        Method create = de.getMethod("create", String.class);
        Method startM = hp.getMethod("start", int.class, int.class, int.class, int.class);
        Method stopM = hp.getMethod("stop");

        AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, RATE * 4);
        rec.startRecording();
        Thread.sleep(300);
        short[] t = new short[4800]; rec.read(t, 0, t.length);

        Object e = create.invoke(null, he());
        Object p = hp.getConstructor(de).newInstance(e);
        setField(p, "mPackageName", "com.android.shell");
        startM.invoke(p, 1, 0, 255, 56);

        // 记录 2.4s，每 300ms 一个窗，输出主频
        int win = RATE * 3 / 10;
        short[] buf = new short[win];
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < 8; k++) {
            int got = 0;
            while (got < win) { int r = rec.read(buf, got, win - got); if (r > 0) got += r; }
            double mean = 0; for (short s : buf) mean += s; mean /= win;
            double[] x = new double[win];
            for (int i = 0; i < win; i++) x[i] = buf[i] - mean;
            sb.append(String.format("t=%dms:%dHz  ", k * 300, (int) peakHz(x)));
        }
        log(sb.toString());
        try { stopM.invoke(p); } catch (Throwable th) {}
        rec.stop(); rec.release();
        log("done");
        System.exit(0);
    }
}
