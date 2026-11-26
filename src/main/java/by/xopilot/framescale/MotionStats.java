package by.xopilot.framescale;

import java.util.Arrays;
import java.util.Locale;

/** Bounded CPU summary of the one-shot GPU motion-vector readback. */
final class MotionStats {
    record Summary(int width,int height,int total,int valid,int hand,
            double meanX,double meanY,double p95,double max) {
        String format(){
            if(valid==0)return String.format(Locale.ROOT,
                "motion center %dx%d: valid 0/%d, hand %d",width,height,total,hand);
            return String.format(Locale.ROOT,
                "motion center %dx%d: valid %d/%d (%.1f%%), hand %d, mean=(%+.3f,%+.3f) px, p95 %.3f px, max %.3f px",
                width,height,valid,total,100.0*valid/total,hand,meanX,meanY,p95,max);
        }
    }

    private MotionStats() {}

    static Summary summarize(int width,int height,float[] vectors,byte[] validity){
        if(width<1||height<1||(long)width*height>128L*128L)
            throw new IllegalArgumentException("Invalid sample extent");
        int total=width*height;
        if(vectors==null||vectors.length!=total*2||validity==null||validity.length!=total)
            throw new IllegalArgumentException("Readback size mismatch");
        double sumX=0,sumY=0,max=0;
        int valid=0,hand=0;
        double[] magnitudes=new double[total];
        for(int i=0;i<total;i++){
            int mask=validity[i]&0xff;
            if((mask&3)==3)hand++;
            float x=vectors[i*2],y=vectors[i*2+1];
            if((mask&1)==0||!Float.isFinite(x)||!Float.isFinite(y))continue;
            double magnitude=Math.hypot(x,y);
            magnitudes[valid++]=magnitude;
            sumX+=x;sumY+=y;max=Math.max(max,magnitude);
        }
        if(valid==0)return new Summary(width,height,total,0,hand,0,0,0,0);
        Arrays.sort(magnitudes,0,valid);
        double p95=magnitudes[Math.max(0,(int)Math.ceil(valid*.95)-1)];
        return new Summary(width,height,total,valid,hand,sumX/valid,sumY/valid,p95,max);
    }
}
