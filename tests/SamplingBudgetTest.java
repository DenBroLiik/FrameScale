package by.xopilot.framescale;

import java.util.List;
import java.util.Map;
public final class SamplingBudgetTest {
    static void check(boolean b) { if(!b) throw new AssertionError(); }
    static SamplingBudget.Option o(String v,String... allowed) {return new SamplingBudget.Option(v,List.of(allowed));}
    public static void main(String[] args) {
        var values=Map.of("shadowMapResolution",o("2048","1024","1536","2048","3072"),
            "SSR_RAY_COUNT",o("4","1","2","3","4","5"),
            "CLOUDS_CUMULUS_PRIMARY_STEPS_H",o("40","16","32","40"),
            "CREPUSCULAR_RAYS_STEPS_ZENITH",o("4","1","2","3","4"),
            "TAAU_RENDER_SCALE",o("0.75","0.75","1.00"));
        var shadow=SamplingBudget.plan(values,SamplingBudget.Preset.SHADOWS);
        check(shadow.get("shadowMapResolution").equals("1536"));check(shadow.get("SSR_RAY_COUNT").equals("4"));
        var balanced=SamplingBudget.plan(values,SamplingBudget.Preset.BALANCED);
        check(balanced.get("shadowMapResolution").equals("2048"));check(balanced.get("SSR_RAY_COUNT").equals("3"));
        check(balanced.get("CLOUDS_CUMULUS_PRIMARY_STEPS_H").equals("32"));
        check(balanced.get("CREPUSCULAR_RAYS_STEPS_ZENITH").equals("4"));
        check(!balanced.containsKey("TAAU_RENDER_SCALE"));
        check(SamplingBudget.reduce(o("1536","1024","1536","2048"),75,1536).equals("1536"));
        check(SamplingBudget.reduce(o("40","16","40"),80,16).equals("40"));
        check(SamplingBudget.reduce(o("bad","1","2","bad"),80,1).equals("bad"));
        var clean=new java.util.TreeMap<>(values);clean.remove("TAAU_RENDER_SCALE");
        var session=new EffectSession();var base=new EffectSession.Snapshot("Photon",clean);
        var change=session.preview(base,SamplingBudget.Preset.BALANCED);
        var applied=new java.util.TreeMap<String,SamplingBudget.Option>();
        clean.forEach((k,v)->applied.put(k,new SamplingBudget.Option(change.desired().get(k),v.allowed())));
        var next=new EffectSession.Snapshot("Photon",applied);session.confirm(change,next);
        check(session.preview(next,SamplingBudget.Preset.ORIGINAL).desired().equals(base.values()));
        check(session.preview(next,SamplingBudget.Preset.SHADOWS).desired().equals(shadow));
        try {session.preview(base,SamplingBudget.Preset.ORIGINAL);throw new AssertionError();} catch(IllegalStateException expected){}
        session.confirm(session.preview(next,SamplingBudget.Preset.ORIGINAL),base);
        check(session.active()==SamplingBudget.Preset.ORIGINAL);
        System.out.println("SamplingBudget tests PASS");
    }
}
