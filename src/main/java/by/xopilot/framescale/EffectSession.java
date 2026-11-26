package by.xopilot.framescale;

import java.util.Map;

/** Owns only known effect options, never TAA, render scale or unrelated shader settings. */
final class EffectSession {
    record Snapshot(String pack,Map<String,SamplingBudget.Option> options) {
        Snapshot { options=Map.copyOf(options); }
        Map<String,String> values() { return SamplingBudget.plan(options,SamplingBudget.Preset.ORIGINAL); }
    }
    record Change(Snapshot expected,Map<String,String> desired,SamplingBudget.Preset preset) {}
    private Snapshot original,last;
    private SamplingBudget.Preset active=SamplingBudget.Preset.ORIGINAL;
    SamplingBudget.Preset active() { return active; }
    boolean owns(Snapshot current) { return last!=null&&last.equals(current); }
    void clear() { original=last=null;active=SamplingBudget.Preset.ORIGINAL; }
    Change preview(Snapshot current,SamplingBudget.Preset preset) {
        if(last!=null&&!owns(current)) throw new IllegalStateException("Photon effects changed outside FrameScale; use forget before a new preset");
        Snapshot base=original==null?current:original;
        return new Change(current,SamplingBudget.plan(base.options(),preset),preset);
    }
    void confirm(Change change,Snapshot actual) {
        if(!change.expected().pack().equals(actual.pack())||!change.desired().equals(actual.values()))
            throw new IllegalStateException("Iris did not confirm effect values");
        if(change.preset()==SamplingBudget.Preset.ORIGINAL) {clear();return;}
        if(original==null) original=change.expected();
        last=actual;
        active=change.preset();
    }
}
