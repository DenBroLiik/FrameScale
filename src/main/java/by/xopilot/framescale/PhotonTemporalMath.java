package by.xopilot.framescale;

import java.util.Arrays;

/** Photon TAAU coordinate and camera reprojection math. Matrices are OpenGL column-major. */
final class PhotonTemporalMath {
    private PhotonTemporalMath() {}

    record Extent(int x,int y,int width,int height) {}

    static Extent resolveExtent(float[] viewResolution,float scale,int textureWidth,int textureHeight) {
        if(viewResolution==null||viewResolution.length!=2||!finite(viewResolution)
            ||!Float.isFinite(scale)||scale<=0f||scale>1f||textureWidth<1||textureHeight<1)
            throw new IllegalArgumentException("Invalid Photon render dimensions");
        int w=Math.round(viewResolution[0]*scale),h=Math.round(viewResolution[1]*scale);
        if(w<1||h<1||w>textureWidth||h>textureHeight||(long)w*h>16_777_216L)
            throw new IllegalArgumentException("TAAU extent exceeds source texture");
        // Photon stores the scaled render region at the lower-left of the allocation.
        return new Extent(0,0,w,h);
    }

    /** Photon shaders.properties defines taa_offset=(jitter_x/viewWidth,jitter_y/viewWidth). */
    static float[] jitterNdc(float[] taaOffset,float scale) {
        if(taaOffset==null||taaOffset.length!=2||!finite(taaOffset)||!Float.isFinite(scale)||scale<=0||scale>1)
            throw new IllegalArgumentException("Invalid Photon jitter");
        return new float[]{taaOffset[0]/scale,taaOffset[1]/scale};
    }

    /**
     * previousProjection * previousModelView * cameraTranslation *
     * inverse(currentModelView) * inverse(currentProjection).
     * All matrices and vectors use OpenGL column-major convention.
     */
    static float[] currentToPreviousClip(float[] currentModelView,float[] currentProjection,
            float[] previousModelView,float[] previousProjection,float[] camera,float[] previousCamera) {
        requireMatrix(currentModelView);requireMatrix(currentProjection);
        requireMatrix(previousModelView);requireMatrix(previousProjection);
        if(camera==null||camera.length!=3||previousCamera==null||previousCamera.length!=3
            ||!finite(camera)||!finite(previousCamera)) throw new IllegalArgumentException("Invalid camera position");
        float[] invMv=invert(currentModelView),invProj=invert(currentProjection);
        float[] translation=identity();
        translation[12]=camera[0]-previousCamera[0];
        translation[13]=camera[1]-previousCamera[1];
        translation[14]=camera[2]-previousCamera[2];
        float[] result=multiply(previousProjection,previousModelView);
        result=multiply(result,translation);
        result=multiply(result,invMv);
        result=multiply(result,invProj);
        if(!finite(result))throw new IllegalArgumentException("Non-finite reprojection matrix");
        return result;
    }

    static float[] identity(){return new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};}
    static float[] multiply(float[] a,float[] b){
        requireMatrix(a);requireMatrix(b);float[] out=new float[16];
        for(int col=0;col<4;col++)for(int row=0;row<4;row++)
            for(int k=0;k<4;k++)out[col*4+row]+=a[k*4+row]*b[col*4+k];
        return out;
    }
    static float[] invert(float[] m){
        requireMatrix(m);
        double[][] a=new double[4][8];
        for(int row=0;row<4;row++){for(int col=0;col<4;col++)a[row][col]=m[col*4+row];a[row][row+4]=1;}
        for(int col=0;col<4;col++){
            int pivot=col;for(int row=col+1;row<4;row++)if(Math.abs(a[row][col])>Math.abs(a[pivot][col]))pivot=row;
            if(Math.abs(a[pivot][col])<1e-12)throw new IllegalArgumentException("Singular camera matrix");
            double[] tmp=a[col];a[col]=a[pivot];a[pivot]=tmp;
            double divisor=a[col][col];for(int j=0;j<8;j++)a[col][j]/=divisor;
            for(int row=0;row<4;row++)if(row!=col){double factor=a[row][col];for(int j=0;j<8;j++)a[row][j]-=factor*a[col][j];}
        }
        float[] out=new float[16];for(int row=0;row<4;row++)for(int col=0;col<4;col++)out[col*4+row]=(float)a[row][col+4];
        if(!finite(out))throw new IllegalArgumentException("Non-finite inverse");return out;
    }
    private static void requireMatrix(float[] m){if(m==null||m.length!=16||!finite(m))throw new IllegalArgumentException("Invalid 4x4 matrix");}
    private static boolean finite(float[] a){for(float v:a)if(!Float.isFinite(v))return false;return true;}
}