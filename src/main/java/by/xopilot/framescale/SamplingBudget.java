package by.xopilot.framescale;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;

/** Conservative, explicit effect budgets. Only exact pack-declared values may be chosen. */
final class SamplingBudget {
    enum Preset { ORIGINAL, SHADOWS, CLOUDS, REFLECTIONS, VOLUMETRICS, BALANCED }
    record Limit(String key, Preset group, int percent, int minimum) {}
    record Option(String value,List<String> allowed) {
        Option { allowed=List.copyOf(allowed); }
    }
    private static final List<Limit> LIMITS=List.of(
        new Limit("shadowMapResolution",Preset.SHADOWS,75,1536),
        new Limit("CLOUDS_CUMULUS_PRIMARY_STEPS_H",Preset.CLOUDS,80,16),
        new Limit("CLOUDS_CUMULUS_PRIMARY_STEPS_Z",Preset.CLOUDS,80,8),
        new Limit("CLOUDS_CUMULUS_LIGHTING_STEPS",Preset.CLOUDS,85,4),
        new Limit("SSR_RAY_COUNT",Preset.REFLECTIONS,75,2),
        new Limit("SSR_INTERSECTION_STEPS_SMOOTH",Preset.REFLECTIONS,80,8),
        new Limit("SSR_INTERSECTION_STEPS_ROUGH",Preset.REFLECTIONS,80,6),
        new Limit("CREPUSCULAR_RAYS_STEPS_HORIZON",Preset.VOLUMETRICS,80,12),
        new Limit("CREPUSCULAR_RAYS_STEPS_ZENITH",Preset.VOLUMETRICS,80,4),
        new Limit("LPV_VL_STEPS",Preset.VOLUMETRICS,80,12));
    static List<String> keys() { return LIMITS.stream().map(Limit::key).toList(); }
    static Map<String,String> plan(Map<String,Option> options,Preset preset) {
        var result=new TreeMap<String,String>();
        for(var limit:LIMITS) {
            var option=options.get(limit.key());
            if(option==null) continue;
            String value=option.value();
            if(preset==limit.group()||preset==Preset.BALANCED&&limit.group()!=Preset.SHADOWS)
                value=reduce(option,limit.percent(),limit.minimum());
            result.put(limit.key(),value);
        }
        return java.util.Collections.unmodifiableMap(result);
    }
    static String reduce(Option option,int percent,int minimum) {
        int current;
        try { current=Integer.parseInt(option.value()); } catch(NumberFormatException e) { return option.value(); }
        if(current<=minimum||!option.allowed().contains(option.value())) return option.value();
        // Ceil avoids exceeding the intended sample reduction; floors prevent aggressively low quality.
        long goal=Math.max(minimum,((long)current*percent+99)/100);
        int best=current;String selected=option.value();
        for(String v:option.allowed()) {
            try {
                int n=Integer.parseInt(v);
                if(n>=goal&&n<best) {best=n;selected=v;}
            } catch(NumberFormatException ignored) {}
        }
        return selected;
    }
    static Map<String,String> changes(Map<String,String> current,Map<String,String> desired) {
        if(!current.keySet().equals(desired.keySet())) throw new IllegalArgumentException("Option shape changed");
        var diff=new TreeMap<String,String>();
        desired.forEach((k,v)->{if(!v.equals(current.get(k))) diff.put(k,v);});
        return java.util.Collections.unmodifiableMap(diff);
    }
}
