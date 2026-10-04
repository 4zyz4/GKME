import os, sys, numpy as np

H = os.path.join(os.environ["TEMP"], "opencode", "haptic")


def load(path):
    return np.array([[float(v) for v in l.split()] for l in open(path) if len(l.split()) == 4])


def band_env(t, y, f0=169.0, bw=25.0):
    dt = float(np.median(np.diff(t)))
    N = len(y)
    Y = np.fft.fft(y)
    fr = np.fft.fftfreq(N, dt)
    Hf = ((np.abs(fr) >= f0 - bw) & (np.abs(fr) <= f0 + bw)).astype(float)
    yb = np.real(np.fft.ifft(Y * Hf))
    # analytic envelope of bandpassed signal
    h = np.zeros(N)
    h[0] = 1
    h[1:(N + 1) // 2] = 2
    if N % 2 == 0:
        h[N // 2] = 1
    env = np.abs(np.fft.ifft(np.fft.fft(yb) * h))
    return yb, env


def analyze(name, path, f0=169.0, thr=0.5, smooth_ms=8.0):
    if not os.path.exists(path):
        print(f"{name}: MISSING"); return
    D = load(path)
    if len(D) < 100:
        print(f"{name}: too short"); return
    t = D[:, 0] * 1e-9
    xyz = D[:, 1:4]
    dt = float(np.median(np.diff(t)))
    # choose axis with max energy in [f0-30, f0+30]
    best = None
    for ax in range(3):
        y = xyz[:, ax] - xyz[:, ax].mean()
        Y = np.fft.fft(y)
        fr = np.fft.fftfreq(len(y), dt)
        band = (np.abs(fr) >= f0 - 30) & (np.abs(fr) <= f0 + 30)
        e = float(np.sum(np.abs(Y[band]) ** 2))
        if best is None or e > best[0]:
            best = (e, ax)
    ax = best[1]
    y = xyz[:, ax] - xyz[:, ax].mean()
    _, env = band_env(t, y, f0=f0)
    # smooth
    k = max(1, int(smooth_ms / 1000 / dt))
    env = np.convolve(env, np.ones(k) / k, mode="same")
    peak = env.max()
    act = np.where(env > 0.35 * peak)[0]
    if len(act) < 20:
        print(f"{name}: axis={'xyz'[ax]} no active")
        return
    a0, a1 = act[0], act[-1]
    s = a0 + int(0.12 / dt)
    e = a1 - int(0.05 / dt)
    if e <= s:
        s, e = a0, a1
    seg = env[s:e]
    med = float(np.median(seg))
    below = seg < thr * med
    runs = []
    i = 0
    while i < len(below):
        if below[i]:
            j = i
            while j < len(below) and below[j]:
                j += 1
            runs.append((t[s + (i + j) // 2], (j - i) * dt, float(seg[i:j].min() / med)))
            i = j
        else:
            i += 1
    deep = [rr for rr in runs if rr[1] >= 0.006]
    spac = np.diff([r0[0] for r0 in runs]) if len(runs) > 1 else np.array([])
    print(f"\n=== {name} axis={'xyz'[ax]} span={t[a1]-t[a0]:.2f}s ===")
    print(f"  env median={med:.4f} peak={peak:.4f} min/med={seg.min()/med:.3f} p10/med={np.percentile(seg,10)/med:.3f}")
    print(f"  below {thr}*med: {below.mean()*100:4.1f}% ({len(runs)} runs, {len(deep)} >=6ms)")
    for (c, d, dep) in deep:
        print(f"    @{c:5.2f}s dur={d*1000:5.1f}ms depth={dep:.2f}*med")
    if len(spac):
        print(f"  spacing mean={spac.mean()*1000:.0f}ms min={spac.min()*1000:.0f} max={spac.max()*1000:.0f}")


if __name__ == "__main__":
    names = sys.argv[1:] or ["mp_one4000", "mp_one2000", "mp_one1000", "mp_n2x4000", "mp_n8x500",
                             "mp_merged16", "mp_sep_stop", "mp_sep_nostop", "mp_sep_overlap",
                             "mp_loop_neg1", "mp_merged_chain", "mp_single4s"]
    for n in names:
        analyze(n, os.path.join(H, n + ".txt"))
