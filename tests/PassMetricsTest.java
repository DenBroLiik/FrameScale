package by.xopilot.framescale;

public final class PassMetricsTest {
    static void check(boolean b) { if(!b) throw new AssertionError(); }
    public static void main(String[] args) {
        var seq=new PassSequence();seq.reset();
        for(int i=0;i<PassMetrics.MARKS;i++) check(seq.accept(i));
        check(seq.complete());check(!seq.accept(10));check(!seq.complete());
        seq.reset();check(seq.accept(0));check(!seq.accept(2));check(!seq.complete());
        var m=new PassMetrics(2);
        long[] t=new long[PassMetrics.MARKS];
        for(int i=1;i<t.length;i++) t[i]=t[i-1]+i*1_000_000L;
        check(m.add(t));check(m.count()==1);
        long[] bad=t.clone();bad[4]=-1;check(!m.add(bad));check(m.count()==1);
        check(m.add(t));check(m.full());check(!m.add(t));
        var rows=m.rows();check(rows.getFirst().stage().equals("Final"));
        check(rows.getFirst().mean()==10);
        check(rows.getLast().mean()==55);
        check(Math.abs(rows.stream().mapToDouble(PassMetrics.Row::share).sum()-100)<1e-8);
        var zero=new PassMetrics(1);check(!zero.add(new long[PassMetrics.MARKS]));
        System.out.println("PassMetrics tests PASS");
    }
}
