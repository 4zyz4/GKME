import sys, numpy as np
from scipy.signal import butter, sosfiltfilt
p = sys.argv[1]
hop = int(sys.argv[2]) if len(sys.argv) > 2 else 50
lo = int(sys.argv[3]) if len(sys.argv) > 3 else 60
hi = int(sys.argv[4]) if len(sys.argv) > 4 else 500
x = np.fromfile(p, dtype="<i2").astype(np.float64) / 32768.0
sr = 48000
sos = butter(4, [lo, hi], btype="band", fs=sr, output="sos")
xb = sosfiltfilt(sos, x)
w = int(sr * 0.04)
win = np.hanning(w)
Fr = np.fft.rfftfreq(w, 1/sr)
print(f"file={p} dur={len(x)/sr:.2f}s band={lo}-{hi}Hz  rawRms={np.sqrt(np.mean(x**2)):.4f} bandRms={np.sqrt(np.mean(xb**2)):.4f}")
print(" t_ms  bandRms   domHz")
h = sr * hop // 1000
for i in range(len(x)//h):
    seg = xb[i*h: i*h+w]
    if len(seg) < w: seg = np.pad(seg, (0, w-len(seg)))
    rms = np.sqrt(np.mean(seg**2))
    X = np.abs(np.fft.rfft(seg*win))**2
    dom = Fr[np.argmax(X)] if X.sum() > 1e-15 else 0
    bar = "#" * int(rms * 900)
    print(f"{i*hop:5d}  {rms:7.5f}  {dom:5.0f}  {bar}")
