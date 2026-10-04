import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Looper;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 用加速度计对比「多个 HE 效果/事件如何合成一段连续、不中断的震动」的各种策略。
 * 恒定 HE56 @ 0.7，任何边界都会在包络上显示为下陷（用 scripts/analyze_env.py 的带通+希尔伯特包络分析）。
 *
 * 编译/运行见 docs/richtap-hd-vibration.md §13 与 scripts/mkjar.ps1：
 *   javac --release 8 -nowarn -cp <android.jar> -d out MergeChainProbe.java
 *   d8 --release --lib <android.jar> --output out out\*.class   # 必须含内部类
 *   adb shell CLASSPATH=/data/local/tmp/mp.jar app_process /data/local/tmp MergeProbe <strategy> <out>
 *
 * 关键策略（结论见 §13）：
 *   merged16     : 一条效果 16 事件 x200ms，控制点均匀分布 → 每 200ms 一次 ~45ms 下陷
 *   edge         : 一条效果 n 事件 x evMs，控制点**聚到两端**(0,ε,ev-ε,ev) → 完全连续
 *   edgeChain    : 多个 edge 效果**无 stop**链接（提前 lead ms 起新效果）→ 任意时长连续
 *   edgeChainStop: 同上但在 start 新效果后 stop 旧效果 → `stop()` 是全局取消，整段静音
 *   sep_stop     : 每个 200ms 单独 start、stop+start 换块
 *   chain_single : 长单事件无 stop 链接（每个事件自带 ~30% 时长的起振/衰减，仍会掉幅）
 *   loop_neg1    : 单事件 start(loop=-1)（每圈衔接处下陷）
 *   curve4t      : 单事件，指定 4 个控制点的**时间与强度**（用于证明：控制点越靠两端起振越短）
 */
public class MergeProbe {
    static void log(String s) { System.out.println("[MergeProbe] " + s); }
    static void setField(Object o, String n, Object v) {
        try { Field f = o.getClass().getDeclaredField(n); f.setAccessible(true); f.set(o, v); }
        catch (Throwable t) {}
    }

    static final int FREQ = 56;
    static final int EV = 200;
    static final int N = 16;

    static String curve(int evMs, double inten) {
        int a = 0, b = evMs / 3, c = 2 * evMs / 3, d = evMs;
        return "[{\"Frequency\":0.0,\"Intensity\":" + inten + ",\"Time\":" + a + "},"
             + "{\"Frequency\":0.0,\"Intensity\":" + inten + ",\"Time\":" + b + "},"
             + "{\"Frequency\":0.0,\"Intensity\":" + inten + ",\"Time\":" + c + "},"
             + "{\"Frequency\":0.0,\"Intensity\":" + inten + ",\"Time\":" + d + "}]";
    }

    static String effect(int n, int evMs, int freq, double inten, int baseRel) {
        return effectStep(n, evMs, evMs, freq, inten, baseRel);
    }

    static String curveEdge(int evMs, double inten, int eps) {
        int b = Math.min(eps, evMs / 2 - 1), c = evMs - b;
        return "[{\"Frequency\":0.0,\"Intensity\":" + inten + ",\"Time\":0},"
             + "{\"Frequency\":0.0,\"Intensity\":" + inten + ",\"Time\":" + b + "},"
             + "{\"Frequency\":0.0,\"Intensity\":" + inten + ",\"Time\":" + c + "},"
             + "{\"Frequency\":0.0,\"Intensity\":" + inten + ",\"Time\":" + evMs + "}]";
    }

