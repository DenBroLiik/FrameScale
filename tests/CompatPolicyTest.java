package by.xopilot.framescale;
import java.util.Map;
public class CompatPolicyTest {
    public static void main(String[] args) {
        check(!CompatPolicy.evaluate(Map.of()).scale());
        check(CompatPolicy.evaluate(Map.of("iris","1.8.14-beta.1","gpu_tape","1.0.3","sodium","0.8.13")).scale());
        for(String id : new String[]{"super_resolution","superresolution","renderscale","render_scale","vulkanmod"}) {
            var d=CompatPolicy.evaluate(Map.of("iris","1",id,"1")); check(!d.scale()&&!d.gpuTimer());
        }
        System.out.println("CompatPolicy: PASS");
    }
    private static void check(boolean result) { if(!result) throw new AssertionError(); }
}
