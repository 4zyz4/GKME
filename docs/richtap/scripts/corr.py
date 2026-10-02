import numpy as np, sys
from scipy.signal import butter, sosfiltfilt, hilbert

def env_of(x, sr=48000, band=(40,400), hop_ms=20, win_ms=40):
    sos = butter(4, band, btype="band", fs=sr, output="sos")
    bp = sosfiltfilt(sos, x.astype(np.float64))
    e = np.abs(hilbert(bp))
    h = sr*hop_ms//1000; w = sr*win_ms//1000
    n = (len(e)-w)//h
    return np.array([np.sqrt(np.mean(e[i*h:i*h+w]**2)) for i in range(n)])

src = np.fromfile(sys.argv[1], dtype="<f4")
rec = np.fromfile(sys.argv[2], dtype="<i2").astype(np.float64)/32768.0
start = float(sys.argv[3]); dur = float(sys.argv[4])
s0 = int(start*48000); s1 = int((start+dur)*48000)
es = env_of(src[s0:s1])
er = env_of(rec)
# 丢弃录音开头 0.5s（引擎起振）
er = er[25:]
L = min(len(es), len(er))
es, er = es[:L], er[:L]
a = (es-es.mean())/(es.std()+1e-9)
b = (er-er.mean())/(er.std()+1e-9)
c = np.correlate(b, a, mode="full")/L
lag = np.argmax(c)-L+1
print(f"frames={L} (20ms each)  max corr={c.max():.3f}  lag={lag*20}ms")
# 分块相关性
for i in range(0, L, L//4):
    seg = slice(i, min(i+L//4, L))
    aa=a[seg]; bb=b[seg]
    cc=np.correlate(bb,aa,'full')/len(aa)
    print(f"  t={i*20:5d}ms corr={cc.max():.3f}")
# 打印两路包络对比(每 200ms 平均)
print(" t_ms   src    rec")
for i in range(0, L, 10):
    print(f"{i*20:6d}  {a[i]:5.2f}  {b[i]:5.2f}")
