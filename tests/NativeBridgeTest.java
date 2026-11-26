package by.xopilot.framescale;
public final class NativeBridgeTest {
    public static void main(String[] args) {
        if(args.length!=1) throw new AssertionError("Expected test mode");
        if(args[0].equals("missing")) {
            if(NativeBridge.load()||NativeBridge.status().equals("Not loaded")) throw new AssertionError("Missing library not rejected");
            System.out.println("JNI missing-library fallback PASS");return;
        }
        if(!NativeBridge.load()) throw new AssertionError(NativeBridge.status());
        if(!NativeBridge.validExtent(1920,1200)||NativeBridge.validExtent(-1,1)
            ||NativeBridge.validExtent(0,1)||NativeBridge.validExtent(8192,8192)) throw new AssertionError("JNI extent validation");
        try {NativeBridge.probe(new byte[17],new byte[16]);throw new AssertionError("Invalid UUID accepted");}
        catch(IllegalArgumentException expected){}
        String report=NativeBridge.probe(new byte[16],new byte[16]);
        if(report==null||report.isBlank())throw new AssertionError("Missing native result");
        if(report.startsWith("Matched"))throw new AssertionError("Zero UUID unexpectedly matched");
        System.out.println("JNI ABI, validation and unmatched-device tests PASS: "+report);
    }
}
