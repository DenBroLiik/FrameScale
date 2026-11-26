package by.xopilot.framescale;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Explicitly installed native library; no downloads, extraction or implicit search path. */
final class NativeBridge {
    static final int ABI=1;
    private static boolean attempted,loaded;
    private static String reason="Not loaded";
    static synchronized boolean load() {
        if(attempted) return loaded;
        attempted=true;
        try {
            String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT);
            String arch=System.getProperty("os.arch","");
            if(!os.startsWith("linux")||!(arch.equals("amd64")||arch.equals("x86_64")))
                throw new IOException("Only Linux x86_64 is supported");
            String configured=System.getProperty("framescale.native.path","");
            if(configured.isBlank()) throw new IOException("Set -Dframescale.native.path to a trusted locally built .so");
            Path path=Path.of(configured);
            if(!path.isAbsolute()) throw new IOException("Native library path must be absolute");
            path=path.toRealPath();
            if(!Files.isRegularFile(path)||!path.getFileName().toString().endsWith(".so"))
                throw new IOException("Expected a regular .so file");
            System.load(path.toString());
            int actual=nativeAbi();
            if(actual!=ABI) throw new IOException("ABI mismatch: expected "+ABI+", got "+actual);
            loaded=true;reason="ABI "+actual+" ready";
        } catch(IOException|RuntimeException|LinkageError e) {
            reason=e.getClass().getSimpleName()+": "+e.getMessage();
        }
        return loaded;
    }
    static synchronized String status() { return reason; }
    static String probe(byte[] devices,byte[] driver) {
        if(devices==null||devices.length<16||devices.length>256||devices.length%16!=0
            ||driver==null||driver.length!=16) throw new IllegalArgumentException("Invalid UUID data");
        if(!load()) return "Native bridge unavailable: "+status();
        return nativeProbe(devices.clone(),driver.clone());
    }
    static boolean validExtent(int w,int h) {
        if(!load()) return false;
        return nativeValidExtent(w,h);
    }
    private static native int nativeAbi();
    private static native boolean nativeValidExtent(int width,int height);
    private static native String nativeProbe(byte[] deviceUuids,byte[] driverUuid);
}
