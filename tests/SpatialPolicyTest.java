package by.xopilot.framescale;
import java.util.Map;
public final class SpatialPolicyTest {
    static void check(boolean ok) { if(!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        check(SpatialPolicy.blocked(Map.of("sodium","0.8.13")).isEmpty());
        check(SpatialPolicy.blocked(Map.of("iris","1")).equals("iris"));
        check(SpatialPolicy.blocked(Map.of("iris","1"),true).isEmpty());
        check(SpatialPolicy.blocked(Map.of("iris","1","gpu_tape","1"),true).equals("gpu_tape"));
        check(SpatialPolicy.blocked(Map.of("gpu_tape","1")).equals("gpu_tape"));
        check(SpatialPolicy.blocked(Map.of("super_resolution","1")).equals("super_resolution"));
        check(SpatialPolicy.validScale(50)&&SpatialPolicy.validScale(100)&&!SpatialPolicy.validScale(49));
        check(SpatialPolicy.validOutput(1920,1200)&&!SpatialPolicy.validOutput(0,1200));
        check(!SpatialPolicy.validOutput(8192,8192));
        System.out.println("SpatialPolicy tests PASS");
    }
}
