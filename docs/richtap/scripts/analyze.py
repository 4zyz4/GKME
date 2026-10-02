import numpy as np

S = np.fromfile(r"C:\Users\4zyz4\AppData\Local\Temp\opencode\haptic\src48.f32", dtype="<f4")
sr = 48000
print(f"len={len(S)} dur={len(S)/sr:.1f}s peak={np.max(np.abs(S)):.3f} rms={np.sqrt(np.mean(S**2)):.4f}")

# overall band energy
from numpy.fft import rfft, rfftfreq
N = 1 << 18
seg = S[:N] * np.hanning(N)
P = np.abs(rfft(seg))**2
Fr = rfftfreq(N, 1/sr)
tot = P.sum()
bands = [(0,50),(50,100),(100,200),(200,400),(400,1000),(1000,3000),(3000,8000),(8000,20000)]
for lo,hi in bands:
    m = (Fr>=lo)&(Fr<hi)
    print(f"  {lo:>5}-{hi:<5}Hz {100*P[m].sum()/tot:5.1f}%")

# frame features over whole file
hop = sr//100  # 10ms
win = int(sr*0.025)
n = len(S)//hop
env = np.zeros(n); cent = np.zeros(n); domf = np.zeros(n)
w = np.hanning(win)
Fr2 = rfftfreq(win, 1/sr)
for i in range(n):
    x = S[i*hop:i*hop+win]
    if len(x) < win: x = np.pad(x,(0,win-len(x)))
    env[i] = np.sqrt(np.mean(x**2))
    X = np.abs(rfft(x*w)); p = X**2
    if p.sum() > 1e-9:
        cent[i] = (Fr2*p).sum()/p.sum()
        domf[i] = Fr2[np.argmax(p)]
print(f"env: p10={np.percentile(env,10):.4f} p50={np.percentile(env,50):.4f} p90={np.percentile(env,90):.4f} max={env.max():.4f}")
print(f"centroid p50={np.percentile(cent,50):.0f} domf p50={np.percentile(domf,50):.0f}")

# band 50-350Hz envelope + instantaneous freq via analytic signal
from scipy.signal import butter, sosfiltfilt, hilbert
sos = butter(4, [50,350], btype='band', fs=sr, output='sos')
bp = sosfiltfilt(sos, S.astype(np.float64))
benv = np.abs(hilbert(bp))
print(f"band50-350 env: p50={np.percentile(benv,50):.5f} p90={np.percentile(benv,90):.5f} max={benv.max():.5f}  energy_frac={np.mean(bp**2)/np.mean(S**2):.3f}")

# save features for later use
np.save(r"C:\Users\4zyz4\AppData\Local\Temp\opencode\haptic\feat_env.npy", env)
np.save(r"C:\Users\4zyz4\AppData\Local\Temp\opencode\haptic\feat_cent.npy", cent)
np.save(r"C:\Users\4zyz4\AppData\Local\Temp\opencode\haptic\feat_domf.npy", domf)
np.save(r"C:\Users\4zyz4\AppData\Local\Temp\opencode\haptic\feat_benv.npy", benv[::hop//1][:n])
print("saved features")
