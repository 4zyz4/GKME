package com.zyz4.gkme.input.usb;

/**
 * Encoder for the Nintendo Switch "HD rumble" wire format.
 *
 * Frequency and amplitude are mapped with the algorithm documented in
 * dekuNukem/Nintendo_Switch_Reverse_Engineering (rumble_data_table.md):
 *
 *   encodedFreq = round(log2(freqHz / 10) * 32)
 *   encodedAmp  = round(log2(amp * 8.7) * 32)   for amp &gt;  0.23
 *               = round(log2(amp * 17) * 16)    for 0.12 &lt; amp &lt;= 0.23
 *               = 0                             otherwise
 *
 * The classic 4-byte side packs one high-band tone and one low-band tone for a
 * single linear actuator; a full rumble frame is two sides concatenated.
 */
public final class HdRumbleCodec {

    private HdRumbleCodec() {}

    /** Maximum frequency representable by the encoding (Hz). */
    public static final double MAX_FREQ_HZ = 1252.0;

    /** Converts a frequency in Hz to the JoyCon frequency code (0..0xDF). */
    public static int encodedFreq(double freqHz) {
        if (freqHz < 10.0) {
            return 0;
        }
        double f = Math.min(freqHz, MAX_FREQ_HZ);
        int v = (int) Math.round(Math.log(f / 10.0) / Math.log(2.0) * 32.0);
        return clamp(v, 0, 0xDF);
    }

    /** Converts a normalized amplitude (0..1) to the JoyCon amplitude code. */
    public static int encodedAmp(double amp) {
        if (amp <= 0.0) {
            return 0;
        }
        double a = Math.min(amp, 1.0);
        int v;
        if (a > 0.23) {
            v = (int) Math.round(Math.log(a * 8.7) / Math.log(2.0) * 32.0);
        } else if (a > 0.12) {
            v = (int) Math.round(Math.log(a * 17.0) / Math.log(2.0) * 16.0);
        } else {
            v = 0;
        }
        // 0x7F (127) keeps hfAmp (v*2) inside a byte after adding the frequency
        // high bits and keeps lfAmp within the 7-bit low range.
        return clamp(v, 0, 0x7F);
    }

    /**
     * Writes the classic 4-byte JoyCon / Pro Controller side at {@code offset}.
     * Each side is driven by two tones (high band and low band), so both a
     * frequency and an amplitude are needed per band.
     */
    public static void writeClassicSide(
            byte[] out, int offset,
            double highFreqHz, double highAmp,
            double lowFreqHz, double lowAmp) {
        int hf = (encodedFreq(highFreqHz) - 0x60) * 4;
        hf = clamp(hf, 0x04, 0x1FC);
        int lf = encodedFreq(lowFreqHz) - 0x40;
        lf = clamp(lf, 0x01, 0x7F);

        int hfAmp = encodedAmp(highAmp) * 2;
        int lfAmp = encodedAmp(lowAmp) / 2 + 0x40;

        out[offset] = (byte) (hf & 0xFF);
        out[offset + 1] = (byte) ((hfAmp + ((hf >> 8) & 0xFF)) & 0xFF);
        // 0x80 is the fixed control bit of the low band byte.
        out[offset + 2] = (byte) ((lf + 0x80) & 0xFF);
        out[offset + 3] = (byte) (lfAmp & 0xFF);
    }

    /**
     * Switch 2 Pro frequency field (10 bits). The 7-bit classic code is scaled
     * by 4 so it covers the same representable band as the classic HF field.
     */
    public static int proCon2Freq(double freqHz) {
        return clamp(encodedFreq(freqHz) * 4, 0, 0x3FF);
    }

    /** Switch 2 Pro amplitude field used by {@link ProCon2Controller#encodeHdRumble}. */
    public static int proCon2Amp(double amp) {
        if (amp <= 0.0) {
            return 0;
        }
        return (int) Math.round(Math.min(amp, 1.0) * 29000.0);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
