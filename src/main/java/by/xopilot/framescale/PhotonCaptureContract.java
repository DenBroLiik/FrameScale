package by.xopilot.framescale;

final class PhotonCaptureContract {
    private PhotonCaptureContract() {}
    static boolean candidate(String name) {
        return "composite4".equals(name)||"world0/composite4".equals(name)
            ||"world-1/composite4".equals(name)||"world1/composite4".equals(name);
    }
    static boolean outputs(int[] buffers) {
        return buffers!=null&&buffers.length==2&&buffers[0]==0&&buffers[1]==5;
    }
    static boolean extent(int w,int h) {
        return w>0&&h>0&&w<=8192&&h<=8192&&(long)w*h<=16_777_216L;
    }
    static boolean finite(float[] a,int length) {
        if(a==null||a.length!=length) return false;
        for(float f:a) if(!Float.isFinite(f)) return false;
        return true;
    }
    static boolean colorFormat(int f) { return f==0x8c3a||f==0x881a; } // R11F_G11F_B10F, RGBA16F
    static boolean depthFormat(int f) {
        return f==0x81a5||f==0x81a6||f==0x81a7||f==0x8cac||f==0x88f0||f==0x8cad;
    }
}
