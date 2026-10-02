import os
out = r"C:\Users\4zyz4\AppData\Local\Temp\opencode\haptic\he"

def pt(t, i, f):
    return '{"Frequency":%s,"Intensity":%s,"Time":%d}' % (f, i, t)

def ev(rel):
    curve = "[" + ",".join([
        pt(0, "4.0E-4", "-3.0"),
        pt(66, "0.85", "-1.0"),
        pt(133, "0.9", "2.0"),
        pt(200, "0.7", "5.0"),
    ]) + "]"
    return ('{"Event":{"Type":"continuous","RelativeTime":%d,"Duration":200,'
            '"Parameters":{"Frequency":55,"Intensity":100,"Curve":%s}}}' % (rel, curve))

body = ",".join(ev(i * 200) for i in range(3))
s = '{"Metadata":{"Created":"gkme","Description":"hd","Version":1},"Pattern":[' + body + "]}"
open(os.path.join(out, "sci.json"), "w").write(s)
print(s[:260])
