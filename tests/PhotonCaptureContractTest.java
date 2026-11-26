package by.xopilot.framescale;
public final class PhotonCaptureContractTest {
    private static void check(boolean b){if(!b)throw new AssertionError();}
    public static void main(String[] args){
        check(PhotonCaptureContract.candidate("composite4"));
        check(PhotonCaptureContract.candidate("world-1/composite4"));
        check(!PhotonCaptureContract.candidate("composite40"));
        check(!PhotonCaptureContract.candidate(null));
        check(PhotonCaptureContract.outputs(new int[]{0,5}));
        check(!PhotonCaptureContract.outputs(new int[]{5,0}));
        check(PhotonCaptureContract.extent(1920,1200));
        check(!PhotonCaptureContract.extent(8192,8192));
        check(PhotonCaptureContract.colorFormat(0x8c3a));
        check(!PhotonCaptureContract.colorFormat(0x8058));
        check(PhotonCaptureContract.depthFormat(0x8cac));
        check(!PhotonCaptureContract.finite(new float[]{Float.NaN},1));
        check(PhotonCaptureContract.finite(new float[]{0,1},2));
        System.out.println("Photon capture contract PASS");
    }
}
