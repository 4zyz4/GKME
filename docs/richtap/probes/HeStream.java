import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.json.JSONObject;

/** 顺序播放目录下的 HE chunk（每块<=16 事件），主线程交错录音以便检测块边界间隙。 */
public class HeStream {
    static void log(String s) { System.out.println("[HeStream] " + s); }
    static void setField(Object o, String n, Object v) {
        try { Field f = o.getClass().getDeclaredField(n); f.setAccessible(true); f.set(o, v); } catch (Throwable t) {}
    }

    public static void main(String[] a) throws Exception {
        String dir = a[0];
        String manifest = new String(Files.readAllBytes(Paths.get(dir, "manifest.json")), java.nio.charset.StandardCharsets.UTF_8);
        JSONObject mf = new JSONObject(manifest);
        int chunkMs = mf.getInt("chunk_ms");
        org.json.JSONArray arr = mf.getJSONArray("chunks");
        int lead = a.length > 1 ? Integer.parseInt(a[1]) : 80;
        int reps = a.length > 2 ? Integer.parseInt(a[2]) : 1;
        String recPath = a.length > 3 ? a[3] : null;

        Class<?> hp = Class.forName("android.os.HapticPlayer");
        Class<?> de = Class.forName("android.os.DynamicEffect");
        Method create = de.getMethod("create", String.class);
        Method start = hp.getMethod("start");
        Method stop = hp.getMethod("stop");
        Constructor<?> ctor = hp.getConstructor(de);
        log("chunks=" + arr.length() + " chunkMs=" + chunkMs + " lead=" + lead + " reps=" + reps);

        AudioRecord rec = null; FileOutputStream fos = null;
        if (recPath != null) {
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, 48000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, 48000 * 4);
            rec.startRecording(); fos = new FileOutputStream(recPath);
        }
        short[] rbuf = new short[960]; byte[] rbytes = new byte[1920];
        long t0 = System.currentTimeMillis();

        for (int r = 0; r < reps; r++) {
            Object player = null;
            long next = System.currentTimeMillis();
            for (int i = 0; i < arr.length(); i++) {
                String json = new String(Files.readAllBytes(Paths.get(dir, arr.getString(i))), java.nio.charset.StandardCharsets.UTF_8);
                Object effect = create.invoke(null, json);
                Object pl = ctor.newInstance(effect);
                setField(pl, "mPackageName", "com.android.shell");
                while (System.currentTimeMillis() < next) {
                    if (rec != null) { int got = rec.read(rbuf, 0, rbuf.length); if (got > 0) { for (int k = 0; k < got; k++) { rbytes[k*2]=(byte)(rbuf[k]&0xff); rbytes[k*2+1]=(byte)((rbuf[k]>>8)&0xff);} fos.write(rbytes,0,got*2);} }
                    else Thread.sleep(5);
                }
                start.invoke(pl);
                player = pl;
                next += chunkMs - lead;
                if (i % 5 == 0) log("chunk " + i + "/" + arr.length() + " @" + (System.currentTimeMillis()-t0));
            }
            if (player != null) try { stop.invoke(player); } catch (Throwable t) {}
        }
        if (rec != null) { rec.stop(); rec.release(); fos.close(); }
        log("done");
        System.exit(0);
    }
}
