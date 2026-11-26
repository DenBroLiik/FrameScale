package by.xopilot.framescale;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Only preferences persist. Enabling automatic shader reloads always requires opt-in each launch. */
record AutoPreferences(int target,int floor) {
    static final AutoPreferences DEFAULT=new AutoPreferences(80,75);
    AutoPreferences {
        if(target<30||target>240||floor<50||floor>100) throw new IllegalArgumentException("Invalid preferences");
    }
    static AutoPreferences load(Path file) throws IOException {
        if(!Files.exists(file)) return DEFAULT;
        if(Files.size(file)>4096) throw new IOException("FrameScale config too large");
        var p=new Properties();
        try(InputStream in=Files.newInputStream(file)) { p.load(in); }
        return new AutoPreferences(parse(p,"target",80,30,240),parse(p,"floor",75,50,100));
    }
    private static int parse(Properties p,String key,int fallback,int min,int max) {
        try {
            int v=Integer.parseInt(p.getProperty(key,Integer.toString(fallback)));
            return v>=min&&v<=max?v:fallback;
        } catch(NumberFormatException e) { return fallback; }
    }
    void save(Path file) throws IOException {
        Path dir=file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp=Files.createTempFile(dir,"framescale-",".tmp");
        try {
            var p=new Properties();
            p.setProperty("target",Integer.toString(target)); p.setProperty("floor",Integer.toString(floor));
            try(OutputStream out=Files.newOutputStream(tmp)) { p.store(out,"FrameScale automatic quality preferences"); }
            try { Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
    }
}
