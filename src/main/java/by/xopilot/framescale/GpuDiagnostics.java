package by.xopilot.framescale;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.system.MemoryStack;
import java.util.HexFormat;
import net.minecraft.client.Minecraft;

/** Read-only OpenGL capability inventory. Does not initialize Vulkan or NGX. */
final class GpuDiagnostics {
    private static String deviceIds() {
        if(!GL.getCapabilities().GL_EXT_memory_object) return "GL UUID unavailable";
        try(var stack=MemoryStack.stackPush()) {
            int count=GL11.glGetInteger(EXTMemoryObject.GL_NUM_DEVICE_UUIDS_EXT);
            if(count<1||count>16) return "GL UUID count invalid";
            var bytes=stack.malloc(16);
            var out=new StringBuilder("GL device UUIDs=");
            for(int i=0;i<count;i++) {
                EXTMemoryObject.glGetUnsignedBytei_vEXT(EXTMemoryObject.GL_DEVICE_UUID_EXT,i,bytes);
                byte[] value=new byte[16];for(int j=0;j<16;j++) value[j]=bytes.get(j);
                if(i>0) out.append(',');
                out.append(HexFormat.of().formatHex(value));
            }
            return out.toString();
        } catch(RuntimeException|LinkageError e) { return "GL UUID unavailable: "+e.getClass().getSimpleName(); }
    }
    static String report() {
        var c=GL.getCapabilities();
        var win=Minecraft.getInstance().getWindow();
        return "GL vendor="+GL11.glGetString(GL11.GL_VENDOR)
            +" | renderer="+GL11.glGetString(GL11.GL_RENDERER)
            +" | version="+GL11.glGetString(GL11.GL_VERSION)
            +" | framebuffer="+win.getWidth()+"x"+win.getHeight()
            +" | timestamp="+c.OpenGL33
            +" | spatial compute/DSA="+c.OpenGL45
            +" | memory_object="+c.GL_EXT_memory_object
            +" | memory_fd="+c.GL_EXT_memory_object_fd
            +" | memory_win32="+c.GL_EXT_memory_object_win32
            +" | semaphore="+c.GL_EXT_semaphore
            +" | semaphore_fd="+c.GL_EXT_semaphore_fd
            +" | semaphore_win32="+c.GL_EXT_semaphore_win32
            +" | "+deviceIds()
            +" | DLSS backend: not implemented (Vulkan device/NGX not tested)";
    }
}
