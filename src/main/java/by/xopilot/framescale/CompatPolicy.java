package by.xopilot.framescale;

import java.util.Map;
import java.util.Set;

/** Only FrameScale features are gated; never disable another mod. */
final class CompatPolicy {
    private static final Set<String> EXTERNAL = Set.of("super_resolution", "superresolution", "renderscale", "render_scale", "vulkanmod");
    record Decision(boolean scale, boolean gpuTimer, String reason) {}
    static Decision evaluate(Map<String,String> mods) {
        var competing = mods.keySet().stream().filter(EXTERNAL::contains).sorted().toList();
        if (!competing.isEmpty()) return new Decision(false, false, "External renderer/upscaler: " + competing + "; CPU frame intervals only");
        if (!mods.containsKey("iris")) return new Decision(false, true, "Iris absent; profiling only");
        return new Decision(true, true, "Photon adapter eligible; runtime API and shader options still require probing");
    }
}
