import java.lang.reflect.*;

public class ApiDump {
    static void log(String s) { System.out.println("[ApiDump] " + s); }

    static void dump(String cn) {
        log("==== " + cn + " ====");
        try {
            Class<?> c = Class.forName(cn);
            log("-- constructors --");
            for (Constructor<?> k : c.getDeclaredConstructors()) log("  " + k);
            log("-- methods --");
            for (Method m : c.getDeclaredMethods()) log("  " + m);
            log("-- fields --");
            for (Field f : c.getDeclaredFields()) log("  " + f);
        } catch (Throwable t) {
            log("ERR " + t);
        }
    }

    public static void main(String[] a) {
        dump("android.os.HapticPlayer");
        dump("android.os.DynamicEffect");
        dump("android.os.HapticEffect");
        dump("android.os.HapticEffect$CurvePoint");
    }
}
