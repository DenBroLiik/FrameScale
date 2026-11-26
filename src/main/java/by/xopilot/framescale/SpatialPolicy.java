package by.xopilot.framescale;

import java.util.Map;
import java.util.Set;

/** Initial world integration deliberately excludes unvalidated framebuffer owners. */
final class SpatialPolicy {
    private static final Set<String> BLOCKED=Set.of("iris","gpu_tape","gputape","distanthorizons","vulkanmod",
        "super_resolution","superresolution","renderscale","render_scale","oculus","canvas");
    static String blocked(Map<String,String> mods) {
        return mods.keySet().stream().filter(BLOCKED::contains).sorted().findFirst().orElse("");
    }
    static boolean validScale(int value) { return value>=50&&value<=100; }
    static boolean validOutput(int width,int height) {
        return width>0&&height>0&&width<=8192&&height<=8192&&(long)width*height<=16_777_216L;
    }
}
