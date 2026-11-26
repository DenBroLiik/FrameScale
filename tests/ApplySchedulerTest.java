package by.xopilot.framescale;
public class ApplySchedulerTest {
    static void check(boolean b) { if(!b) throw new AssertionError(); }
    public static void main(String[] args) {
        var s=new ApplyScheduler();
        s.beginDrag(); s.changed();
        for(int i=0;i<100;i++) check(!s.ready());
        s.clearPending(); s.changed();
        for(int i=0;i<100;i++) check(!s.ready());
        s.endDrag(); check(s.ready()); check(!s.ready());
        s.changed(); for(int i=0;i<5;i++) check(!s.ready());
        s.changed(); for(int i=0;i<5;i++) check(!s.ready());
        check(s.ready()); check(!s.ready());
        s.changed(); s.cancel(); check(!s.ready());
        System.out.println("ApplyScheduler tests PASS");
    }
}
