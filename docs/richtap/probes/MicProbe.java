import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

public class MicProbe {
    public static void main(String[] args) throws Exception {
        int rate = 48000;
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        System.out.println("[MicProbe] minBuf=" + min);
        AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate * 2));
        System.out.println("[MicProbe] state=" + rec.getState() + " (3=INITIALIZED)");
        try {
            rec.startRecording();
            System.out.println("[MicProbe] recordingState=" + rec.getRecordingState() + " (3=RECORDING)");
            short[] buf = new short[4800];
            int total = 0;
            long peak = 0;
            for (int i = 0; i < 10; i++) {
                int n = rec.read(buf, 0, buf.length);
                if (n > 0) {
                    total += n;
                    for (int j = 0; j < n; j++) {
                        int v = Math.abs(buf[j]);
                        if (v > peak) peak = v;
                    }
                }
            }
            System.out.println("[MicProbe] read=" + total + " peak=" + peak);
            rec.stop();
        } catch (Throwable t) {
            System.out.println("[MicProbe] ERROR " + t);
        }
        rec.release();
    }
}
