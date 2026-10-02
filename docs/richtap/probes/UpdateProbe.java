import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** 验证 HapticPlayer.updateParameter(interval, amplitude, freq) 是否能在不重启的情况下改变幅度。 */
public class UpdateProbe {
    static final int RATE = 48000;
    static void log(String s) { System.out.println("[UpdateProbe] " + s); }
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

        Method upd3 = null, updAmp = null, updFreq = null;
        try { upd3 = hp.getMethod("updateParameter", int.class, int.class, int.class); log("has updateParameter(int,int,int)"); } catch (Throwable t) { log("no updateParameter3: " + t); }
        try { updAmp = hp.getMethod("updateAmplitude", int.class); log("has updateAmplitude(int)"); } catch (Throwable t) {}
        try { updFreq = hp.getMethod("updateFrequency", int.class); log("has updateFrequency(int)"); } catch (Throwable t) {}

        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE * 4));
        rec.startRecording();
        Thread.sleep(300);
        rms(rec, 4800);

        Object e = create.invoke(null, he(56, 20000));
        Object p = hp.getConstructor(de).newInstance(e);
        setField(p, "mPackageName", "com.android.shell");
        startM.invoke(p, 1, 0, 255, 56);
        double rA = rms(rec, 24000);
        log(String.format("A amp255 (once) rms=%.1f", rA));

        if (upd3 != null) {
            upd3.invoke(p, 0, 40, 56);
            double rU = rms(rec, 24000);
            log(String.format("after updateParameter(0,40,56) rms=%.1f  (若≪A则可用)", rU));
        }
        stopM.invoke(p);
        double rS = rms(rec, 12000);
        log(String.format("after stop rms=%.1f", rS));

        rec.stop(); rec.release();
        log("done");
        System.exit(0);
    }
}
