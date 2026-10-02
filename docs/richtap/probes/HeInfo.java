import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

public class HeInfo {
    static void log(String s) { System.out.println("[HeInfo] " + s); }
    static void setF(Object o, String n, Object v) throws Exception {
        Field f = o.getClass().getDeclaredField(n); f.setAccessible(true); f.set(o, v);
    }
    static Object getF(Object o, String n) throws Exception {
        Field f = o.getClass().getDeclaredField(n); f.setAccessible(true); return f.get(o);
    }

    @SuppressWarnings("unchecked")
    static void dumpEffect(Object de) throws Exception {
        if (de == null) { log("  effect = null"); return; }
        log("  class=" + de.getClass().getName());
        List<Object> eff = (List<Object>) getF(de, "mEffects");
        log("  mEffects.size = " + (eff == null ? "null" : eff.size()));
        log("  getDuration = " + de.getClass().getMethod("getDuration").invoke(de));
        if (eff != null) {
            for (int i = 0; i < eff.size(); i++) {
                Object e = eff.get(i);
                StringBuilder sb = new StringBuilder("    #" + i + " " + e.getClass().getSimpleName());
                for (String fn : new String[]{"mType","mRelativeTime","mDuration","mIntensity","mSharpness"}) {
                    try { sb.append(" ").append(fn).append("=").append(getF(e, fn)); } catch (Throwable t) {}
                }
                try {
                    List<Object> cs = (List<Object>) getF(e, "mCurves");
                    sb.append(" curves=").append(cs == null ? "null" : cs.size());
                } catch (Throwable t) {}
                log(sb.toString());
            }
        }
        try { log("  debug=" + de.getClass().getMethod("toDebugString").invoke(de)); } catch (Throwable t) {}
    }

    public static void main(String[] a) throws Exception {
        Class<?> d = Class.forName("android.os.DynamicEffect");
        Method create = d.getMethod("create", String.class);
        for (String p : a) {
            String json = new String(Files.readAllBytes(Paths.get(p)), java.nio.charset.StandardCharsets.UTF_8);
            log("==== " + new File(p).getName() + " (" + json.length() + "B) ====");
            Object de;
            try { de = create.invoke(null, json); }
            catch (Throwable t) { log("  create ERR " + t.getCause()); continue; }
            dumpEffect(de);
        }
    }
}