    static String effectEdge(int n, int evMs, int step, int freq, double inten, int eps) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"Metadata\":{\"Created\":\"merge\",\"Description\":\"hd\",\"Version\":1},\"Pattern\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            int rt = i * step;
            sb.append("{\"Event\":{\"Type\":\"continuous\",\"RelativeTime\":").append(rt)
              .append(",\"Duration\":").append(evMs)
              .append(",\"Parameters\":{\"Frequency\":").append(freq)
              .append(",\"Intensity\":100,\"Curve\":").append(curveEdge(evMs, inten, eps)).append("}}}");
        }
        sb.append("]}");
        return sb.toString();
    }

    static String effectStep(int n, int evMs, int step, int freq, double inten, int baseRel) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"Metadata\":{\"Created\":\"merge\",\"Description\":\"hd\",\"Version\":1},\"Pattern\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            int rt = baseRel + i * step;
            sb.append("{\"Event\":{\"Type\":\"continuous\",\"RelativeTime\":").append(rt)
              .append(",\"Duration\":").append(evMs)
              .append(",\"Parameters\":{\"Frequency\":").append(freq)
              .append(",\"Intensity\":100,\"Curve\":").append(curve(evMs, inten)).append("}}}");
        }
        sb.append("]}");
        return sb.toString();
    }

    public static void main(String[] a) throws Exception {
        final String strategy = a[0];
        final String out = a[1];
        final int p1 = a.length > 2 ? Integer.parseInt(a[2]) : 60;

        Class<?> hp = Class.forName("android.os.HapticPlayer");
        Class<?> de = Class.forName("android.os.DynamicEffect");
        final Method create = de.getMethod("create", String.class);
        final Method startNoArg = hp.getMethod("start");
        final Method startArgs = hp.getMethod("start", int.class, int.class, int.class, int.class);
        final Method stop = hp.getMethod("stop");
        final Constructor<?> ctor = hp.getConstructor(de);

        Class<?> at = Class.forName("android.app.ActivityThread");
        try { Looper.prepareMainLooper(); } catch (Throwable t) {}
        Object atThread = at.getMethod("systemMain").invoke(null);
        Context ctx = (Context) at.getMethod("getSystemContext").invoke(atThread);
        SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        log("strategy=" + strategy + " p1=" + p1 + " sensor=" + acc + " minDelayUs=" + acc.getMinDelay());

        final BufferedWriter bw = new BufferedWriter(new FileWriter(out));
        final long[] t0 = {0};
        final long[] n = {0};
        SensorEventListener l = new SensorEventListener() {
            public void onSensorChanged(SensorEvent e) {
                if (t0[0] == 0) t0[0] = e.timestamp;
                try { bw.write((e.timestamp - t0[0]) + " " + e.values[0] + " " + e.values[1] + " " + e.values[2] + "\n"); }
                catch (Exception ex) {}
                n[0]++;
            }
            public void onAccuracyChanged(Sensor s, int x) {}
        };
        sm.registerListener(l, acc, 2084);

        Thread player = new Thread(new Runnable() { public void run() {
            try {
                Thread.sleep(500);
                log("play " + strategy);
                if (strategy.equals("single4s")) {
                    Object pl = mk(create, ctor, effect(1, 4000, FREQ, 0.7, 0));
                    startNoArg.invoke(pl);
                    Thread.sleep(3600);
                    stop.invoke(pl);
                } else if (strategy.equals("merged16")) {
                    Object pl = mk(create, ctor, effect(N, EV, FREQ, 0.7, 0));
                    startNoArg.invoke(pl);
                    Thread.sleep(3600);
                    stop.invoke(pl);
                } else if (strategy.equals("sep_stop")) {
                    int evMs = a.length > 3 ? Integer.parseInt(a[3]) : EV;
                    int cnt = a.length > 4 ? Integer.parseInt(a[4]) : N;
                    Object pl = null;
                    long next = System.currentTimeMillis();
                    for (int i = 0; i < cnt; i++) {
                        while (System.currentTimeMillis() < next) Thread.sleep(1);
                        if (pl != null) try { stop.invoke(pl); } catch (Throwable t) {}
                        pl = mk(create, ctor, effect(1, evMs, FREQ, 0.7, 0));
                        startNoArg.invoke(pl);
                        next += evMs;
                    }
                    Thread.sleep(200);
                    if (pl != null) stop.invoke(pl);
                } else if (strategy.equals("sep_nostop")) {
                    Object pl = null;
                    long next = System.currentTimeMillis();
                    for (int i = 0; i < N; i++) {
                        while (System.currentTimeMillis() < next) Thread.sleep(1);
                        pl = mk(create, ctor, effect(1, EV, FREQ, 0.7, 0));
                        startNoArg.invoke(pl);
                        next += EV;
                    }
                    Thread.sleep(400);
                    if (pl != null) stop.invoke(pl);
                } else if (strategy.equals("sep_overlap")) {
                    Object prev = null;
                    long next = System.currentTimeMillis();
                    for (int i = 0; i < N; i++) {
                        while (System.currentTimeMillis() < next) Thread.sleep(1);
                        final Object old = prev;
                        Object pl = mk(create, ctor, effect(1, EV, FREQ, 0.7, 0));
                        startNoArg.invoke(pl);
                        prev = pl;
                        if (old != null && p1 > 0) {
                            new Thread(new Runnable() { public void run() {
                                try { Thread.sleep(p1); stop.invoke(old); } catch (Throwable t) {}
                            }}).start();
                        }
                        next += EV;
                    }
                    Thread.sleep(400);
                    if (prev != null) stop.invoke(prev);
                } else if (strategy.equals("loop_neg1")) {
                    Object pl = mk(create, ctor, effect(1, EV, FREQ, 0.7, 0));
                    startArgs.invoke(pl, -1, 0, 255, FREQ);
                    Thread.sleep(3600);
                    stop.invoke(pl);
                } else if (strategy.equals("nevents")) {
                    int cnt = p1;
                    int evMs = Integer.parseInt(a.length > 3 ? a[3] : "200");
                    Object pl = mk(create, ctor, effect(cnt, evMs, FREQ, 0.7, 0));
                    startNoArg.invoke(pl);
                    Thread.sleep((long) cnt * evMs + 500);
                    try { stop.invoke(pl); } catch (Throwable x) {}
                } else if (strategy.equals("edgeChain")) {
                    // p1=nEvents, a[3]=evMs, a[4]=count, a[5]=eps, a[6]=lead ; no stop, overlap lead
                    int n = p1;
                    int evMs = Integer.parseInt(a[3]);
                    int count = Integer.parseInt(a[4]);
                    int eps = Integer.parseInt(a.length > 5 ? a[5] : "4");
                    int lead = Integer.parseInt(a.length > 6 ? a[6] : "20");
                    long eff = (long) n * evMs;
                    Object pl = null;
                    long next = System.currentTimeMillis();
                    for (int i = 0; i < count; i++) {
                        while (System.currentTimeMillis() < next) Thread.sleep(1);
                        pl = mk(create, ctor, effectEdge(n, evMs, evMs, FREQ, 0.7, eps));
                        startNoArg.invoke(pl);   // no stop
                        next += eff - lead;
                    }
                    Thread.sleep(400);
                    if (pl != null) try { stop.invoke(pl); } catch (Throwable x) {}
                } else if (strategy.equals("edgeChainStop")) {
                    int n = p1;
                    int evMs = Integer.parseInt(a[3]);
                    int count = Integer.parseInt(a[4]);
                    int eps = Integer.parseInt(a.length > 5 ? a[5] : "4");
                    long eff = (long) n * evMs;
                    Object pl = null;
                    long next = System.currentTimeMillis();
                    for (int i = 0; i < count; i++) {
                        while (System.currentTimeMillis() < next) Thread.sleep(1);
                        Object old = pl;
                        pl = mk(create, ctor, effectEdge(n, evMs, evMs, FREQ, 0.7, eps));
                        startNoArg.invoke(pl);
                        if (old != null) try { stop.invoke(old); } catch (Throwable x) {}
                        next += eff;
                    }
                    Thread.sleep(400);
                    if (pl != null) try { stop.invoke(pl); } catch (Throwable x) {}
                } else if (strategy.equals("edgeRamp")) {
                    // 16 events x p1 ms, per-event intensity = 0.15 + 0.6*sin(pi*i/(n-1)),
                    // edge-clustered curve. Envelope should be a smooth hump with no boundary dips.
                    int n = 16;
                    int evMs = p1;
                    int eps = a.length > 3 ? Integer.parseInt(a[3]) : 4;
                    StringBuilder sb = new StringBuilder();
                    sb.append("{\"Metadata\":{\"Created\":\"merge\",\"Description\":\"hd\",\"Version\":1},\"Pattern\":[");
                    for (int i = 0; i < n; i++) {
                        if (i > 0) sb.append(",");
                        double inten = 0.15 + 0.6 * Math.sin(Math.PI * i / (n - 1));
                        sb.append("{\"Event\":{\"Type\":\"continuous\",\"RelativeTime\":").append(i * evMs)
                          .append(",\"Duration\":").append(evMs)
                          .append(",\"Parameters\":{\"Frequency\":").append(FREQ)
                          .append(",\"Intensity\":100,\"Curve\":").append(curveEdge(evMs, inten, eps)).append("}}}");
                    }
                    sb.append("]}");
                    Object pl = mk(create, ctor, sb.toString());
                    startNoArg.invoke(pl);
                    Thread.sleep((long) n * evMs + 500);
                    try { stop.invoke(pl); } catch (Throwable x) {}
                } else if (strategy.equals("edge")) {
                    // n events x evMs, edge-clustered curve. p1=n, a[3]=evMs, a[4]=eps
                    int n = p1;
                    int evMs = Integer.parseInt(a.length > 3 ? a[3] : "200");
                    int eps = Integer.parseInt(a.length > 4 ? a[4] : "4");
                    Object pl = mk(create, ctor, effectEdge(n, evMs, evMs, FREQ, 0.7, eps));
                    startNoArg.invoke(pl);
                    Thread.sleep((long) n * evMs + 500);
                    try { stop.invoke(pl); } catch (Throwable x) {}
                } else if (strategy.equals("edgeChunk")) {
                    // p1=nEvents, a[3]=evMs, a[4]=count, a[5]=eps ; restart each chunk (stop+start)
                    int nev = p1;
                    int evMs = Integer.parseInt(a[3]);
                    int count = Integer.parseInt(a[4]);
                    int eps = Integer.parseInt(a.length > 5 ? a[5] : "4");
                    int chunkMs = nev * evMs;
                    Object pl = null;
                    long next = System.currentTimeMillis();
                    for (int i = 0; i < count; i++) {
                        while (System.currentTimeMillis() < next) Thread.sleep(1);
                        if (pl != null) try { stop.invoke(pl); } catch (Throwable t) {}
                        pl = mk(create, ctor, effectEdge(nev, evMs, evMs, FREQ, 0.7, eps));
                        startNoArg.invoke(pl);
                        next += chunkMs;
                    }
                    Thread.sleep(200);
                    if (pl != null) stop.invoke(pl);
                } else if (strategy.equals("curve4t")) {
                    // single event, p1=dur, a[3..6]=times, a[7..10]=intensities
                    int dur = p1;
                    int t0 = Integer.parseInt(a[3]);
                    int t1 = Integer.parseInt(a[4]);
                    int t2 = Integer.parseInt(a[5]);
                    int t3 = Integer.parseInt(a[6]);
                    double i0 = Double.parseDouble(a[7]);
                    double i1 = Double.parseDouble(a[8]);
                    double i2 = Double.parseDouble(a[9]);
                    double i3 = Double.parseDouble(a[10]);
                    String json = "{\"Metadata\":{\"Created\":\"merge\",\"Description\":\"hd\",\"Version\":1},"
                        + "\"Pattern\":[{\"Event\":{\"Type\":\"continuous\",\"RelativeTime\":0,\"Duration\":" + dur
                        + ",\"Parameters\":{\"Frequency\":56,\"Intensity\":100,\"Curve\":["
                        + "{\"Frequency\":0.0,\"Intensity\":" + i0 + ",\"Time\":" + t0 + "},"
                        + "{\"Frequency\":0.0,\"Intensity\":" + i1 + ",\"Time\":" + t1 + "},"
                        + "{\"Frequency\":0.0,\"Intensity\":" + i2 + ",\"Time\":" + t2 + "},"
                        + "{\"Frequency\":0.0,\"Intensity\":" + i3 + ",\"Time\":" + t3 + "}]}}}]}";
                    Object pl = mk(create, ctor, json);
                    startNoArg.invoke(pl);
                    Thread.sleep((long) dur + 400);
                    try { stop.invoke(pl); } catch (Throwable x) {}
                } else if (strategy.equals("curve4")) {
                    // single event, p1 = duration, a[3..6] = four curve intensities
                    int dur = p1;
                    double i0 = Double.parseDouble(a[3]);
                    double i1 = Double.parseDouble(a[4]);
                    double i2 = Double.parseDouble(a[5]);
                    double i3 = Double.parseDouble(a[6]);
                    String json = "{\"Metadata\":{\"Created\":\"merge\",\"Description\":\"hd\",\"Version\":1},"
                        + "\"Pattern\":[{\"Event\":{\"Type\":\"continuous\",\"RelativeTime\":0,\"Duration\":" + dur
                        + ",\"Parameters\":{\"Frequency\":56,\"Intensity\":100,\"Curve\":["
                        + "{\"Frequency\":0.0,\"Intensity\":" + i0 + ",\"Time\":0},"
                        + "{\"Frequency\":0.0,\"Intensity\":" + i1 + ",\"Time\":" + (dur / 3) + "},"
                        + "{\"Frequency\":0.0,\"Intensity\":" + i2 + ",\"Time\":" + (2 * dur / 3) + "},"
                        + "{\"Frequency\":0.0,\"Intensity\":" + i3 + ",\"Time\":" + dur + "}]}}}]}";
                    Object pl = mk(create, ctor, json);
                    startNoArg.invoke(pl);
                    Thread.sleep(dur + 400);
                    try { stop.invoke(pl); } catch (Throwable x) {}
                } else if (strategy.equals("chain_single")) {
                    // Long single-event effects, started `lead` ms before the previous ends, no stop.
                    // p1 = event ms, a[3] = lead ms, a[4] = count
                    int dur = p1;
                    int lead = a.length > 3 ? Integer.parseInt(a[3]) : 50;
                    int count = a.length > 4 ? Integer.parseInt(a[4]) : 3;
                    Object pl = null;
                    long next = System.currentTimeMillis();
                    for (int i = 0; i < count; i++) {
                        while (System.currentTimeMillis() < next) Thread.sleep(1);
                        pl = mk(create, ctor, effect(1, dur, FREQ, 0.7, 0));
                        startNoArg.invoke(pl);   // no stop of previous
                        next += dur - lead;
                    }
                    Thread.sleep(400);
                    if (pl != null) try { stop.invoke(pl); } catch (Throwable x) {}
                } else if (strategy.equals("chain_single_stop")) {
                    // same but stop previous right after starting new (app-style replacement)
                    int dur = p1;
                    int count = a.length > 4 ? Integer.parseInt(a[4]) : 3;
                    Object pl = null;
                    long next = System.currentTimeMillis();
                    for (int i = 0; i < count; i++) {
                        while (System.currentTimeMillis() < next) Thread.sleep(1);
                        Object old = pl;
                        pl = mk(create, ctor, effect(1, dur, FREQ, 0.7, 0));
                        startNoArg.invoke(pl);
                        if (old != null) try { stop.invoke(old); } catch (Throwable x) {}
                        next += dur;
                    }
                    Thread.sleep(400);
                    if (pl != null) try { stop.invoke(pl); } catch (Throwable x) {}
                } else if (strategy.equals("shape")) {
                    // one 3000ms event, curve 0.1 -> 1.0 -> 1.0 -> 0.1 (tests whether a single
                    // event can carry a shaped envelope continuously)
                    String json = "{\"Metadata\":{\"Created\":\"merge\",\"Description\":\"hd\",\"Version\":1},"
                        + "\"Pattern\":[{\"Event\":{\"Type\":\"continuous\",\"RelativeTime\":0,\"Duration\":3000,"
                        + "\"Parameters\":{\"Frequency\":56,\"Intensity\":100,\"Curve\":["
                        + "{\"Frequency\":0.0,\"Intensity\":0.1,\"Time\":0},"
                        + "{\"Frequency\":0.0,\"Intensity\":1.0,\"Time\":1000},"
                        + "{\"Frequency\":0.0,\"Intensity\":1.0,\"Time\":2000},"
                        + "{\"Frequency\":0.0,\"Intensity\":0.1,\"Time\":3000}]}}}]}";
                    Object pl = mk(create, ctor, json);
                    startNoArg.invoke(pl);
                    Thread.sleep(3400);
                    try { stop.invoke(pl); } catch (Throwable x) {}
                } else if (strategy.equals("chunk")) {
                    // p1 = events per effect, a[3] = eventMs, a[4] = effect count
                    int nev = p1;
                    int evMs = Integer.parseInt(a[3]);
                    int count = Integer.parseInt(a[4]);
                    int chunkMs = nev * evMs;
                    Object pl = null;
                    long next = System.currentTimeMillis();
                    for (int i = 0; i < count; i++) {
                        while (System.currentTimeMillis() < next) Thread.sleep(1);
                        if (pl != null) try { stop.invoke(pl); } catch (Throwable t) {}
                        pl = mk(create, ctor, effect(nev, evMs, FREQ, 0.7, 0));
                        startNoArg.invoke(pl);
                        next += chunkMs;
                    }
                    Thread.sleep(200);
                    if (pl != null) stop.invoke(pl);
                } else if (strategy.equals("nstep")) {
                    int cnt = p1;
                    int evMs = Integer.parseInt(a[3]);
                    int step = Integer.parseInt(a[4]);
                    Object pl = mk(create, ctor, effectStep(cnt, evMs, step, FREQ, 0.7, 0));
                    startNoArg.invoke(pl);
                    Thread.sleep((long) cnt * step + 800);
                    try { stop.invoke(pl); } catch (Throwable x) {}
                } else if (strategy.equals("merged_chain")) {
                    Object pl = mk(create, ctor, effect(N, EV, FREQ, 0.7, 0));
                    startNoArg.invoke(pl);
                    long t = System.currentTimeMillis();
                    while (System.currentTimeMillis() - t < N * EV - 50) Thread.sleep(5);
                    Object pl2 = mk(create, ctor, effect(N, EV, FREQ, 0.7, 0));
                    startNoArg.invoke(pl2);   // no stop of pl
                    Thread.sleep(N * EV + 400);
                    try { stop.invoke(pl2); } catch (Throwable x) {}
                } else {
                    log("unknown strategy");
                }
            } catch (Throwable t) { log("player ERR " + t); }
            new android.os.Handler(Looper.getMainLooper()).post(new Runnable() {
                public void run() { Looper.myLooper().quit(); }
            });
        }});
        player.start();
        Looper.loop();
        sm.unregisterListener(l);
        bw.flush(); bw.close();
        log("samples=" + n[0] + " -> " + out);
        System.exit(0);
    }

    static Object mk(Method create, Constructor<?> ctor, String json) throws Exception {
        Object e = create.invoke(null, json);
        Object p = ctor.newInstance(e);
        setField(p, "mPackageName", "com.android.shell");
        return p;
    }
}
