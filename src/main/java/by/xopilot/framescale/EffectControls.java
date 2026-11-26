package by.xopilot.framescale;

import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import org.slf4j.LoggerFactory;
import java.util.Locale;

/** Sodium native selector and command front end; explicit, reversible session presets. */
final class EffectControls {
    private static final EffectSession session=new EffectSession();
    private static final SamplingBudget.Preset[] PRESETS=SamplingBudget.Preset.values();
    private static boolean available,syncing,needsSync;
    private static String error="";
    static int load() {
        available=false;
        try {
            if(CompatPolicy.evaluate(CompatInventory.loaded()).scale()) {
                var snapshot=PhotonBridge.readEffects();
                available=!snapshot.options().isEmpty();
                if(session.active()!=SamplingBudget.Preset.ORIGINAL&&!session.owns(snapshot)) {
                    error="Photon effect options changed externally; restore refused. Use /framescale effects forget to start a new baseline.";
                }
            }
        } catch(ReflectiveOperationException|RuntimeException|LinkageError ignored) {}
        return session.active().ordinal();
    }
    static boolean available() { return available; }
    static void beginSync() { syncing=true; }
    static void endSync() { syncing=false;needsSync=false; }
    static boolean needsSync() { return needsSync; }
    static Component format(int i) { return Component.translatable("framescale.effects."+PRESETS[i].name().toLowerCase(Locale.ROOT)); }
    static Component tooltip(int i) {
        var text=Component.translatable("framescale.effects.tip."+PRESETS[i].name().toLowerCase(Locale.ROOT));
        if(!error.isBlank()) text.append(Component.literal("\n"+error));
        return text;
    }
    static SamplingBudget.Preset parse(String value) {
        return SamplingBudget.Preset.valueOf(value.toUpperCase(Locale.ROOT));
    }
    private static EffectSession.Change plan(SamplingBudget.Preset preset) throws ReflectiveOperationException {
        if(!CompatPolicy.evaluate(CompatInventory.loaded()).scale()) throw new IllegalStateException("Competing renderer/upscaler");
        var snapshot=PhotonBridge.readEffects();
        if(snapshot.options().isEmpty()) throw new IllegalStateException("No recognized Photon effect options");
        return session.preview(snapshot,preset);
    }
    private static String description(EffectSession.Change change) {
        var diff=SamplingBudget.changes(change.expected().values(),change.desired());
        if(diff.isEmpty()) return "No compatible effect changes required";
        var text=new StringBuilder();
        diff.forEach((k,v)->text.append("\n").append(k).append(": ").append(change.expected().values().get(k)).append(" → ").append(v));
        return text.toString().stripLeading();
    }
    static String preview(SamplingBudget.Preset preset) throws ReflectiveOperationException {
        return "Proposed "+preset+": "+description(plan(preset));
    }
    static String apply(SamplingBudget.Preset preset) throws ReflectiveOperationException {
        var change=plan(preset);
        var diff=SamplingBudget.changes(change.expected().values(),change.desired());
        if(diff.isEmpty()) {
            if(preset==SamplingBudget.Preset.ORIGINAL||session.active()!=SamplingBudget.Preset.ORIGINAL)
                session.confirm(change,change.expected());
            error="";return "No effect changes required";
        }
        FrameScale.disableAuto();
        FrameScale.stopActiveProfiler();
        var actual=PhotonBridge.applyEffects(change);
        session.confirm(change,actual);
        error="";
        return "Applied "+preset+" (one shader reload): "+description(change);
    }
    static String status() {
        return "Effects: "+session.active()+" | "+(available?"Photon options available":"availability not confirmed")+
            (error.isBlank()?"":" | "+error)+" | Undo is session-only.";
    }
    static void forget() { session.clear();error="";needsSync=true; }
    static void save(int i) {
        if(syncing||i==session.active().ordinal()) return;
        String text;
        try { text=apply(PRESETS[i]); }
        catch(ReflectiveOperationException|RuntimeException|LinkageError e) {
            error=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
            LoggerFactory.getLogger("FrameScale").warn("Effect preset failed",e);text="Effect preset failed: "+error;
        }
        needsSync=true;
        var gui=Minecraft.getInstance().gui;
        if(gui!=null) gui.getChat().addMessage(Component.literal("[FrameScale] "+text));
    }
}
