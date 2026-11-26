package by.xopilot.framescale;
import java.util.List;
public final class AutoPolicyTest {
    static void check(boolean ok) { if(!ok) throw new AssertionError(); }
    static AutoPolicy.Window w(double m,double p,double spike) { return new AutoPolicy.Window(360,m,p,spike); }
    public static void main(String[] args) {
        var p=new AutoPolicy(); var slow=w(19,22,0); var busy=w(17,20,0); var cpu=w(8,10,0); var fast=w(8,10,0);
        for(int i=0;i<10;i++) check(p.observe(slow,cpu,80)==AutoPolicy.Action.HOLD);
        for(int i=0;i<10;i++) check(p.observe(w(19,80,.2),busy,80)==AutoPolicy.Action.HOLD);
        check(p.observe(slow,busy,80)==AutoPolicy.Action.HOLD);
        check(p.observe(slow,busy,80)==AutoPolicy.Action.HOLD);
        check(p.observe(slow,busy,80)==AutoPolicy.Action.LOWER);
        for(int i=0;i<3;i++) check(p.observe(fast,fast,80)==AutoPolicy.Action.HOLD);
        check(p.observe(fast,fast,80)==AutoPolicy.Action.RAISE);
        check(AutoPolicy.next(List.of(75,80,85,90,95,100),100,75,AutoPolicy.Action.LOWER)==95);
        check(AutoPolicy.next(List.of(75,80,85),75,75,AutoPolicy.Action.LOWER)==75);
        check(AutoPolicy.next(List.of(75,80,85),80,75,AutoPolicy.Action.RAISE)==85);
        check(!AutoPolicy.improved(slow,busy,w(19,21,0),w(16,18,0)));
        check(AutoPolicy.improved(slow,busy,w(17,19,0),w(15,18,0)));
        var stats=new FrameStats(300);
        for(int i=0;i<295;i++) stats.add(12);
        for(int i=0;i<5;i++) stats.add(150);
        check(stats.robust().stable()); check(stats.robust().median()==12);
        p.reset(); check(p.observe(stats.robust(),w(10,12,0),80)==AutoPolicy.Action.HOLD);
        System.out.println("AutoPolicy tests PASS");
    }
}
