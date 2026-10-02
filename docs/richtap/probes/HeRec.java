import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;

/** 播放一个 HE JSON 效果，同时把麦克风录音写入文件（16bit mono PCM）。 */
public class HeRec {
    static final int RATE = 48000;
    static void log(String s) { System.out.println("[HeRec] " + s); }
    static void setField(Object o, String n, Object v) {
        try { Field f = o.getClass().getDeclaredField(n); f.setAccessible(true); f.set(o, v); }
        catch (Throwable t) {}
    }

    public static void main(String[] a) throws Exception {
        String jsonPath = a[0];
        String out = a[1];
        int recMs = Integer.parseInt(a[2]);
        int loop = a.length > 3 ? Integer.parseInt(a[3]) : 0;

        Class<?> hp = Class.forName("android.os.HapticPlayer");
        Class<?> de = Class.forName("android.os.DynamicEffect");
        Method create = de.getMethod("create", String.class);
        Method startM = hp.getMethod("start", int.class, int.class, int.class, int.class);
        Method stopM = hp.getMethod("stop");
        Constructor<?> ctor = hp.getConstructor(de);

        String json = new String(Files.readAllBytes(Paths.get(jsonPath)), java.nio.charset.StandardCharsets.UTF_8);
        Object effect = create.invoke(null, json);

        AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, RATE * 4);
        rec.startRecording();

        // 先录 300ms 环境噪声
        short[] pre = new short[RATE / 3];
        int got = 0; while (got < pre.length) { int r = rec.read(pre, got, pre.length - got); if (r > 0) got += r; }

        Object player = ctor.newInstance(effect);
        setField(player, "mPackageName", "com.android.shell");
        long t0 = System.nanoTime();
        String mode = a.length > 4 ? a[4] : "args";
        if (mode.equals("noarg")) {
            hp.getMethod("start").invoke(player);
        } else {
            startM.invoke(player, loop, 0, 255, 56);
        }

        int total = RATE * recMs / 1000;
        short[] buf = new short[Math.min(total, RATE)];
        FileOutputStream fos = new FileOutputStream(out);
        byte[] bb = new byte[buf.length * 2];
        int written = 0;
        while (written < total) {
            int want = Math.min(buf.length, total - written);
            int r = rec.read(buf, 0, want);
            if (r > 0) {
                for (int i = 0; i < r; i++) { bb[i*2] = (byte)(buf[i] & 0xff); bb[i*2+1] = (byte)((buf[i] >> 8) & 0xff); }
                fos.write(bb, 0, r * 2);
                written += r;
            }
        }
        long t1 = System.nanoTime();
        try { stopM.invoke(player); } catch (Throwable t) {}
        fos.close();
        rec.stop(); rec.release();
        log("played loop=" + loop + " recMs=" + recMs + " recordRealMs=" + ((t1 - t0) / 1000000) + " writtenSamples=" + written + " -> " + out);
        System.exit(0);
    }
}
