package by.xopilot.framescale;
public final class PhotonTemporalMathTest {
    private static void check(boolean v){if(!v)throw new AssertionError();}
    private static void near(float a,float b){if(Math.abs(a-b)>1e-5)throw new AssertionError(a+" != "+b);}
    public static void main(String[] args){
        var e=PhotonTemporalMath.resolveExtent(new float[]{1920,1200},.75f,1920,1200);
        check(e.x()==0&&e.y()==0&&e.width()==1440&&e.height()==900);
        var j=PhotonTemporalMath.jitterNdc(new float[]{.001f,-.002f},.5f);
        near(j[0],.002f);near(j[1],-.004f);
        float[] id=PhotonTemporalMath.identity();
        float[] m=PhotonTemporalMath.currentToPreviousClip(id,id,id,id,new float[]{0,0,0},new float[]{0,0,0});
        for(int i=0;i<16;i++)near(m[i],id[i]);
        m=PhotonTemporalMath.currentToPreviousClip(id,id,id,id,new float[]{3,2,-1},new float[]{1,1,2});
        near(m[12],2);near(m[13],1);near(m[14],-3);
        float[] product=PhotonTemporalMath.multiply(m,PhotonTemporalMath.invert(m));
        for(int i=0;i<16;i++)near(product[i],id[i]);
        boolean failed=false;try{PhotonTemporalMath.resolveExtent(new float[]{1920,1200},1.1f,1920,1200);}catch(IllegalArgumentException ex){failed=true;}check(failed);
        failed=false;try{PhotonTemporalMath.invert(new float[16]);}catch(IllegalArgumentException ex){failed=true;}check(failed);
        failed=false;try{PhotonTemporalMath.resolveExtent(new float[]{Float.NaN,1200},.5f,1920,1200);}catch(IllegalArgumentException ex){failed=true;}check(failed);
        System.out.println("PhotonTemporalMath PASS");
    }
}