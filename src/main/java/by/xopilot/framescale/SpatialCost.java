package by.xopilot.framescale;

import java.util.Locale;

/** Opt-in, sparse GPU timing; retains the last result after resource release. */
final class SpatialCost {
    private final GpuTimer timer=new GpuTimer();
    private final FrameStats samples=new FrameStats(600);
    private boolean enabled;
    private int frames;
    private String label="No capture";
    void start(String label) {
        stop();samples.clear();frames=0;this.label=label;enabled=true;
    }
    boolean begin() {
        if(!enabled||++frames%8!=0) return false;
        try {
            if(timer.begin(samples)) return true;
        } catch(RuntimeException|LinkageError ignored) {}
        stop();return false;
    }
    void end() {
        try {timer.end();}
        catch(RuntimeException|LinkageError ignored) {stop();}
    }
    void stop() {
        enabled=false;
        try {timer.close();} catch(RuntimeException|LinkageError ignored) {}
    }
    String report() {
        var s=samples.snapshot();
        if(s.count()==0) return label+" | No completed GPU samples";
        return String.format(Locale.ROOT,"%s | %s | %d samples | %.3f ms mean | %.3f ms p95",
            label,enabled?"capturing":"stopped",s.count(),s.meanMs(),s.p95Ms());
    }
}
