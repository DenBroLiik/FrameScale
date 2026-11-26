package by.xopilot.framescale;

public final class PhotonTemporalMathTest {
    private static void check(boolean v){if(!v)throw new AssertionError();}
    private static void near(float a,float b){if(Math.abs(a-b)>1e-5f)throw new AssertionError(a+" != "+b);}
    private static void near(float[] a,float[] b,float epsilon){
        check(a.length==b.length);
        for(int i=0;i<a.length;i++)if(Math.abs(a[i]-b[i])>epsilon)
            throw new AssertionError("index "+i+": "+a[i]+" != "+b[i]);
    }
    private static float[] rotateY(float angle){
        float c=(float)Math.cos(angle),s=(float)Math.sin(angle);
        return new float[]{c,0,-s,0, 0,1,0,0, s,0,c,0, 0,0,0,1};
    }
    private static float[] perspective(float fovDegrees,float aspect,float near,float far){
        float f=1f/(float)Math.tan(Math.toRadians(fovDegrees)*.5);
        float[] p=new float[16];
        p[0]=f/aspect;p[5]=f;p[10]=(far+near)/(near-far);p[11]=-1f;
        p[14]=(2f*far*near)/(near-far);
        return p;
    }
    private static float[] transform(float[] m,float[] v){
        float[] out=new float[4];
        for(int row=0;row<4;row++)
            out[row]=m[row]*v[0]+m[4+row]*v[1]+m[8+row]*v[2]+m[12+row]*v[3];
        return out;
    }
    private static float[] addCameraDelta(float[] p,float[] current,float[] previous){
        return new float[]{p[0]+current[0]-previous[0],p[1]+current[1]-previous[1],
            p[2]+current[2]-previous[2],p[3]};
    }
    public static void main(String[] args){
        var e=PhotonTemporalMath.resolveExtent(new float[]{1920,1200},.75f,1920,1200);
        check(e.x()==0&&e.y()==0&&e.width()==1440&&e.height()==900);
        var j=PhotonTemporalMath.jitterNdc(new float[]{.001f,-.002f},.5f);
        near(j[0],.002f);near(j[1],-.004f);
        float[] id=PhotonTemporalMath.identity();
        float[] m=PhotonTemporalMath.currentToPreviousClip(id,id,id,id,new float[]{0,0,0},new float[]{0,0,0});
        near(m,id,1e-5f);
        m=PhotonTemporalMath.currentToPreviousClip(id,id,id,id,new float[]{3,2,-1},new float[]{1,1,2});
        near(m[12],2);near(m[13],1);near(m[14],-3);
        float[] product=PhotonTemporalMath.multiply(m,PhotonTemporalMath.invert(m));
        near(product,id,1e-5f);
        float[] hand=PhotonTemporalMath.currentToPreviousHandClipFromInverse(id,id,id,id);
        near(hand,id,1e-5f);
        float[] world=PhotonTemporalMath.currentToPreviousClipFromInverse(id,id,id,id,
            new float[]{4,0,0},new float[]{1,0,0});
        near(world[12],3);
        float[] handNoCamera=PhotonTemporalMath.currentToPreviousHandClipFromInverse(id,id,id,id);
        near(handNoCamera[12],0);

        // Compare the composed clip transform with Photon's sequential view->scene->previous-view path.
        float[] currentMv=rotateY(.31f),previousMv=rotateY(-.17f);
        float[] currentProjection=perspective(70f,16f/9f,.1f,512f);
        float[] previousProjection=perspective(68f,16f/9f,.1f,512f);
        float[] camera={12.5f,65f,-4.75f},previousCamera={12.2f,65.1f,-5.1f};
        float[] pointInCurrentView={1.25f,-.4f,-8f,1f};
        float[] currentClip=transform(currentProjection,pointInCurrentView);
        float[] composed=PhotonTemporalMath.currentToPreviousClip(currentMv,currentProjection,
            previousMv,previousProjection,camera,previousCamera);
        float[] actual=transform(composed,currentClip);
        float[] view=transform(PhotonTemporalMath.invert(currentProjection),currentClip);
        float[] scene=transform(PhotonTemporalMath.invert(currentMv),view);
        float[] previousScene=addCameraDelta(scene,camera,previousCamera);
        float[] expected=transform(previousProjection,transform(previousMv,previousScene));
        near(actual,expected,2e-4f);

        float[] handComposed=PhotonTemporalMath.currentToPreviousHandClipFromInverse(
            PhotonTemporalMath.invert(currentMv),PhotonTemporalMath.invert(currentProjection),
            previousMv,previousProjection);
        float[] handActual=transform(handComposed,currentClip);
        float[] handExpected=transform(previousProjection,
            transform(previousMv,transform(PhotonTemporalMath.invert(currentMv),view)));
        near(handActual,handExpected,2e-4f);
        check(Math.abs(actual[0]/actual[3]-handActual[0]/handActual[3])>1e-5f);
        check(Math.abs(actual[1]/actual[3]-currentClip[1]/currentClip[3])>1e-5f);

        boolean failed=false;try{PhotonTemporalMath.resolveExtent(new float[]{1920,1200},1.1f,1920,1200);}catch(IllegalArgumentException ex){failed=true;}check(failed);
        failed=false;try{PhotonTemporalMath.invert(new float[16]);}catch(IllegalArgumentException ex){failed=true;}check(failed);
        failed=false;try{PhotonTemporalMath.resolveExtent(new float[]{Float.NaN,1200},.5f,1920,1200);}catch(IllegalArgumentException ex){failed=true;}check(failed);
        System.out.println("PhotonTemporalMath PASS");
    }
}
