import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** 对比：单次长效果 vs 高频 stop+start 重投递，谁更响。 */
public class RestartProbe {
    static final int RATE = 48000;
    static void log(String s) { System.out.println("[RestartProbe] " + s); }
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
        java.lang.reflect.Constructor<?> ctor = hp.getConstructor(de);

        AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, RATE * 4);
        rec.startRecording();
        Thread.sleep(300);
        rms(rec, 4800);

        // A: 单次长效果
        Object ea = create.invoke(null, he(56, 20000));
        Object pa = ctor.newInstance(ea);
        setField(pa, "mPackageName", "com.android.shell");
        startM.invoke(pa, 1, 0, 200, 56);
        double rA = rms(rec, 48000); // 1s
        stopM.invoke(pa);
        log(String.format("A 单次长效果 rms=%.1f", rA));
        Thread.sleep(500);

        // B: 每 50ms stop+start，持续 1s
        long end = System.currentTimeMillis() + 1000;
        Object pb = null;
        // 先测环境
        double rB = 0; int chunks = 0; double sumB = 0;
        while (System.currentTimeMillis() < end) {
            if (pb != null) stopM.invoke(pb);
            Object eb = create.invoke(null, he(56, 4000));
            pb = ctor.newInstance(eb);
            setField(pb, "mPackageName", "com.android.shell");
            startM.invoke(pb, 1, 0, 200, 56);
            sumB += rms(rec, (int)(RATE * 0.05));
            chunks++;
        }
        if (pb != null) stopM.invoke(pb);
        rB = sumB / Math.max(1, chunks);
        log(String.format("B 50ms重投递 rms=%.1f (n=%d)", rB, chunks));
        Thread.sleep(300);

        // C: 每 200ms stop+start，持续 1s
        end = System.currentTimeMillis() + 1000;
        Object pc = null;
        double sumC = 0; int nC = 0;
        while (System.currentTimeMillis() < end) {
            if (pc != null) stopM.invoke(pc);
            Object ec = create.invoke(null, he(56, 4000));
            pc = ctor.newInstance(ec);
            setField(pc, "mPackageName", "com.android.shell");
            startM.invoke(pc, 1, 0, 200, 56);
            sumC += rms(rec, (int)(RATE * 0.2));
            nC++;
        }
        if (pc != null) stopM.invoke(pc);
        log(String.format("C 200ms重投递 rms=%.1f (n=%d)", sumC / Math.max(1, nC), nC));
        Thread.sleep(500);

        // D: 每 500ms stop+start，持续 1.5s
        end = System.currentTimeMillis() + 1500;
        Object pd = null;
        double sumD = 0; int nD = 0;
        while (System.currentTimeMillis() < end) {
            if (pd != null) stopM.invoke(pd);
            Object ed = create.invoke(null, he(56, 4000));
            pd = ctor.newInstance(ed);
            setField(pd, "mPackageName", "com.android.shell");
            startM.invoke(pd, 1, 0, 200, 56);
            sumD += rms(rec, (int)(RATE * 0.5));
            nD++;
        }
        if (pd != null) stopM.invoke(pd);
        log(String.format("D 500ms重投递 rms=%.1f (n=%d)", sumD / Math.max(1, nD), nD));
        Thread.sleep(500);

        // E: 每 1000ms stop+start，持续 2s
        end = System.currentTimeMillis() + 2000;
        Object pe = null;
        double sumE = 0; int nE = 0;
        while (System.currentTimeMillis() < end) {
            if (pe != null) stopM.invoke(pe);
            Object ee = create.invoke(null, he(56, 4000));
            pe = ctor.newInstance(ee);
            setField(pe, "mPackageName", "com.android.shell");
            startM.invoke(pe, 1, 0, 200, 56);
            sumE += rms(rec, RATE);
            nE++;
        }
        if (pe != null) stopM.invoke(pe);
        log(String.format("E 1000ms重投递 rms=%.1f (n=%d)", sumE / Math.max(1, nE), nE));

        rec.stop(); rec.release();
        log("done");
        System.exit(0);
    }
}
