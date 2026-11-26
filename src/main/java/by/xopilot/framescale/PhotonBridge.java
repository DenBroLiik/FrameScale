package by.xopilot.framescale;

import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.List;

/** Optional, version-probed adapter. Iris owns the actual shader settings and their persistence. */
final class PhotonBridge {
    private static final String TAA = "TAA", TAAU = "TAAU", SCALE = "TAAU_RENDER_SCALE";
    record Settings(String pack, boolean taa, boolean enabled, int percent, List<Integer> allowed) {
        Settings { allowed = List.copyOf(allowed); }
    }
    private record State(Class<?> iris, Object pack, String name, Object values, Map<?,?> bools, Map<?,?> strings) {}
    private static Object invoke(Object obj, String method) throws ReflectiveOperationException {
        return obj.getClass().getMethod(method).invoke(obj);
    }
    private static Object call(Class<?> type, String method) throws ReflectiveOperationException {
        return type.getMethod(method).invoke(null);
    }
    private static Object value(State s, String method, String key) throws ReflectiveOperationException {
        Class<?> api = Class.forName("net.irisshaders.iris.shaderpack.option.values.OptionValues");
        return api.getMethod(method, String.class).invoke(s.values, key);
    }
    private static State state() throws ReflectiveOperationException {
        Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
        String name = String.valueOf(call(iris, "getCurrentPackName"));
        if (!name.toLowerCase(Locale.ROOT).contains("photon")) throw new IllegalStateException("Photon is not active: " + name);
        Object pack = ((Optional<?>) call(iris, "getCurrentPack")).orElseThrow(() -> new IllegalStateException("No loaded shader pack"));
        Object options = invoke(pack, "getShaderPackOptions");
        Object set = invoke(options, "getOptionSet");
        Map<?,?> bools = (Map<?,?>) invoke(set, "getBooleanOptions");
        Map<?,?> strings = (Map<?,?>) invoke(set, "getStringOptions");
        if (!bools.containsKey(TAA) || !bools.containsKey(TAAU) || !strings.containsKey(SCALE))
            throw new IllegalStateException("This Photon version does not expose TAA/TAAU/scale");
        return new State(iris, pack, name, invoke(options, "getOptionValues"), bools, strings);
    }
    private static Object option(Map<?,?> map, String key) throws ReflectiveOperationException {
        return invoke(map.get(key), "getOption");
    }
    private static boolean bool(State s, String key) throws ReflectiveOperationException {
        boolean def = (Boolean) invoke(option(s.bools,key), "getDefaultValue");
        Object result = value(s, "getBooleanValue", key);
        if (result instanceof Boolean b) return b;
        if (result instanceof Enum<?> e) return switch (e.name()) {
            case "TRUE" -> true; case "FALSE" -> false; case "DEFAULT" -> def;
            default -> throw new IllegalStateException("Unknown boolean option state: " + e.name());
        };
        throw new IllegalStateException("Unknown Iris boolean option representation");
    }
    private static String str(State s, String key) throws ReflectiveOperationException {
        Object result = value(s,"getStringValue",key);
        if (!(result instanceof Optional<?> optional)) throw new IllegalStateException("Unknown Iris string option representation");
        return optional.isPresent() ? (String)optional.get() : (String)invoke(option(s.strings,key), "getDefaultValue");
    }
    private static int percent(String text) {
        double p = Double.parseDouble(text) * 100;
        if (!Double.isFinite(p) || p < 50 - 1e-6 || p > 100 + 1e-6 || Math.abs(p - Math.round(p)) > 1e-6)
            throw new IllegalArgumentException("Unsupported render scale: " + text);
        return (int)Math.round(p);
    }
    private static TreeMap<Integer,String> allowed(State s) throws ReflectiveOperationException {
        Object opt = option(s.strings,SCALE);
        Object raw = invoke(opt, "getAllowedValues");
        if (!(raw instanceof Collection<?> values)) throw new IllegalStateException("Unknown Iris scale format");
        var map = new TreeMap<Integer,String>();
        for (Object v : values) if (v instanceof String text) {
            try { map.putIfAbsent(percent(text),text); } catch (IllegalArgumentException ignored) {}
        }
        if (map.isEmpty()) throw new IllegalStateException("No supported 50–100% scales");
        return map;
    }
    private static Settings read(State s) throws ReflectiveOperationException {
        int scale=percent(str(s,SCALE));
        var options=allowed(s);
        if (!options.containsKey(scale)) throw new IllegalStateException("Current scale not in declared values");
        return new Settings(s.name,bool(s,TAA),bool(s,TAAU),scale,List.copyOf(options.keySet()));
    }
    static Settings read() throws ReflectiveOperationException { return read(state()); }
    static String status() throws ReflectiveOperationException {
        Settings s=read(); return s.pack()+" | TAA: "+s.taa()+" | TAAU: "+s.enabled()+" | "+s.percent()+"%";
    }
    static String apply(int percent) throws ReflectiveOperationException {
        Settings before=read();
        apply(before,true,percent);
        return status();
    }
    static Settings apply(Settings expected, boolean enabled, int percent) throws ReflectiveOperationException {
        return change(expected,enabled||expected.taa(),enabled,percent);
    }
    static Settings restore(Settings expected,Settings previous) throws ReflectiveOperationException {
        if(!expected.pack().equals(previous.pack())) throw new IllegalStateException("Shader pack changed");
        return change(expected,previous.taa(),previous.enabled(),previous.percent());
    }
    @SuppressWarnings("unchecked")
    private static Settings change(Settings expected,boolean taa,boolean enabled,int percent) throws ReflectiveOperationException {
        State s=state();
        Settings before=read(s);
        if (!before.equals(expected)) throw new IllegalStateException("Iris settings changed; reopen FrameScale");
        String declared=allowed(s).get(percent);
        if (declared==null) throw new IllegalArgumentException("Scale not allowed: "+percent);
        if (before.enabled()==enabled && before.percent()==percent && before.taa()==taa) return before;
        Map<String,String> queue=(Map<String,String>)call(s.iris,"getShaderPackOptionQueue");
        if (!queue.isEmpty()) throw new IllegalStateException("Apply or cancel pending changes in Iris first");
        if (Boolean.TRUE.equals(call(s.iris,"shouldResetShaderPackOptionsOnNextReload")))
            throw new IllegalStateException("Iris reset pending; finish it first");
        var reload=s.iris.getMethod("reload");
        try {
            if (before.taa()!=taa) queue.put(TAA,Boolean.toString(taa));
            if (before.enabled()!=enabled) queue.put(TAAU,Boolean.toString(enabled));
            if (before.percent()!=percent) queue.put(SCALE,declared);
            reload.invoke(null);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Iris reload failed; check shader settings", e.getCause());
        } finally {
            queue.remove(TAA); queue.remove(TAAU); queue.remove(SCALE);
        }
        if (Boolean.TRUE.equals(call(s.iris,"isFallback"))) throw new IllegalStateException("Iris entered fallback after reload");
        Settings actual=read();
        if (!actual.pack().equals(before.pack()) || actual.enabled()!=enabled || actual.percent()!=percent || actual.taa()!=taa)
            throw new IllegalStateException("Iris did not confirm requested settings");
        return actual;
    }
}
