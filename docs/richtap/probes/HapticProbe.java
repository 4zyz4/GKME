import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class HapticProbe {
    static void log(String s) { System.out.println("[HapticProbe] " + s); }

    static void setField(Object o, String name, Object val) {
        try { Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); f.set(o, val); }
        catch (Throwable t) { log("set " + name + " failed: " + t); }
    }

    public static void main(String[] args) throws Exception {
        Class<?> hp = Class.forName("android.os.HapticPlayer");
        Class<?> de = Class.forName("android.os.DynamicEffect");
        log("isAvailable=" + hp.getMethod("isAvailable").invoke(null) + " version=" + hp.getMethod("getVersion").invoke(null));

        String json = new String(Files.readAllBytes(Paths.get(args[0].substring(1))), StandardCharsets.UTF_8);
        log("json len=" + json.length());

        Object effect = de.getMethod("create", String.class).invoke(null, json);
        log("effect=" + effect);
        try { log("getDuration=" + de.getMethod("getDuration").invoke(effect)); } catch (Throwable t) { log("getDuration err " + t); }
        try { Field mf = de.getField("mEffects"); Object v = mf.get(effect); log("mEffects=" + v); } catch (Throwable t) { log("mEffects err " + t); }
        try { int[] enc = (int[]) de.getMethod("encapsulate").invoke(effect); log("encapsulate.len=" + enc.length + " = " + java.util.Arrays.toString(enc)); } catch (Throwable t) { log("encapsulate err " + t); }
        try { log("version=" + de.getField("version").get(effect) + " loop=" + de.getField("mLoop").get(effect) + " interval=" + de.getField("mInterval").get(effect)); } catch (Throwable t) {}

        Object player = hp.getConstructor(de).newInstance(effect);
        setField(player, "mPackageName", "com.android.shell");
        Method start = hp.getMethod("start", int.class, int.class, int.class, int.class);
        log("start(" + args[1] + "," + args[2] + "," + args[3] + "," + args[4] + ")");
        start.invoke(player, Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]));
        Thread.sleep(Integer.parseInt(args[5]));
        try { hp.getMethod("stop").invoke(player); } catch (Throwable t) { log("stop err " + t); }
        log("done.");
    }
}
