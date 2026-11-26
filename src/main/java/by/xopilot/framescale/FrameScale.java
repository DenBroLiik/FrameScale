package by.xopilot.framescale;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import java.util.Locale;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.minecraft.client.gui.screens.Screen;

@Mod(value="framescale", dist=Dist.CLIENT)
public final class FrameScale {
    private static FrameScale instance;
    private final FrameStats frames=new FrameStats(1800), gpu=new FrameStats(1800);
    private final GpuTimer timer=new GpuTimer();
    private boolean measuring, gpuAvailable, gpuFailed, gpuPermitted;
    private long lastFrame;
    public FrameScale(ModContainer container) {
        instance=this;
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> settings(parent));
        NeoForge.EVENT_BUS.addListener(this::screenInit);
        NeoForge.EVENT_BUS.addListener(this::commands);
        NeoForge.EVENT_BUS.addListener(this::pre);
        NeoForge.EVENT_BUS.addListener(this::post);
    }
    private Screen settings(Screen parent) { return createSettings(parent); }
    static Screen createSettings(Screen parent) { return new FrameScaleScreen(parent, ()->{ if(instance!=null) instance.stop(); }); }
    private void screenInit(ScreenEvent.Init.Post event) { VideoSettingsEntry.add(event, this::settings); }
    private void pre(RenderFrameEvent.Pre ignored) {
        if (!measuring || Minecraft.getInstance().level==null) { lastFrame=0; return; }
        long now=System.nanoTime();
        if(lastFrame!=0) frames.add((now-lastFrame)/1_000_000.0);
        lastFrame=now;
        if (!gpuFailed && gpuPermitted) {
            try { gpuAvailable=timer.begin(gpu); }
            catch (RuntimeException | LinkageError e) { gpuFailed=true; gpuAvailable=false; }
        }
    }
    private void post(RenderFrameEvent.Post ignored) { if(measuring && !gpuFailed && gpuPermitted) {
            try { timer.end(); }
            catch (RuntimeException | LinkageError e) { gpuFailed=true; gpuAvailable=false; }
        } }
    private void stop() { measuring=false; lastFrame=0; try { timer.close(); } catch (RuntimeException | LinkageError e) { gpuAvailable=false; } }
    private void start() { stop(); frames.clear(); gpu.clear(); gpuAvailable=false; gpuFailed=false; gpuPermitted=CompatPolicy.evaluate(CompatInventory.loaded()).gpuTimer(); measuring=true; }
    private int message(String s) {
        Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[FrameScale] "+s)); return 1;
    }
    private int report() {
        var f=frames.snapshot(); var g=gpu.snapshot();
        if(f.count()==0) return message("No frame samples. /framescale bench start");
        return message(String.format(Locale.ROOT,"%d frames | %.1f FPS | 1%% low %.1f | p95 %.2f ms | GPU %s",f.count(),f.fps(),f.low1Fps(),f.p95Ms(),gpuAvailable&&g.count()>0?String.format(Locale.ROOT,"%.2f ms mean / %.2f ms p95 (%d samples)",g.meanMs(),g.p95Ms(),g.count()):"unavailable"));
    }
    private int status() { try { return message(PhotonBridge.status()); } catch(ReflectiveOperationException|RuntimeException|LinkageError e){ return message("Unsupported Iris/Photon: "+e); } }
    private int scale(int n) {
        var decision=CompatPolicy.evaluate(CompatInventory.loaded());
        if (!decision.scale()) return message("Scale blocked: "+decision.reason());
        stop();
        try { return message(PhotonBridge.apply(n)); }
        catch(ReflectiveOperationException|RuntimeException|LinkageError e){ return message("Scale not confirmed: "+e+". Check Iris settings; use its reload control if needed."); }
    }
    private int compatExport() {
        try { return message("Saved: "+CompatInventory.export(Minecraft.getInstance().gameDirectory.toPath())); }
        catch (java.io.IOException | RuntimeException e) { return message("Export failed: "+e.getClass().getSimpleName()); }
    }
    private void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("framescale")
            .executes(c->status())
            .then(Commands.literal("status").executes(c->status()))
            .then(Commands.literal("compat").executes(c->message(CompatInventory.summary(CompatInventory.loaded())))
                .then(Commands.literal("export").executes(c->compatExport())))
            .then(Commands.literal("scale").then(Commands.argument("percent",IntegerArgumentType.integer(50,100)).executes(c->scale(IntegerArgumentType.getInteger(c,"percent")))))
            .then(Commands.literal("bench")
                .then(Commands.literal("start").executes(c->{start();return message("Measuring; close chat and keep scene consistent.");}))
                .then(Commands.literal("report").executes(c->report()))
                .then(Commands.literal("stop").executes(c->{stop();return report();}))));
    }
}
