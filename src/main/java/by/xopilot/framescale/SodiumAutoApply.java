package by.xopilot.framescale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;
import java.lang.reflect.Method;

/**
 * Sodium's public config API stages values until Apply. The 0.8.13 implementation
 * exposes single-option apply; probe it only on that version and never apply other mods' options.
 */
final class SodiumAutoApply {
    private static final String SCREEN="net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen";
    private static final ResourceLocation ID=ResourceLocation.fromNamespaceAndPath("framescale","render_scale");
    private Object config,option;
    private Method apply,modify,changed,validated;
    private boolean unavailable;
    private int last=Integer.MIN_VALUE,settled;
    private Screen screen;
    private static boolean isSodium(Screen s) { return s!=null && SCREEN.equals(s.getClass().getName()); }
    private boolean connect() throws ReflectiveOperationException {
        if(unavailable) return false;
        String version=CompatInventory.loaded().getOrDefault("sodium","");
        if(!version.equals("0.8.13")&&!version.startsWith("0.8.13+")) return false;
        Class<?> manager=Class.forName("net.caffeinemc.mods.sodium.client.config.ConfigManager");
        Object next=manager.getField("CONFIG").get(null);
        if(next==null) return false;
        if(next!=config || option==null) {
            Object found=next.getClass().getMethod("getOption",ResourceLocation.class).invoke(next,ID);
            if(found==null) return false;
            apply=next.getClass().getMethod("applyOption",ResourceLocation.class);
            modify=found.getClass().getMethod("modifyValue",Object.class);
            changed=found.getClass().getMethod("hasChanged");
            validated=found.getClass().getMethod("getValidatedValue");
            config=next; option=found;
        }
        return true;
    }
    private void sync() throws ReflectiveOperationException {
        int value=NativeScaleOption.load();
        NativeScaleOption.beginSync();
        try {
            modify.invoke(option,Integer.valueOf(value));
            if(Boolean.TRUE.equals(changed.invoke(option))) apply.invoke(config,ID);
        } finally { NativeScaleOption.endSync(); }
        last=Integer.MIN_VALUE; settled=0;
    }
    // Run before Sodium rebuilds its option widgets, so the native slider reflects current Iris.
    void init(Screen s) {
        if(!isSodium(s)) return;
        try { if(connect()) { sync(); screen=s; } }
        catch(ReflectiveOperationException|RuntimeException|LinkageError e) { disable(e); }
    }
    void tick() {
        if(unavailable) return;
        Minecraft mc=Minecraft.getInstance();
        if(!isSodium(mc.screen)) { screen=null; settled=0; last=Integer.MIN_VALUE; return; }
        try {
            if(!connect()) return;
            if(screen!=mc.screen || NativeScaleOption.needsSync()) { sync(); screen=mc.screen; }
            if(!NativeScaleOption.available() || !Boolean.TRUE.equals(changed.invoke(option))) { settled=0; last=Integer.MIN_VALUE; return; }
            int value=(Integer)validated.invoke(option);
            if(GLFW.glfwGetMouseButton(mc.getWindow().getWindow(),GLFW.GLFW_MOUSE_BUTTON_LEFT)==GLFW.GLFW_PRESS) {
                last=value; settled=0; return;
            }
            if(value!=last) { last=value; settled=0; return; }
            if(++settled>=3) {
                apply.invoke(config,ID);
                if(NativeScaleOption.needsSync()) sync();
                settled=0;
            }
        } catch(ReflectiveOperationException|RuntimeException|LinkageError e) { disable(e); }
    }
    private void disable(Throwable e) {
        unavailable=true;
        LoggerFactory.getLogger("FrameScale").warn("Sodium single-option auto-apply unavailable; standard Apply remains usable",e);
    }
}
