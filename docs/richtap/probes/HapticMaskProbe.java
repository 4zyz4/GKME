import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

public class HapticMaskProbe {
    static void log(String s) { System.out.println("[Mask] " + s); }

    static final int[] MASKS = {
        0x00000004, // MONO
        0x0000000C, // STEREO
        0x20000000, // HAPTIC_A
        0x10000000, // HAPTIC_B
        0x20000004, // MONO_HAPTIC_A
        0x10000004, // MONO_HAPTIC_B
        0x2000000C, // STEREO_HAPTIC_A
        0x1000000C, // STEREO_HAPTIC_B
        0x30000004, // MONO_HAPTIC_AB
        0x3000000C, // STEREO_HAPTIC_AB
    };

    static void test(int mask) {
        String hex = "0x" + Integer.toHexString(mask);
        int min;
        try {
            min = AudioTrack.getMinBufferSize(48000, mask, AudioFormat.ENCODING_PCM_16BIT);
        } catch (Throwable t) {
            log(hex + " getMinBufferSize threw " + t);
            return;
        }
        log(hex + " minBuffer=" + min);
        if (min <= 0) { log("   -> skip (minBuffer<=0)"); return; }
        AudioTrack t = null;
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setHapticChannelsMuted(false)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48000)
                    .setChannelMask(mask)
                    .build();
            t = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(Math.max(min, 9600))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            log("   -> BUILD OK state=" + t.getState() + " chCount=" + t.getChannelCount()
                    + " session=" + t.getAudioSessionId() + " rate=" + t.getSampleRate());
        } catch (Throwable e) {
            log("   -> build FAILED: " + e);
        } finally {
            if (t != null) t.release();
        }
    }

    public static void main(String[] args) {
        log("isHapticPlaybackSupported=" + AudioManager.isHapticPlaybackSupported());
        for (int m : MASKS) test(m);
        log("done");
    }
}
