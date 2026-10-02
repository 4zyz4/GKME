import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.HapticGenerator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class HapticGenProbe {

    static void log(String s) { System.out.println("[HGProbe] " + s); }

    static int reflectInt(Class<?> c, String name, int def) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable t) {
            log("reflectInt " + name + " fail: " + t);
            return def;
        }
    }

    static void dumpHapticRefs() {
        int chHapticA = reflectInt(AudioFormat.class, "CHANNEL_OUT_HAPTIC_A", 0x20000000);
        log("AudioFormat.CHANNEL_OUT_HAPTIC_A = 0x" + Integer.toHexString(chHapticA));
        try {
            Method[] ms = AudioAttributes.Builder.class.getDeclaredMethods();
            for (Method m : ms) {
                if (m.getName().toLowerCase().contains("haptic")) log("AABuilder method: " + m);
            }
        } catch (Throwable t) { log("dump AABuilder fail " + t); }
        try {
            Method[] ms = AudioTrack.Builder.class.getDeclaredMethods();
            for (Method m : ms) {
                if (m.getName().toLowerCase().contains("haptic")) log("ATBuilder method: " + m);
            }
        } catch (Throwable t) { log("dump ATBuilder fail " + t); }
    }

    static void dumpEffects() {
        try {
            AudioEffect.Descriptor[] ds = AudioEffect.queryEffects();
            log("queryEffects count=" + (ds == null ? -1 : ds.length));
            if (ds != null) {
                for (AudioEffect.Descriptor d : ds) {
                    String n = d.name == null ? "" : d.name;
                    String t = d.type == null ? "" : d.type.toString();
                    if (n.toLowerCase().contains("haptic") || n.toLowerCase().contains("hapticgenerator")
                            || n.toLowerCase().contains("volume") || t.contains("1411")) {
                        log("  effect name=" + n + " type=" + t + " impl=" + d.implementor);
                    }
                }
            }
        } catch (Throwable t) { log("queryEffects fail " + t); }
    }

    static short[] tone(int frames, double freq, int channels, double amp) {
        short[] out = new short[frames * channels];
        for (int n = 0; n < frames; n++) {
            short v = (short) (Math.sin(2 * Math.PI * freq * n / 48000.0) * amp * 32767);
            for (int c = 0; c < channels; c++) out[n * channels + c] = v;
        }
        return out;
    }

    static void tryTrack(String label, int channelMask, AudioAttributes attrs, double freq, int seconds) {
        log("---- " + label + " mask=0x" + Integer.toHexString(channelMask) + " ----");
        AudioTrack track = null;
        HapticGenerator hg = null;
        try {
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48000)
                    .setChannelMask(channelMask)
                    .build();
            int minBuf = AudioTrack.getMinBufferSize(48000,
                    channelMask, AudioFormat.ENCODING_PCM_16BIT);
            log("  minBuffer=" + minBuf);
            int chCount = Integer.bitCount(channelMask & 0xFFFF);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(Math.max(minBuf, 48000 * chCount * 2 / 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            log("  track state=" + track.getState() + " session=" + track.getAudioSessionId()
                    + " chCount=" + track.getChannelCount());
            int session = track.getAudioSessionId();
            try {
                hg = HapticGenerator.create(session);
                log("  HapticGenerator.create -> " + (hg == null ? "NULL" : "ok"));
                if (hg != null) {
                    int r = hg.setEnabled(true);
                    log("  setEnabled(true) -> " + r + " (SUCCESS=" + AudioEffect.SUCCESS + ")");
                    try { log("  hg.enabled=" + hg.getEnabled()); } catch (Throwable t) {}
                }
            } catch (Throwable t) {
                log("  create/setEnabled threw: " + t);
            }
            track.play();
            int frames = 480;
            int block = frames * chCount;
            short[] buf = tone(frames, freq, chCount, 0.7);
            int total = 48000 / frames * seconds;
            for (int i = 0; i < total; i++) {
                short[] b = (i % 4 == 0) ? buf : new short[block];
                track.write(b, 0, block);
            }
            track.stop();
            log("  played " + seconds + "s @" + freq + "Hz");
        } catch (Throwable t) {
            log("  EX " + t);
            t.printStackTrace(System.out);
        } finally {
            try { if (hg != null) hg.release(); } catch (Throwable t) {}
            try { if (track != null) track.release(); } catch (Throwable t) {}
        }
    }

    public static void main(String[] args) throws Exception {
        try {
            log("AudioManager.isHapticPlaybackSupported=" + AudioManager.isHapticPlaybackSupported());
        } catch (Throwable t) { log("isHapticPlaybackSupported threw " + t); }
        try { log("HapticGenerator.isAvailable=" + HapticGenerator.isAvailable()); }
        catch (Throwable t) { log("HapticGenerator.isAvailable threw " + t); }

        dumpHapticRefs();
        dumpEffects();

        AudioAttributes media = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).build();

        int hapticA = reflectInt(AudioFormat.class, "CHANNEL_OUT_HAPTIC_A", 0x20000000);
        int stereo = AudioFormat.CHANNEL_OUT_STEREO;
        int mono = AudioFormat.CHANNEL_OUT_MONO;

        tryTrack("stereo (no haptic mask)", stereo, media, 170.0, 2);
        tryTrack("mono", mono, media, 170.0, 2);
        tryTrack("haptic-only", hapticA, media, 170.0, 2);
        tryTrack("stereo+haptic", stereo | hapticA, media, 170.0, 2);
        tryTrack("haptic+haptic", hapticA | (hapticA >> 1), media, 170.0, 2);

        log("done");
    }
}
