package by.xopilot.framescale;

import java.util.List;

/** A quality-first governor: no reaction to single slow frames or CPU-only pressure. */
final class AutoPolicy {
    enum Action { HOLD, LOWER, RAISE }
    record Window(int count, double median, double p90, double spikes) {
        boolean stable() {
            return count>=120 && Double.isFinite(median) && median>0
                && Double.isFinite(p90) && p90>=median && p90<=median*1.55 && spikes<=0.06;
        }
    }
    private int bad,good;
    void reset() { bad=good=0; }
    Action observe(Window frame,Window gpu,int target) {
        if(!frame.stable()||!gpu.stable()||gpu.count()<frame.count()*0.6) { reset(); return Action.HOLD; }
        double budget=1000.0/target;
        // GPU timestamps are a heuristic, not proof of saturation.
        boolean slow=frame.median()>budget*1.08 && gpu.median()>budget*1.05
            && gpu.median()>=frame.median()*0.78;
        boolean headroom=frame.median()<budget*0.76 && gpu.median()<budget*0.76 && frame.p90()<budget*0.92;
        bad=slow?bad+1:0; good=headroom?good+1:0;
        if(bad>=3) { reset(); return Action.LOWER; }
        if(good>=4) { reset(); return Action.RAISE; }
        return Action.HOLD;
    }
    static int next(List<Integer> allowed,int current,int floor,Action action) {
        if(action==Action.HOLD) return current;
        if(action==Action.LOWER) {
            if(current<=floor) return current;
            int goal=Math.max(floor,current-5);
            return allowed.stream().filter(v->v>=goal&&v<current).min(Integer::compare).orElse(current);
        }
        int goal=Math.min(100,current+5);
        return allowed.stream().filter(v->v>current&&v<=goal).max(Integer::compare).orElse(current);
    }
    static boolean improved(Window beforeFrame,Window beforeGpu,Window frame,Window gpu) {
        return frame.median()<=beforeFrame.median()*0.97 && gpu.median()<=beforeGpu.median()*0.95;
    }
}
