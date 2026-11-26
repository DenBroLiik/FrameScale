package by.xopilot.framescale;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import java.util.Locale;
import java.nio.file.Path;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.LoggerFactory;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.event.level.ChunkEvent;

@Mod(value="framescale", dist=Dist.CLIENT)
public final class FrameScale {
    private static FrameScale instance;
    private final Path prefsPath=FMLPaths.CONFIGDIR.get().resolve("framescale.properties");
    private final SodiumAutoApply sodiumAutoApply=new SodiumAutoApply();
    private final AutoQuality auto=new AutoQuality(s->message(s));
    private final PassProfiler passes=new PassProfiler(s->message(s));
    private final FrameStats frames=new FrameStats(1800), gpu=new FrameStats(1800);
    private final GpuTimer timer=new GpuTimer();
    private boolean measuring, gpuAvailable, gpuFailed, gpuPermitted;
    private long lastFrame;
    public FrameScale(ModContainer container) {
        instance=this;
        try { var prefs=AutoPreferences.load(prefsPath); auto.target(prefs.target()); auto.floor(prefs.floor()); }
        catch(java.io.IOException|RuntimeException e) { LoggerFactory.getLogger("FrameScale").warn("Cannot read FrameScale preferences; defaults used",e); }
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> settings(parent));
        NeoForge.EVENT_BUS.addListener(this::screenInit);
        NeoForge.EVENT_BUS.addListener(this::screenInitPre);
        NeoForge.EVENT_BUS.addListener(this::clientTick);
        NeoForge.EVENT_BUS.addListener(this::chunkLoad);
        NeoForge.EVENT_BUS.addListener(this::commands);
        NeoForge.EVENT_BUS.addListener(this::pre);
        NeoForge.EVENT_BUS.addListener(this::post);
    }
    private Screen settings(Screen parent) { return createSettings(parent); }
    static Screen createSettings(Screen parent) { return new FrameScaleScreen(parent, FrameScale::stopActiveProfiler); }
    static void stopActiveProfiler() { if(instance!=null) { instance.stop(); instance.passes.stop(); } }
    static boolean autoEnabled() { return instance!=null&&instance.auto.enabled(); }
    static boolean enableAuto() { if(instance==null) return false; instance.stop(); instance.passes.stop(); return instance.auto.enable(); }
    static void disableAuto() { if(instance!=null&&instance.auto.enabled()) instance.auto.disable("Manual mode"); }
    static int autoTarget() { return instance==null?AutoQuality.DEFAULT_TARGET:instance.auto.target(); }
    static int autoFloor() { return instance==null?AutoQuality.DEFAULT_FLOOR:instance.auto.floor(); }
    static void autoTarget(int value) { if(instance!=null&&instance.auto.target()!=value) { instance.auto.target(value); instance.savePreferences(); } }
    static void autoFloor(int value) { if(instance!=null&&instance.auto.floor()!=value) { instance.auto.floor(value); instance.savePreferences(); } }
    private void savePreferences() {
        try { new AutoPreferences(auto.target(),auto.floor()).save(prefsPath); }
        catch(java.io.IOException|RuntimeException e) { LoggerFactory.getLogger("FrameScale").warn("Cannot save FrameScale preferences",e); message("Could not save Auto preferences; check log"); }
    }
    static String autoStatus() { return instance==null?"Off":instance.auto.status(); }
    private void chunkLoad(ChunkEvent.Load event) { if(event.getLevel().isClientSide()) auto.chunkLoaded(); }
    private void screenInitPre(ScreenEvent.Init.Pre event) { sodiumAutoApply.init(event.getScreen()); }
    private void clientTick(ClientTickEvent.Post event) { sodiumAutoApply.tick(); auto.tick(); SpatialWorld.tick(); PhotonCapture.tick(); }
    private void screenInit(ScreenEvent.Init.Post event) { VideoSettingsEntry.add(event, this::settings); }
    private void pre(RenderFrameEvent.Pre ignored) {
        passes.pre();
        auto.pre();
        if (!measuring || Minecraft.getInstance().level==null) { lastFrame=0; return; }
        long now=System.nanoTime();
        if(lastFrame!=0) frames.add((now-lastFrame)/1_000_000.0);
        lastFrame=now;
        if (!gpuFailed && gpuPermitted) {
            try { gpuAvailable=timer.begin(gpu); }
            catch (RuntimeException | LinkageError e) { gpuFailed=true; gpuAvailable=false; }
        }
    }
    private void post(RenderFrameEvent.Post ignored) { passes.post(); auto.post(); if(measuring && !gpuFailed && gpuPermitted) {
            try { timer.end(); }
            catch (RuntimeException | LinkageError e) { gpuFailed=true; gpuAvailable=false; }
        } }
    private void stop() { measuring=false; lastFrame=0; try { timer.close(); } catch (RuntimeException | LinkageError e) { gpuAvailable=false; } }
    private void start() { disableAuto(); passes.stop(); stop(); frames.clear(); gpu.clear(); gpuAvailable=false; gpuFailed=false; gpuPermitted=CompatPolicy.evaluate(CompatInventory.loaded()).gpuTimer(); measuring=true; }
    private int message(String s) {
        var gui=Minecraft.getInstance().gui;
        if(gui!=null) gui.getChat().addMessage(Component.literal("[FrameScale] "+s)); return 1;
    }
    private int report() {
        var f=frames.snapshot(); var g=gpu.snapshot();
        if(f.count()==0) return message("No frame samples. /framescale bench start");
        return message(String.format(Locale.ROOT,"%d frames | %.1f FPS | 1%% low %.1f | p95 %.2f ms | GPU %s",f.count(),f.fps(),f.low1Fps(),f.p95Ms(),gpuAvailable&&g.count()>0?String.format(Locale.ROOT,"%.2f ms mean / %.2f ms p95 (%d samples)",g.meanMs(),g.p95Ms(),g.count()):"unavailable"));
    }
    private int status() { try { return message(PhotonBridge.status()); } catch(ReflectiveOperationException|RuntimeException|LinkageError e){ return message("Unsupported Iris/Photon: "+e); } }
    private int scale(int n) {
        disableAuto(); passes.stop();
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
    private int startPasses(int count) {
        if(passes.running()) return message("Pass capture already running");
        disableAuto(); stop();
        return message(passes.start(count));
    }
    private int exportPasses() {
        try { return message("Saved: "+passes.export(Minecraft.getInstance().gameDirectory.toPath())); }
        catch(java.io.IOException|RuntimeException e) { return message("Export failed: "+e.getMessage()); }
    }
    private int effectCommand(String name,boolean preview) {
        try {
            var preset=EffectControls.parse(name);
            return message(preview?EffectControls.preview(preset):EffectControls.apply(preset));
        } catch(ReflectiveOperationException|RuntimeException|LinkageError e) {
            return message("Effects unavailable: "+e.getMessage());
        }
    }
    private int spatialTest() {
        if(!CompatPolicy.evaluate(CompatInventory.loaded()).gpuTimer()) return message("Spatial test blocked by renderer compatibility policy");
        if(auto.enabled()||passes.running()||measuring||SpatialWorld.load()!=100) return message("Stop Auto and profilers before spatial test");
        try { return message(SpatialSelfTest.run()); }
        catch(java.io.IOException|RuntimeException|LinkageError e) {
            LoggerFactory.getLogger("FrameScale").warn("Spatial self-test failed",e);
            return message("Spatial self-test failed: "+e);
        }
    }
    private void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("framescale")
            .executes(c->status())
            .then(Commands.literal("status").executes(c->status()))
            .then(Commands.literal("compat").executes(c->message(CompatInventory.summary(CompatInventory.loaded())))
                .then(Commands.literal("export").executes(c->compatExport())))
            .then(Commands.literal("scale").then(Commands.argument("percent",IntegerArgumentType.integer(50,100)).executes(c->scale(IntegerArgumentType.getInteger(c,"percent")))))
            .then(Commands.literal("gpu").executes(c->{try { return message(GpuDiagnostics.report()); } catch(RuntimeException|LinkageError e) { return message("GPU diagnostics unavailable: "+e); }}))
            .then(Commands.literal("native")
                .executes(c->message(NativeInteropDiagnostic.status()))
                .then(Commands.literal("probe").executes(c->message(NativeInteropDiagnostic.start(s->message(s))))))
            .then(Commands.literal("capture")
                .executes(c->message(PhotonCapture.report()))
                .then(Commands.literal("start").executes(c->message(PhotonCapture.start(s->message(s)))))
                .then(Commands.literal("report").executes(c->message(PhotonCapture.report())))
                .then(Commands.literal("uniforms").executes(c->message(PhotonCapture.uniformReport())))
                .then(Commands.literal("gpu")
                    .executes(c->message(PhotonCapture.probeReport()))
                    .then(Commands.argument("width",IntegerArgumentType.integer(1,8192))
                        .then(Commands.argument("height",IntegerArgumentType.integer(1,8192))
                            .executes(c->message(PhotonCapture.probe(IntegerArgumentType.getInteger(c,"width"),IntegerArgumentType.getInteger(c,"height"),s->message(s)))))))
                .then(Commands.literal("series")
                    .then(Commands.argument("width",IntegerArgumentType.integer(1,8192))
                        .then(Commands.argument("height",IntegerArgumentType.integer(1,8192))
                            .then(Commands.argument("count",IntegerArgumentType.integer(1,120))
                                .executes(c->message(PhotonCapture.series(IntegerArgumentType.getInteger(c,"width"),IntegerArgumentType.getInteger(c,"height"),IntegerArgumentType.getInteger(c,"count"),s->message(s))))))))
                .then(Commands.literal("stop").executes(c->message(PhotonCapture.stop()))))
            .then(Commands.literal("upscale")
                .executes(c->message(SpatialWorld.status()))
                .then(Commands.literal("test").executes(c->spatialTest()))
                .then(Commands.literal("cost")
                    .then(Commands.literal("start").executes(c->message(SpatialWorld.costStart())))
                    .then(Commands.literal("report").executes(c->message(SpatialWorld.costReport())))
                    .then(Commands.literal("stop").executes(c->message(SpatialWorld.costStop()))))
                .then(Commands.literal("spatial").then(Commands.argument("percent",IntegerArgumentType.integer(50,100))
                    .executes(c->message(SpatialWorld.set(IntegerArgumentType.getInteger(c,"percent")))))))
            .then(Commands.literal("auto")
                .executes(c->message(auto.status()))
                .then(Commands.literal("on").executes(c->message(enableAuto()?auto.status():"Auto unavailable: "+auto.status())))
                .then(Commands.literal("off").executes(c->{disableAuto();return message(auto.status());}))
                .then(Commands.literal("target").then(Commands.argument("fps",IntegerArgumentType.integer(30,240)).executes(c->{autoTarget(IntegerArgumentType.getInteger(c,"fps"));return message(auto.status());})))
                .then(Commands.literal("floor").then(Commands.argument("percent",IntegerArgumentType.integer(50,100)).executes(c->{autoFloor(IntegerArgumentType.getInteger(c,"percent"));return message(auto.status());}))))
            .then(Commands.literal("effects")
                .executes(c->message(EffectControls.status()))
                .then(Commands.literal("preview").then(Commands.argument("preset",StringArgumentType.word())
                    .suggests((c,b)->{ for(var v:SamplingBudget.Preset.values()) b.suggest(v.name().toLowerCase(Locale.ROOT));return b.buildFuture(); })
                    .executes(c->effectCommand(StringArgumentType.getString(c,"preset"),true))))
                .then(Commands.literal("apply").then(Commands.argument("preset",StringArgumentType.word())
                    .suggests((c,b)->{ for(var v:SamplingBudget.Preset.values()) b.suggest(v.name().toLowerCase(Locale.ROOT));return b.buildFuture(); })
                    .executes(c->effectCommand(StringArgumentType.getString(c,"preset"),false))))
                .then(Commands.literal("restore").executes(c->effectCommand("original",false)))
                .then(Commands.literal("forget").executes(c->{EffectControls.forget();return message("Effect baseline forgotten; current shader values unchanged");})))
            .then(Commands.literal("passes")
                .then(Commands.literal("start").executes(c->startPasses(240))
                    .then(Commands.argument("frames",IntegerArgumentType.integer(60,1200)).executes(c->startPasses(IntegerArgumentType.getInteger(c,"frames")))))
                .then(Commands.literal("report").executes(c->message(passes.report())))
                .then(Commands.literal("stop").executes(c->{passes.stop();return message(passes.report());}))
                .then(Commands.literal("export").executes(c->exportPasses())))
            .then(Commands.literal("bench")
                .then(Commands.literal("start").executes(c->{start();return message("Measuring; close chat and keep scene consistent.");}))
                .then(Commands.literal("report").executes(c->report()))
                .then(Commands.literal("stop").executes(c->{stop();return report();}))));
    }
}
