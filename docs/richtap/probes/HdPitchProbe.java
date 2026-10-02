import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 一边用隐藏 API 播放 HD 连续效果，一边用麦克风录音并做 Goertzel 频谱分析，
 * 打印每个 HE Frequency 值对应的真实音高（Hz）。
 *
 * 用法: app_process ... HdPitchProbe <freq1> <freq2> ...
 */
public class HdPitchProbe {
    static final int RATE = 48000;
    static final int N = 16384;
    static final int HZ_LO = 30;
    static final int HZ_HI = 700;

    static void log(String s) { System.out.println("[HdPitchProbe] " + s); }

    static void setField(Object o, String name, Object val) {
        try { Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); f.set(o, val); }
        catch (Throwable t) { log("set " + name + " failed: " + t); }
    }

    static String he(int freq) {
        return "{\"Metadata\":{\"Created\":\"probe\",\"Description\":\"hd\",\"Version\":1},"
            + "\"Pattern\":[{\"Event\":{\"Type\":\"continuous\",\"RelativeTime\":0,\"Duration\":1000,"
            + "\"Parameters\":{\"Frequency\":" + freq + ",\"Intensity\":100,\"Curve\":["
            + "{\"Frequency\":0.0,\"Intensity\":1.0,\"Time\":0},"
            + "{\"Frequency\":0.0,\"Intensity\":1.0,\"Time\":125},"
            + "{\"Frequency\":0.0,\"Intensity\":1.0,\"Time\":875},"
            + "{\"Frequency\":0.0,\"Intensity\":1.0,\"Time\":1000}]}}}]}";
    }

    static short[] readN(AudioRecord rec, int n) {
        short[] buf = new short[n];
        int got = 0;
        long deadline = System.currentTimeMillis() + 2000;
        while (got < n && System.currentTimeMillis() < deadline) {
            int r = rec.read(buf, got, n - got);
            if (r <= 0) { Thread.yield(); continue; }
            got += r;
        }
        return buf;
    }

    /** Returns top-3 peaks as {hz1,mag1,hz2,mag2,hz3,mag3} plus rms. */
    static double[] analyze(short[] s) {
        double mean = 0;
        for (short v : s) mean += v;
        mean /= s.length;
        double[] x = new double[s.length];
        for (int i = 0; i < s.length; i++) x[i] = s[i] - mean;

        int lo = HZ_LO, hi = HZ_HI;
        double[] mag = new double[hi - lo + 1];
        for (int hz = lo; hz <= hi; hz++) mag[hz - lo] = goertzel(x, hz);

        // collect local maxima
        java.util.List<int[]> peaks = new java.util.ArrayList<>();
        for (int i = 1; i < mag.length - 1; i++) {
            if (mag[i] >= mag[i - 1] && mag[i] >= mag[i + 1] && mag[i] > 0) {
                peaks.add(new int[]{lo + i});
            }
        }
        peaks.sort((a, b) -> Double.compare(mag[b[0] - lo], mag[a[0] - lo]));

        double sum = 0;
        for (double v : x) sum += v * v;
        double rms = Math.sqrt(sum / x.length);

        double[] out = new double[7];
        out[6] = rms;
        for (int k = 0; k < 3 && k < peaks.size(); k++) {
            int hz = peaks.get(k)[0];
            out[k * 2] = hz;
            out[k * 2 + 1] = mag[hz - lo];
        }
        return out;
    }

    static double goertzel(double[] x, double hz) {
        double w = 2.0 * Math.PI * hz / RATE;
        double c = 2.0 * Math.cos(w);
        double s1 = 0, s2 = 0;
        for (double v : x) {
            double s0 = v + c * s1 - s2;
            s2 = s1; s1 = s0;
        }
        double re = s1 - s2 * Math.cos(w);
        double im = s2 * Math.sin(w);
        return Math.sqrt(re * re + im * im);
    }

    public static void main(String[] args) throws Exception {
        Class<?> hp = Class.forName("android.os.HapticPlayer");
        Class<?> de = Class.forName("android.os.DynamicEffect");
        Method create = de.getMethod("create", String.class);
        Method start = hp.getMethod("start", int.class, int.class, int.class, int.class);
        Method stop = hp.getMethod("stop");

        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, N * 4));
        rec.startRecording();
        Thread.sleep(300);
        readN(rec, 4800); // warm up
        log("freq ->  peakHz  rms");

        for (String a : args) {
            int f = Integer.parseInt(a);
            System.out.println("[HdPitchProbe] measuring " + f + " ...");
            System.out.flush();
            Object effect = create.invoke(null, he(f));
            Object player = hp.getConstructor(de).newInstance(effect);
            setField(player, "mPackageName", "com.android.shell");
            start.invoke(player, -1, 0, 255, f);
            Thread.sleep(150);                 // settle / skip ramp
            short[] s = readN(rec, N);         // ~341ms
            try { stop.invoke(player); } catch (Throwable t) {}
            double[] r = analyze(s);
            log(String.format("%3d -> %4.0f(%5.0f)  %4.0f(%5.0f)   rms=%.1f",
                f, r[0], r[1], r[2], r[3], r[6]));
            System.out.flush();
            Thread.sleep(120);
        }
        rec.stop();
        rec.release();
        log("done.");
        System.exit(0);
    }
}
