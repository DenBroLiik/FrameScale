package by.xopilot.framescale;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Iris remains the source of truth; 49 represents TAAU off in the native integer slider. */
final class NativeScaleOption {
    static final int AUTO=48, OFF=49;
    private static final Logger LOG=LoggerFactory.getLogger("FrameScale");
    private static PhotonBridge.Settings baseline;
    private static boolean syncing, needsSync;
    private static String error="";
    static int load() {
        baseline=null;
        try {
            if(CompatPolicy.evaluate(CompatInventory.loaded()).scale())
                baseline=PhotonBridge.read();
        } catch(ReflectiveOperationException|RuntimeException|LinkageError ignored) { }
        return current();
    }
    static int current() { return FrameScale.autoEnabled()?AUTO:baseline!=null&&baseline.enabled()?baseline.percent():OFF; }
    // Require the documented Photon 1%-step range. Other packs and variants keep the fallback screen.
    static boolean available() {
        return baseline!=null && baseline.allowed().size()==51
            && baseline.allowed().getFirst()==50 && baseline.allowed().getLast()==100
            && CompatPolicy.evaluate(CompatInventory.loaded()).scale();
    }
    static void beginSync() { syncing=true; }
    static void endSync() { syncing=false; needsSync=false; }
    static boolean needsSync() { return needsSync; }
    static void save(int selected) {
        if(syncing || selected==current()) return;
        if(selected==AUTO) { if(!FrameScale.enableAuto()) needsSync=true; return; }
        FrameScale.disableAuto();
        load();
        try {
            if(!available()) throw new IllegalStateException("Photon/Iris scale option unavailable");
            FrameScale.stopActiveProfiler();
            var before=baseline;
            baseline=PhotonBridge.apply(before,selected!=OFF,selected==OFF?before.percent():selected);
            error="";
        } catch(ReflectiveOperationException|RuntimeException|LinkageError e) {
            error=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
            LOG.warn("FrameScale scale change failed",e);
            needsSync=true;
            if(Minecraft.getInstance().gui!=null)
                Minecraft.getInstance().gui.getChat().addMessage(Component.translatable("framescale.error",error));
        }
    }
    static String transition(int selected) {
        var window=Minecraft.getInstance().getWindow();
        int w=window.getWidth(),h=window.getHeight();
        if(w<=0||h<=0) return "—";
        int effective=selected==AUTO?(baseline!=null&&baseline.enabled()?baseline.percent():100):selected==OFF?100:selected;
        return new Resolution(w,h).transition(effective);
    }
    static Component format(int selected) {
        if(selected==AUTO) return Component.translatable("framescale.native.auto",transition(selected));
        if(selected==OFF) return Component.translatable("framescale.native.off",transition(selected));
        return Component.literal(transition(selected));
    }
    static Component tooltip(int selected) {
        if(selected==AUTO) return Component.translatable("framescale.auto.tooltip",FrameScale.autoTarget(),FrameScale.autoFloor(),FrameScale.autoStatus());
        var c=Component.translatable("framescale.native.tooltip",selected==OFF?100:selected,transition(selected));
        if(!error.isBlank()) c.append(Component.literal("\n")).append(Component.translatable("framescale.error",error));
        return c;
    }
}
