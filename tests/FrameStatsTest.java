import by.xopilot.framescale.FrameStats;
public class FrameStatsTest {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        var stats=new FrameStats(100);
        check(stats.snapshot().count()==0);
        stats.add(Double.NaN); stats.add(-1); check(stats.snapshot().count()==0);
        for(int i=0;i<100;i++) stats.add(20);
        check(stats.snapshot().fps()==50 && stats.snapshot().p95Ms()==20);
        stats.add(40); check(stats.snapshot().count()==100 && stats.snapshot().low1Fps()==25);
        stats.clear(); check(stats.snapshot().count()==0);
        System.out.println("FrameStats tests PASS");
    }
}
