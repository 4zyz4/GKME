import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 验证：连续 start 新 effect（不调用 stop）是否会替换旧 effect。
 * 用麦克风测 RMS：A(amp 255) -> B(amp 40, 不 stop) -> stop。
 */
public class ReplaceProbe {
    static final int RATE = 48000;
    static void log(String s) { System.out.println("[ReplaceProbe] " + s); }
    static void setField(Object o, String n, Object v) {
        try { Field f = o.getClass().getDeclaredField(n); f.setAccessible(true); f.set(o, v); }
        catch (Throwable t) {}
    }
    static String he(int freq) {
        return "{\"Metadata\":{\"Created\":\"p\",\"Description\":\"hd\",\"Version\":1},"
            + "\"Pattern\":[{\"Event\":{\"Type\":\"continuous\",\"RelativeTime\":0,\"Duration\":4000,"
            + "\"Parameters\":{\"Frequency\":" + freq + ",\"Intensity\":100,\"Curve\":["
            + "{\"Frequency\":0.0,\"Intensity\":1.0,\"Time\":0},"
            + "{\"Frequency\":0.0,\"Intensity\":1.0,\"Time\":500},"
            + "{\"Frequency\":0.0,\"Intensity\":1.0,\"Time\":3500},"
            + "{\"Frequency\":0.0,\"Intensity\":1.0,\"Time\":4000}]}}}]}";
    }
    static double rms(AudioRecord rec, int n) {
        short[] buf = new short[2048];
        double sum = 0; int total = 0;
        while (total < n) {
            int r = rec.read(buf, 0, Math.min(buf.length, n - total));
            if (r <= 0) continue;
            for (int i = 0; i < r; i++) sum += (double) buf[i] * buf[i];
            total += r;
        }
        return Math.sqrt(sum / total);
    }
    public static void main(String[] a) throws Exception {
        Class<?> hp = Class.forName("android.os.HapticPlayer");
        Class<?> de = Class.forName("android.os.DynamicEffect");
        Method create = de.getMethod("create", String.class);
        Method startM = hp.getMethod("start", int.class, int.class, int.class, int.class);
        Method stopM = hp.getMethod("stop");

        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE * 4));
        rec.startRecording();
        Thread.sleep(300);
        rms(rec, 4800);

        // A: amp 255
        Object ea = create.invoke(null, he(56));
        Object pa = hp.getConstructor(de).newInstance(ea);
        setField(pa, "mPackageName", "com.android.shell");
        startM.invoke(pa, 1, 0, 255, 56);
        double rA = rms(rec, 24000); // 500ms
        log(String.format("A amp255 rms=%.1f", rA));

        // B: amp 40, NO stop
        Object eb = create.invoke(null, he(56));
        Object pb = hp.getConstructor(de).newInstance(eb);
        setField(pb, "mPackageName", "com.android.shell");
        startM.invoke(pb, 1, 0, 40, 56);
        double rB = rms(rec, 24000);
        log(String.format("B amp40 (no stop) rms=%.1f", rB));

        // C: stop 后再 amp 40
        stopM.invoke(pb);
        Object ec = create.invoke(null, he(56));
        Object pc = hp.getConstructor(de).newInstance(ec);
        setField(pc, "mPackageName", "com.android.shell");
        startM.invoke(pc, 1, 0, 40, 56);
        double rC = rms(rec, 24000);
        log(String.format("C amp40 (stop+start) rms=%.1f  (应明显 < A)", rC));

        stopM.invoke(pc);
        double rS = rms(rec, 12000);
        log(String.format("after stop rms=%.1f", rS));

        rec.stop(); rec.release();
        log("done");
        System.exit(0);
    }
}
