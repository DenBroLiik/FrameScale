package by.xopilot.framescale;

import net.minecraft.client.Minecraft;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Opt-in slow adaptation for Photon compile-time scale. All GL work stays on the render/client thread. */
final class AutoQuality {
    static final int DEFAULT_TARGET=80, DEFAULT_FLOOR=75;
    private static final long SECOND=1_000_000_000L, WINDOW=6*SECOND, WARMUP=10*SECOND,
        COOLDOWN=60*SECOND, CHUNK_HOLD=6*SECOND;
    private static final int MAX_CHANGES=6;
    private final AutoPolicy policy=new AutoPolicy();
    private final FrameStats frames=new FrameStats(8192),gpu=new FrameStats(8192);
    private final GpuTimer timer=new GpuTimer();
    private final AtomicInteger chunks=new AtomicInteger();
    private final Consumer<String> message;
    private boolean enabled,collecting,queryOpen,lowerBlocked;
    private int target=DEFAULT_TARGET,floor=DEFAULT_FLOOR,changes;
    private long start,last,readyAt,nextChange,chunkWindow,chunkHold;
    private Object level;
    private Resolution output;
    private PhotonBridge.Settings owned;
    private Trial trial;
    private String status="Off";
    private record Trial(PhotonBridge.Settings previous,AutoPolicy.Window frame,AutoPolicy.Window gpu,
                         double x,double y,double z,int windows) {
        Trial waited() { return new Trial(previous,frame,gpu,x,y,z,windows+1); }
    }
    AutoQuality(Consumer<String> message) { this.message=message; }
    boolean enabled() { return enabled; }
    int target() { return target; }
    int floor() { return floor; }
    String status() { return (enabled?"Auto":"Off")+" | "+target+" FPS | floor "+floor+"% | "+status; }
    void target(int value) {
        if(value<30||value>240) throw new IllegalArgumentException("target");
        if(target!=value) { target=value; restartObservation(); }
    }
    void floor(int value) {
        if(value<50||value>100) throw new IllegalArgumentException("floor");
        if(floor!=value) { floor=value; restartObservation(); }
    }
    void chunkLoaded() { if(enabled) chunks.updateAndGet(v->Math.min(1000,v+1)); }
    boolean enable() {
        if(enabled) return true;
        try {
            var d=CompatPolicy.evaluate(CompatInventory.loaded());
            if(!d.scale()||!d.gpuTimer()) throw new IllegalStateException(d.reason());
            owned=PhotonBridge.read();
            enabled=true; changes=0; lowerBlocked=false; level=null; output=null;
            trial=null; nextChange=0; chunks.set(0); chunkWindow=0; chunkHold=0;
            restartObservation(); status="Waiting for stable gameplay";
            return true;
        } catch(ReflectiveOperationException|RuntimeException|LinkageError e) {
            status="Unavailable: "+detail(e); message.accept(status); return false;
        }
    }
    void disable(String reason) {
        enabled=false; trial=null; level=null; owned=null; suspend(); status=reason;
    }
    private void restartObservation() {
        suspend(); trial=null; readyAt=System.nanoTime()+WARMUP;
    }
    private void suspend() {
        if(collecting||queryOpen) {
            try { timer.close(); } catch(RuntimeException|LinkageError e) { LoggerFactory.getLogger("FrameScale").debug("GPU timer cleanup",e); }
        }
        collecting=queryOpen=false; start=last=0; frames.clear(); gpu.clear(); policy.reset();
    }
    private boolean gameplay(Minecraft mc) {
        return mc.level!=null && mc.player!=null && mc.screen==null && mc.isWindowActive()
            && mc.getWindow().getWidth()>0 && mc.getWindow().getHeight()>0;
    }
    void pre() {
        if(!enabled||!collecting) return;
        var mc=Minecraft.getInstance();
        if(!gameplay(mc)) { last=0; return; }
        long now=System.nanoTime();
        if(last!=0) frames.add((now-last)/1_000_000.0);
        last=now;
        try {
            queryOpen=timer.begin(gpu);
            if(!queryOpen) { disable("GPU timing unsupported"); message.accept(status); }
        } catch(RuntimeException|LinkageError e) {
            disable("GPU timing unavailable"); message.accept(status);
        }
    }
    void post() {
        if(!queryOpen) return;
        try { timer.end(); queryOpen=false; }
        catch(RuntimeException|LinkageError e) { disable("GPU timing failed"); message.accept(status); }
    }
    void tick() {
        if(!enabled) return;
        var mc=Minecraft.getInstance();
        long now=System.nanoTime();
        if(!gameplay(mc)) {
            suspend(); readyAt=now+WARMUP; status="Paused: menu, focus or world"; return;
        }
        var dimensions=new Resolution(mc.getWindow().getWidth(),mc.getWindow().getHeight());
        if(level!=mc.level||!dimensions.equals(output)) {
            level=mc.level; output=dimensions; trial=null;
            suspend(); readyAt=now+WARMUP; chunks.set(0); chunkWindow=now; chunkHold=0;
        }
        if(chunkWindow==0) chunkWindow=now;
        if(now-chunkWindow>=SECOND) {
            if(chunks.getAndSet(0)>=4) {
                chunkHold=now+CHUNK_HOLD; suspend(); trial=null;
            }
            chunkWindow=now;
        }
        if(now<chunkHold||chunks.get()>=4) { suspend(); status="Waiting for chunk loading"; return; }
        if(now<readyAt) { status="Warmup"; return; }
        if(changes>=MAX_CHANGES && trial==null) { suspend(); status="Reload limit reached"; return; }
        if(!collecting) {
            frames.clear(); gpu.clear(); start=now; last=0; collecting=true; status="Measuring"; return;
        }
        if(now-start<WINDOW) return;
        var fw=frames.robust(); var gw=gpu.robust();
        frames.clear(); gpu.clear(); start=now; last=0;
        try {
            if(!CompatPolicy.evaluate(CompatInventory.loaded()).scale() || !PhotonBridge.read().equals(owned)) {
                disable("Shader settings changed outside FrameScale"); message.accept(status); return;
            }
            if(!fw.stable()||!gw.stable()||gw.count()<fw.count()*0.6) {
                policy.reset(); status="Unstable frame times; quality held"; return;
            }
            if(trial!=null) {
                double dx=mc.player.getX()-trial.x(),dy=mc.player.getY()-trial.y(),dz=mc.player.getZ()-trial.z();
                if(dx*dx+dy*dy+dz*dz>64) { trial=null; status="Scene changed; comparison discarded"; policy.reset(); return; }
                trial=trial.waited();
                if(AutoPolicy.improved(trial.frame(),trial.gpu(),fw,gw)) {
                    trial=null; status="Lower scale verified"; return;
                }
                if(trial.windows()<2 || now<nextChange) return;
                // No meaningful benefit in the same area: restore previous TAA/TAAU/scale.
                if(changes>=MAX_CHANGES) { trial=null; lowerBlocked=true; status="Reload limit reached"; return; }
                var previous=trial.previous(); trial=null; lowerBlocked=true;
                suspend(); owned=PhotonBridge.restore(owned,previous); changed(now);
                message.accept("Auto: restored previous quality; lower scale gave no measured benefit.");
                return;
            }
            if(now<nextChange) { policy.reset(); status="Reload cooldown"; return; }
            int current=owned.enabled()?owned.percent():100;
            var action=policy.observe(fw,gw,target);
            if(action==AutoPolicy.Action.HOLD || (action==AutoPolicy.Action.LOWER&&lowerBlocked)) {
                status="Holding "+current+"%"; return;
            }
            int next=AutoPolicy.next(owned.allowed(),current,floor,action);
            if(next==current) { status="Holding quality limit "+current+"%"; return; }
            // Reserve one reload for a possible rollback.
            if(action==AutoPolicy.Action.LOWER && changes>=MAX_CHANGES-1) { status="Reload budget reserved"; return; }
            var previous=owned;
            suspend();
            owned=PhotonBridge.apply(owned,true,next); changed(now);
            if(action==AutoPolicy.Action.LOWER) trial=new Trial(previous,fw,gw,mc.player.getX(),mc.player.getY(),mc.player.getZ(),0);
            message.accept("Auto: "+output.transition(next)+" | target "+target+" FPS");
        } catch(ReflectiveOperationException|RuntimeException|LinkageError e) {
            LoggerFactory.getLogger("FrameScale").warn("Auto quality stopped",e);
            disable("Stopped: "+detail(e)); message.accept(status);
        }
    }
    private void changed(long ignored) {
        changes++; long now=System.nanoTime(); readyAt=now+WARMUP; nextChange=now+COOLDOWN; status="Shader reload cooldown";
    }
    private static String detail(Throwable e) { return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage(); }
}
