import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.HapticGenerator;

public class HgMini {
    static void log(String s) { System.out.println("[HgMini] " + s); }

    public static void main(String[] args) throws Exception {
        log("isHapticPlaybackSupported=" + AudioManager.isHapticPlaybackSupported());
        log("HapticGenerator.isAvailable=" + HapticGenerator.isAvailable());
        try {
            java.lang.reflect.Method m = AudioEffect.class.getDeclaredMethod(
                    "isEffectTypeAvailable", java.util.UUID.class);
            m.setAccessible(true);
            java.util.UUID hg = java.util.UUID.fromString("1411e6d6-aecd-4021-a1cf-a6aceb0d71e5");
            log("isEffectTypeAvailable(HG)=" + m.invoke(null, hg));
        } catch (Throwable t) { log("isEffectTypeAvailable reflect fail " + t); }

        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build();
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(32768)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        log("session=" + t.getAudioSessionId());
        try {
            HapticGenerator hg = HapticGenerator.create(t.getAudioSessionId());
            log("create -> " + hg);
            if (hg != null) log("setEnabled -> " + hg.setEnabled(true));
        } catch (Throwable e) {
            log("create threw: " + e);
        }
        t.release();
        log("done");
    }
}
