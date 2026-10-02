import json, os
out = r"C:\Users\4zyz4\AppData\Local\Temp\opencode\haptic\he"
os.makedirs(out, exist_ok=True)

def cont(rel, dur, freq_base, pts, intensity=100):
    # pts: list of (time_ms, intensity_0_1, freq_offset)
    return {"Event": {"Type": "continuous", "RelativeTime": rel, "Duration": dur,
            "Parameters": {"Frequency": freq_base, "Intensity": intensity,
            "Curve": [{"Time": t, "Intensity": iv, "Frequency": fv} for t, iv, fv in pts]}}}

# 1) v1, single event, 8 curve points (chirp freq offset up then down)
pts8 = [(i*60, 1.0, v) for i, v in enumerate([0,10,20,20,10,0,-10,-10])]
json.dump({"Metadata": {"Version": 1, "Created": "x"},
           "Pattern": [cont(0, 480, 56, pts8)]},
          open(os.path.join(out, "v1_8pts.json"), "w"))

# 2) v1, Pattern array with 2 events
json.dump({"Metadata": {"Version": 1, "Created": "x"},
           "Pattern": [cont(0, 300, 40, [(0,1.0,0),(300,1.0,0)]),
                       cont(400, 300, 80, [(0,1.0,0),(300,1.0,0)])]},
          open(os.path.join(out, "v1_twoevents.json"), "w"))

# 3) v2, PatternList of 3 events at absolute times
json.dump({"Metadata": {"Version": 2, "Created": "x"},
           "PatternList": [
               {"AbsoluteTime": 0,   "Pattern": [cont(0, 300, 40, [(0,1.0,0),(300,1.0,0)])]},
               {"AbsoluteTime": 300, "Pattern": [cont(0, 300, 60, [(0,1.0,0),(300,1.0,0)])]},
               {"AbsoluteTime": 600, "Pattern": [cont(0, 300, 80, [(0,1.0,0),(300,1.0,0)])]}]},
          open(os.path.join(out, "v2_patternlist.json"), "w"))

# 4) v1, 16-point curve
pts16 = [(i*30, 1.0, (i*5) % 40 - 20) for i in range(16)]
json.dump({"Metadata": {"Version": 1, "Created": "x"},
           "Pattern": [cont(0, 480, 56, pts16)]},
          open(os.path.join(out, "v1_16pts.json"), "w"))

# extra: N events in one Pattern
def many(n, dur=300):
    return {"Metadata": {"Version": 1, "Created": "x"},
            "Pattern": [cont(i*dur, dur, 30 + (i*4) % 60, [(0,1.0,0),(dur,1.0,0)]) for i in range(n)]}
json.dump(many(16), open(os.path.join(out, "v1_16events.json"), "w"))
json.dump(many(32), open(os.path.join(out, "v1_32events.json"), "w"))
json.dump(many(64), open(os.path.join(out, "v1_64events.json"), "w"))

# alternating low/high, 300ms each: freq 30 (low) / 90 (high); 16 and 32 events
def alt(n, dur=300):
    return {"Metadata": {"Version": 1, "Created": "x"},
            "Pattern": [cont(i*dur, dur, 30 if i % 2 == 0 else 90, [(0,1.0,0),(dur,1.0,0)]) for i in range(n)]}
json.dump(alt(16), open(os.path.join(out, "v1_alt16.json"), "w"))
json.dump(alt(32), open(os.path.join(out, "v1_alt32.json"), "w"))

# alternating loud/soft amplitude, 300ms each (intensity 100 / 15)
def altamp(n, dur=300):
    return {"Metadata": {"Version": 1, "Created": "x"},
            "Pattern": [cont(i*dur, dur, 56, [(0, 1.0 if i % 2 == 0 else 0.15, 0),
                                              (dur, 1.0 if i % 2 == 0 else 0.15, 0)],
                              intensity=100 if i % 2 == 0 else 20) for i in range(n)]}
json.dump(altamp(16), open(os.path.join(out, "v1_altamp16.json"), "w"))

# pulse locator: 16 events, baseline intensity 30, index p boosted to 100
def pulse(n, p, dur=300):
    pat = []
    for i in range(n):
        loud = (i == p)
        pat.append(cont(i*dur, dur, 56,
                        [(0, 1.0 if loud else 0.3, 0), (dur, 1.0 if loud else 0.3, 0)],
                        intensity=100 if loud else 30))
    return {"Metadata": {"Version": 1, "Created": "x"}, "Pattern": pat}
json.dump(pulse(16, 2), open(os.path.join(out, "v1_pulse2.json"), "w"))
json.dump(pulse(16, 10), open(os.path.join(out, "v1_pulse10.json"), "w"))

# single fixed effects for mic calibration
json.dump({"Metadata": {"Version": 1, "Created": "x"}, "Pattern": [cont(0, 3000, 30, [(0,1.0,0),(3000,1.0,0)])]},
          open(os.path.join(out, "single_lo.json"), "w"))
json.dump({"Metadata": {"Version": 1, "Created": "x"}, "Pattern": [cont(0, 3000, 90, [(0,1.0,0),(3000,1.0,0)])]},
          open(os.path.join(out, "single_hi.json"), "w"))

# 2 long events, intensity 100 then 10 (and reversed) -> easy to measure
json.dump({"Metadata": {"Version": 1, "Created": "x"},
           "Pattern": [cont(0, 2000, 56, [(0,1.0,0),(2000,1.0,0)], intensity=100),
                       cont(2000, 2000, 56, [(0,0.1,0),(2000,0.1,0)], intensity=10)]},
          open(os.path.join(out, "v1_2ev_hi_lo.json"), "w"))
json.dump({"Metadata": {"Version": 1, "Created": "x"},
           "Pattern": [cont(0, 2000, 56, [(0,0.1,0),(2000,0.1,0)], intensity=10),
                       cont(2000, 2000, 56, [(0,1.0,0),(2000,1.0,0)], intensity=100)]},
          open(os.path.join(out, "v1_2ev_lo_hi.json"), "w"))

print("generated:", os.listdir(out))
