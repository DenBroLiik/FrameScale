package by.xopilot.framescale;

import java.lang.reflect.Method;

/** Unknown API or shader state is always treated as incompatible. */
final class IrisSpatialGuard {
    private static Object api;
    private static Method active, config, enabled;
    private static boolean failed;
    static boolean shadersDisabled() {
        if(failed) return false;
        try {
            if(api==null) {
                Class<?> type=Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                active=type.getMethod("isShaderPackInUse");
                config=type.getMethod("getConfig");
                enabled=Class.forName("net.irisshaders.iris.api.v0.IrisApiConfig").getMethod("areShadersEnabled");
                api=type.getMethod("getInstance").invoke(null);
            }
            return Boolean.FALSE.equals(active.invoke(api))
                && Boolean.FALSE.equals(enabled.invoke(config.invoke(api)));
        } catch(ReflectiveOperationException|RuntimeException|LinkageError e) {
            failed=true;return false;
        }
    }
}
