package by.xopilot.framescale;

public final class MotionStatsTest {
    private static void check(boolean value){if(!value)throw new AssertionError();}
    private static void near(double a,double b){if(Math.abs(a-b)>1e-6)throw new AssertionError(a+" != "+b);}
    public static void main(String[] args){
        float[] vectors={3,4, 0,0, 6,8, Float.NaN,1};
        byte[] mask={1,7,5,4};
        var s=MotionStats.summarize(2,2,vectors,mask);
        check(s.total()==4&&s.valid()==3&&s.hand()==1);
        check(s.depthEdges()==3&&s.projectedDepthEdges()==2);
        near(s.meanX(),3);near(s.meanY(),4);near(s.p95(),10);near(s.max(),10);
        check(s.format().contains("valid 3/4 (75.0%)"));
        check(s.format().contains("depth-edge candidates 3/4 (75.0%), projected 2"));
        var empty=MotionStats.summarize(1,2,new float[]{1,2,3,4},new byte[]{0,4});
        check(empty.valid()==0&&empty.hand()==0&&empty.depthEdges()==1&&empty.projectedDepthEdges()==0);
        check(empty.format().contains("depth-edge candidates 1/2 (50.0%), projected 0"));
        boolean failed=false;
        try{MotionStats.summarize(2,2,new float[]{1,2},new byte[]{1});}
        catch(IllegalArgumentException e){failed=true;}
        check(failed);
        failed=false;
        try{MotionStats.summarize(129,128,new float[129*128*2],new byte[129*128]);}
        catch(IllegalArgumentException e){failed=true;}
        check(failed);
        System.out.println("MotionStats PASS");
    }
}
