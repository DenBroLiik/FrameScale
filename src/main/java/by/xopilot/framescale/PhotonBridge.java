package by.xopilot.framescale;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Optional Iris integration. Fail closed when private APIs or pack options differ. */
final class PhotonBridge {
    private static final String TAA="TAA", TAAU="TAAU", SCALE="TAAU_RENDER_SCALE";
    private static Object invoke(Object obj, String method) throws ReflectiveOperationException {
        return obj.getClass().getMethod(method).invoke(obj);
    }
    private static Object call(Class<?> type, String method) throws ReflectiveOperationException {
        return type.getMethod(method).invoke(null);
    }
    private record State(Class<?> iris, String packName, Object options, Map<?,?> bools, Map<?,?> strings) {}
    private static State state() throws ReflectiveOperationException {
        Class<?> iris=Class.forName("net.irisshaders.iris.Iris");
        String name=String.valueOf(call(iris,"getCurrentPackName"));
        if (!name.toLowerCase(Locale.ROOT).contains("photon")) throw new IllegalStateException("Photon is not active: "+name);
        Object pack=((Optional<?>)call(iris,"getCurrentPack")).orElseThrow(()->new IllegalStateException("No loaded shaderpack"));
        Object options=invoke(pack,"getShaderPackOptions");
        Object set=invoke(options,"getOptionSet");
        Map<?,?> bools=(Map<?,?>)invoke(set,"getBooleanOptions");
        Map<?,?> strings=(Map<?,?>)invoke(set,"getStringOptions");
        if (!bools.containsKey(TAA) || !bools.containsKey(TAAU) || !strings.containsKey(SCALE))
            throw new IllegalStateException("Photon options not supported by this version");
        return new State(iris,name,options,bools,strings);
    }
    static String status() throws ReflectiveOperationException {
        State s=state(); return s.packName+" | TAA/TAAU/scale options available";
    }
    @SuppressWarnings("unchecked")
    static String apply(int percent) throws ReflectiveOperationException {
        if(percent<50||percent>100) throw new IllegalArgumentException("50–100 only");
        State s=state();
        Object option=invoke(s.strings.get(SCALE),"getOption");
        String value=String.format(Locale.ROOT,"%.2f",percent/100.0);
        Object allowed;
        try { allowed=invoke(option,"getAllowedValues"); }
        catch(NoSuchMethodException e) { allowed=invoke(option,"getValues"); }
        if (!(allowed instanceof java.util.Collection<?> values)) throw new IllegalStateException("Unknown Iris option format");
        // Iris can use 0.5 or 0.50; preserve the exact declared option value.
        String declared=null;
        for(Object candidate: values) if(candidate instanceof String text) {
            try { if(Math.abs(Double.parseDouble(text)-percent/100.0)<1e-8) { declared=text; break; } }
            catch(NumberFormatException ignored) { }
        }
        if(declared==null) throw new IllegalStateException("Scale not allowed: "+value);
        Map<String,String> queue=(Map<String,String>)call(s.iris,"getShaderPackOptionQueue");
        if(!queue.isEmpty()) throw new IllegalStateException("Pending Iris changes; apply them first");
        Method reload=s.iris.getMethod("reload");
        queue.put(TAA,"true"); queue.put(TAAU,"true"); queue.put(SCALE,declared);
        try {
            reload.invoke(null);
            State updated=state();
            if(!updated.packName.equals(s.packName)) throw new IllegalStateException("Shader pack changed during reload");
        } finally { queue.remove(TAA); queue.remove(TAAU); queue.remove(SCALE); }
        return "Photon scale requested: "+percent+"%. Reload finished; verify in shader settings.";
    }
}
