import java.lang.reflect.*;

public class ApiDump2 {
    static void log(String s) { System.out.println("[D] " + s); }

    static void dump(String cn) {
        log("==== " + cn + " ====");
        try {
            Class<?> c = Class.forName(cn);
            for (Constructor<?> k : c.getDeclaredConstructors()) log("  ctor " + k);
            for (Method m : c.getDeclaredMethods()) log("  m " + m);
            for (Field f : c.getDeclaredFields()) log("  f " + f);
        } catch (Throwable t) { log("  ERR " + t); }
    }

    static void consts(String cn) {
        log("==== consts " + cn + " ====");
        try {
            Class<?> c = Class.forName(cn);
            for (Field f : c.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) && Modifier.isFinal(mod)) {
                    f.setAccessible(true);
                    try { log("  " + f.getName() + " = " + f.get(null)); } catch (Throwable t) {}
                }
            }
        } catch (Throwable t) { log("  ERR " + t); }
    }

    public static void main(String[] a) {
        consts("android.os.DynamicEffect");
        dump("android.os.DynamicEffect$Parameter");
        dump("android.os.DynamicEffect$PrimitiveEffect");
        dump("android.os.Pattern");
    }
}
