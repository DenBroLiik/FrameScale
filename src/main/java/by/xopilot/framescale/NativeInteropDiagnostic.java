package by.xopilot.framescale;

import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.system.MemoryStack;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Capture GL identities on the render thread; Vulkan enumeration runs on a single opt-in worker. */
final class NativeInteropDiagnostic {
    private static final AtomicBoolean RUNNING=new AtomicBoolean();
    private static volatile String last="Not run";
    static String status() { return (RUNNING.get()?"Running":"Idle")+" | "+NativeBridge.status()+" | "+last; }
    static String start(Consumer<String> message) {
        if(!RUNNING.compareAndSet(false,true)) return "Native diagnostic already running";
        try {
            var caps=GL.getCapabilities();
            if(!caps.GL_EXT_memory_object||!caps.GL_EXT_memory_object_fd
                ||!caps.GL_EXT_semaphore||!caps.GL_EXT_semaphore_fd) {
                RUNNING.set(false);return "Missing OpenGL external memory/semaphore FD extensions";
            }
            byte[] devices,driver=new byte[16];
            try(var stack=MemoryStack.stackPush()) {
                int count=GL11.glGetInteger(EXTMemoryObject.GL_NUM_DEVICE_UUIDS_EXT);
                if(count<1||count>16) throw new IllegalStateException("Invalid GL UUID count");
                devices=new byte[count*16];
                var buf=stack.malloc(16);
                for(int i=0;i<count;i++) {
                    EXTMemoryObject.glGetUnsignedBytei_vEXT(EXTMemoryObject.GL_DEVICE_UUID_EXT,i,buf);
                    for(int j=0;j<16;j++) devices[i*16+j]=buf.get(j);
                }
                EXTMemoryObject.glGetUnsignedBytevEXT(EXTMemoryObject.GL_DRIVER_UUID_EXT,buf);
                for(int j=0;j<16;j++) driver[j]=buf.get(j);
            }
            var mc=Minecraft.getInstance();
            Thread worker=new Thread(()->{
                try { last=NativeBridge.probe(devices,driver); }
                catch(RuntimeException|LinkageError e) { last="Native diagnostic failed: "+e; }
                finally { RUNNING.set(false); }
                String result=last;
                mc.execute(()->message.accept(result));
            },"FrameScale-native-diagnostic");
            worker.setDaemon(true);worker.start();
            return "Native device capability diagnostic started; /framescale native";
        } catch(RuntimeException|LinkageError e) {
            RUNNING.set(false);return "Cannot capture GL identity: "+e;
        }
    }
}
