package by.xopilot.framescale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Version-probed single-option application. Never invokes Sodium.applyAllOptions. */
final class SodiumAutoApply {
    private static final String SCREEN="net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen";
    private static final List<String> IDS=List.of("target_fps","quality_floor","render_scale","effect_budget");
    private record Slot(ResourceLocation id,Object option,Method modify,Method changed,Method validated) {
        boolean dirty() throws ReflectiveOperationException { return Boolean.TRUE.equals(changed.invoke(option)); }
        int value() throws ReflectiveOperationException { return (Integer)validated.invoke(option); }
    }
    private Object config;
    private Method apply;
    private List<Slot> slots=List.of();
    private boolean unavailable;
    private List<Integer> last=List.of();
    private int settled;
    private Screen screen;
    private static boolean isSodium(Screen s) { return s!=null && SCREEN.equals(s.getClass().getName()); }
    private boolean connect() throws ReflectiveOperationException {
        if(unavailable) return false;
        if(config!=null&&!slots.isEmpty()) return true;
        String version=CompatInventory.loaded().getOrDefault("sodium","");
        if(!version.equals("0.8.13")&&!version.startsWith("0.8.13+")) return false;
        Object next=Class.forName("net.caffeinemc.mods.sodium.client.config.ConfigManager").getField("CONFIG").get(null);
        if(next==null) return false;
        var found=new ArrayList<Slot>();
        for(String name:IDS) {
            var id=ResourceLocation.fromNamespaceAndPath("framescale",name);
            Object o=next.getClass().getMethod("getOption",ResourceLocation.class).invoke(next,id);
            if(o==null) return false;
            found.add(new Slot(id,o,o.getClass().getMethod("modifyValue",Object.class),
                o.getClass().getMethod("hasChanged"),o.getClass().getMethod("getValidatedValue")));
        }
        apply=next.getClass().getMethod("applyOption",ResourceLocation.class);
        config=next; slots=List.copyOf(found); return true;
    }
    private void sync() throws ReflectiveOperationException {
        int scale=NativeScaleOption.load();
        int[] values={FrameScale.autoTarget(),FrameScale.autoFloor(),scale,EffectControls.load()};
        NativeScaleOption.beginSync(); EffectControls.beginSync();
        try {
            for(int i=0;i<slots.size();i++) {
                var s=slots.get(i); s.modify.invoke(s.option,Integer.valueOf(values[i]));
                if(s.dirty()) apply.invoke(config,s.id);
            }
        } finally { NativeScaleOption.endSync(); EffectControls.endSync(); }
        last=List.of(); settled=0;
    }
    void init(Screen s) {
        if(!isSodium(s)) return;
        try { if(connect()) { sync(); screen=s; } }
        catch(ReflectiveOperationException|RuntimeException|LinkageError e) { disable(e); }
    }
    void tick() {
        if(unavailable) return;
        Minecraft mc=Minecraft.getInstance();
        if(!isSodium(mc.screen)) { screen=null; config=null; slots=List.of(); settled=0; last=List.of(); return; }
        try {
            if(!connect()) return;
            if(screen!=mc.screen||(NativeScaleOption.needsSync()||EffectControls.needsSync())) { sync(); screen=mc.screen; }
            var values=new ArrayList<Integer>();
            boolean dirty=false;
            for(var s:slots) { boolean d=s.dirty(); dirty|=d; values.add(d?s.value():-1); }
            if(!dirty) { settled=0; last=List.of(); return; }
            if(GLFW.glfwGetMouseButton(mc.getWindow().getWindow(),GLFW.GLFW_MOUSE_BUTTON_LEFT)==GLFW.GLFW_PRESS) {
                last=List.copyOf(values); settled=0; return;
            }
            if(!values.equals(last)) { last=List.copyOf(values); settled=0; return; }
            if(++settled>=3) {
                for(var s:slots) if(s.dirty()) apply.invoke(config,s.id);
                if((NativeScaleOption.needsSync()||EffectControls.needsSync())) sync();
                settled=0;
            }
        } catch(ReflectiveOperationException|RuntimeException|LinkageError e) { disable(e); }
    }
    private void disable(Throwable e) {
        unavailable=true;
        LoggerFactory.getLogger("FrameScale").warn("Sodium single-option auto-apply unavailable; standard Apply remains usable",e);
    }
}
