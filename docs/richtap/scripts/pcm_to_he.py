import json, sys, os, math, numpy as np
from scipy.signal import butter, sosfiltfilt, hilbert

SR = 48000

def he_of_hz(hz):
    # 真机标定: Hz ~= 170*2^((HE-56)/65)
    return 56.0 + 65.0 * np.log2(np.clip(hz, 60, 400) / 170.0)

def feats(S, bp, idx, band):
    w = S[idx:idx + int(SR * 0.04)]
    wp = bp[idx:idx + int(SR * 0.04)]
    rel = 0.0; fr = 0.0
    if len(wp):
        rel = np.sqrt(np.mean(wp ** 2))
    if len(w) >= 64:
        X = np.abs(np.fft.rfft(w * np.hanning(len(w)))) ** 2
        Fr = np.fft.rfftfreq(len(w), 1 / SR)
        m = (Fr >= band[0]) & (Fr <= band[1])
        if X[m].sum() > 1e-12:
            fr = Fr[m][np.argmax(X[m])]
    return rel, fr

def build_chunks(src, start_s, dur_s, ev_ms=300, events_per_chunk=16, band=(40, 400), outdir=None):
    a = int(start_s * SR); b = int(start_s * SR + dur_s * SR)
    S = src[a:b].astype(np.float64)
    sos = butter(4, band, btype="band", fs=SR, output="sos")
    bp = sosfiltfilt(sos, S)
    env = np.abs(hilbert(bp))
    ref = np.percentile(env, 95) + 1e-9
    pts_n = 4
    n_ev = int(math.ceil(len(S) / (SR * ev_ms / 1000.0)))
    chunks = []
    for c0 in range(0, n_ev, events_per_chunk):
        pattern = []
        for ei in range(c0, min(c0 + events_per_chunk, n_ev)):
            base = ei * (SR * ev_ms // 1000)
            curve = []; freqs = []
            for p in range(pts_n):
                t = int(p * ev_ms / (pts_n - 1))
                rel, fr = feats(S, bp, base + int(t * SR / 1000), band)
                freqs.append(fr)
                curve.append({"Time": t, "Intensity": float(np.clip((rel / ref) ** 0.6, 0, 1)), "Frequency": 0.0})
            base_hz = float(np.median([f for f in freqs if f > 0] or [90.0]))
            hz = float(np.clip(base_hz * 2.0, 90, 270))
            he = int(round(he_of_hz(hz)))
            for p in range(pts_n):
                fr = freqs[p] if freqs[p] > 0 else base_hz
                curve[p]["Frequency"] = float(np.clip(he_of_hz(np.clip(fr * 2.0, 90, 270)) - he, -50, 50))
            pattern.append({"Event": {"Type": "continuous", "RelativeTime": (ei - c0) * ev_ms,
                                      "Duration": ev_ms,
                                      "Parameters": {"Frequency": he, "Intensity": 100, "Curve": curve}}})
        doc = {"Metadata": {"Version": 1, "Created": "gkme-pcm"}, "Pattern": pattern}
        chunks.append(doc)
    if outdir:
        os.makedirs(outdir, exist_ok=True)
        for i, d in enumerate(chunks):
            open(os.path.join(outdir, "chunk_%03d.json" % i), "w").write(json.dumps(d, separators=(",", ":")))
        manifest = {"chunk_ms": ev_ms * events_per_chunk, "ev_ms": ev_ms,
                    "chunks": ["chunk_%03d.json" % i for i in range(len(chunks))]}
        open(os.path.join(outdir, "manifest.json"), "w").write(json.dumps(manifest))
    return chunks

if __name__ == "__main__":
    srcpath = sys.argv[5] if len(sys.argv) > 5 else r"C:\Users\4zyz4\AppData\Local\Temp\opencode\haptic\src48.f32"
    start = float(sys.argv[1]) if len(sys.argv) > 1 else 0.0
    dur = float(sys.argv[2]) if len(sys.argv) > 2 else 60.0
    outdir = sys.argv[6] if len(sys.argv) > 6 else r"C:\Users\4zyz4\AppData\Local\Temp\opencode\haptic\chunks"
    ev = int(sys.argv[3]) if len(sys.argv) > 3 else 300
    src = np.fromfile(srcpath, dtype="<f4")
    cs = build_chunks(src, start, dur, ev_ms=ev, outdir=outdir)
    print(f"chunks={len(cs)} ev_ms={ev} chunk_ms={ev*16} out={outdir}")
