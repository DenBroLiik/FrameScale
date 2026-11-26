package by.xopilot.framescale;

import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;

/** Non-overlapping timestamp intervals; every reported stage uses the same complete frames. */
final class PassMetrics {
    static final List<String> STAGES=List.of("Setup / clear / begin","Pre-shadow gap","Shadows","Prepare",
        "Sky / opaque geometry","Hand / depth copy","Deferred / depth copy",
        "Translucent / hand","Composite","Final");
    static final int MARKS=STAGES.size()+1;
    private final long[][] samples;
    private int count;
    PassMetrics(int capacity) {
        if(capacity<1||capacity>1200) throw new IllegalArgumentException("capacity");
        samples=new long[STAGES.size()+1][capacity];
    }
    int count() { return count; }
    boolean full() { return count==samples[0].length; }
    boolean add(long[] marks) {
        if(full()||marks.length!=MARKS) return false;
        long total=0;
        for(int i=0;i<STAGES.size();i++) {
            long delta=marks[i+1]-marks[i];
            if(delta<0||delta>60_000_000_000L) return false;
            total+=delta;
        }
        if(total<=0||total>60_000_000_000L) return false;
        for(int i=0;i<STAGES.size();i++) samples[i][count]=marks[i+1]-marks[i];
        samples[STAGES.size()][count]=total;
        count++;
        return true;
    }
    record Row(String stage,double mean,double median,double p95,double share) {}
    private Row row(int index,String name,double total) {
        long[] sorted=Arrays.copyOf(samples[index],count);
        Arrays.sort(sorted);
        double sum=0;
        for(long ns:sorted) sum+=ns;
        double mean=sum/count/1e6;
        return new Row(name,mean,sorted[(count-1)/2]/1e6,
            sorted[(int)Math.ceil(count*0.95)-1]/1e6,total>0?100*mean/total:0);
    }
    List<Row> rows() {
        if(count==0) return List.of();
        var total=row(STAGES.size(),"Iris measured scope",0);
        var out=new ArrayList<Row>();
        for(int i=0;i<STAGES.size();i++) out.add(row(i,STAGES.get(i),total.mean()));
        out.sort(Comparator.comparingDouble(Row::mean).reversed());
        out.add(total);
        return List.copyOf(out);
    }
}
