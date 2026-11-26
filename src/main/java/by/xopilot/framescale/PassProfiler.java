package by.xopilot.framescale;

import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/** Bounded, opt-in asynchronous GL timestamp capture. No GL state or render target changes. */
public final class PassProfiler {
    private static final int SLOTS=8, MARKS=PassMetrics.MARKS;
    private static final long SECOND=1_000_000_000L, TIMEOUT=120*SECOND;
    private static PassProfiler active;
    private final int[][] ids=new int[SLOTS][MARKS];
    private final boolean[] pending=new boolean[SLOTS];
    private final PassSequence sequence=new PassSequence();
    private final Consumer<String> message;
    private PassMetrics metrics=new PassMetrics(240);
    private Object world;
    private int width,height,slot=-1,submitted,dropped,incomplete,target;
    private long started,readyAt;
    private boolean running;
    private String state="Idle";
    PassProfiler(Consumer<String> message) { this.message=message; }
    boolean running() { return running; }
    static boolean hookShapeAccepted() { return by.xopilot.framescale.mixin.IrisProbePlugin.accepted; }
    String start(int frames) {
        if(running) return "Pass capture already running";
        var mc=Minecraft.getInstance();
        if(mc.level==null) return "Enter a world first";
        if(!hookShapeAccepted()) return "Iris pipeline hooks unavailable for this build";
        if(!CompatPolicy.evaluate(CompatInventory.loaded()).gpuTimer()) return "GPU queries blocked by compatibility policy";
        try {
            if(!GL.getCapabilities().OpenGL33) return "GL timestamps unavailable";
            Class<?> iris=Class.forName("net.irisshaders.iris.Iris");
            Object pack=iris.getMethod("getCurrentPack").invoke(null);
            if(!(pack instanceof Optional<?> opt)||opt.isEmpty()) return "Enable an Iris shaderpack first";
            for(int i=0;i<SLOTS;i++) for(int j=0;j<MARKS;j++) ids[i][j]=GL15.glGenQueries();
            metrics=new PassMetrics(frames);target=frames;submitted=dropped=incomplete=0;
            world=mc.level;width=mc.getWindow().getWidth();height=mc.getWindow().getHeight();
            started=System.nanoTime();readyAt=started+2*SECOND;
            running=true;active=this;state="Capturing";
            return "Iris pass capture: "+frames+" frames. Close chat; remain in the same loaded scene.";
        } catch(ReflectiveOperationException|RuntimeException|LinkageError e) {
            finish("Unavailable: "+detail(e),false);return state;
        }
    }
    void pre() {
        if(!running) return;
        try {
            var mc=Minecraft.getInstance();
            long now=System.nanoTime();
            if(mc.level!=world||mc.getWindow().getWidth()!=width||mc.getWindow().getHeight()!=height) {
                finish("World or framebuffer changed",true);return;
            }
            poll();
            if(metrics.full()) { finish("Completed",true);return; }
            if(now-started>=TIMEOUT) { finish("Timeout",true);return; }
            if(mc.screen!=null||!mc.isWindowActive()) { readyAt=now+2*SECOND;return; }
            if(now<readyAt||submitted>=target) return;
            slot=-1;
            for(int i=0;i<SLOTS;i++) if(!pending[i]) { slot=i;break; }
            if(slot<0) { dropped++;return; }
            sequence.reset();
        } catch(RuntimeException|LinkageError e) { fail(e); }
    }
    /** Called by optional Iris hooks. No work or allocation when capture is disabled. */
    public static void mark(int index) {
        var p=active;
        if(p==null||p.slot<0) return;
        try {
            if(p.sequence.accept(index)) GL33.glQueryCounter(p.ids[p.slot][index],GL33.GL_TIMESTAMP);
        } catch(RuntimeException|LinkageError e) { p.fail(e); }
    }
    void post() {
        if(!running||slot<0) return;
        // The next use of an incomplete slot must wait for its already submitted GPU commands.
        if(sequence.complete()) { pending[slot]=true; submitted++; }
        else {
            incomplete++;
            try { GL33.glQueryCounter(ids[slot][MARKS-1],GL33.GL_TIMESTAMP); pending[slot]=true; invalid[slot]=true; }
            catch(RuntimeException|LinkageError e) { fail(e); }
        }
        slot=-1;
        if(incomplete>=30&&metrics.count()==0) finish("Iris marker sequence incomplete; unsupported render path",true);
    }
    private final boolean[] invalid=new boolean[SLOTS];
    private void poll() {
        for(int i=0;i<SLOTS;i++) {
            if(!pending[i]||GL15.glGetQueryObjecti(ids[i][MARKS-1],GL15.GL_QUERY_RESULT_AVAILABLE)==0) continue;
            if(invalid[i]) { pending[i]=invalid[i]=false;continue; }
            boolean ready=true;
            for(int j=0;j<MARKS;j++) if(GL15.glGetQueryObjecti(ids[i][j],GL15.GL_QUERY_RESULT_AVAILABLE)==0) {ready=false;break;}
            if(!ready) continue;
            long[] marks=new long[MARKS];
            for(int j=0;j<MARKS;j++) marks[j]=GL33.glGetQueryObjectui64(ids[i][j],GL15.GL_QUERY_RESULT);
            if(!metrics.add(marks)) { submitted--;incomplete++; }
            pending[i]=false;
        }
    }
    void stop() { if(running) finish("Stopped",false); }
    private void fail(Throwable e) {
        LoggerFactory.getLogger("FrameScale").warn("Iris pass capture stopped",e);
        finish("GPU capture failed: "+detail(e),true);
    }
    private void finish(String reason,boolean announce) {
        running=false;if(active==this) active=null;slot=-1;state=reason;
        for(int i=0;i<SLOTS;i++) {
            for(int j=0;j<MARKS;j++) if(ids[i][j]!=0) {
                try { GL15.glDeleteQueries(ids[i][j]); } catch(RuntimeException|LinkageError ignored) {}
                ids[i][j]=0;
            }
            pending[i]=invalid[i]=false;
        }
        if(announce) message.accept("Pass capture: "+state+"; "+metrics.count()+" frames. /framescale passes report");
    }
    String header() { return state+" | "+metrics.count()+"/"+target+" frames | ring drops "+dropped+" | invalid "+incomplete
        +" | framebuffer "+width+"x"+height; }
    String report() {
        var b=new StringBuilder(header());
        for(var row:metrics.rows()) b.append(String.format(Locale.ROOT,"\n%s: %.2f ms mean, %.2f p95%s",
            row.stage(),row.mean(),row.p95(),row.share()>0?String.format(Locale.ROOT," (%.1f%%)",row.share()):""));
        return b.toString();
    }
    Path export(Path gameDir) throws IOException {
        if(metrics.count()==0) throw new IOException("No completed samples");
        var b=new StringBuilder("stage,frames,mean_ms,median_ms,p95_ms,share_percent\n");
        for(var row:metrics.rows()) b.append(String.format(Locale.ROOT,"%s,%d,%.6f,%.6f,%.6f,%.3f\n",
            row.stage(),metrics.count(),row.mean(),row.median(),row.p95(),row.share()));
        Path file=Files.createTempFile(gameDir,"framescale-passes-",".csv");
        Files.writeString(file,b);return file.getFileName();
    }
    private static String detail(Throwable e) { return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage(); }
}
