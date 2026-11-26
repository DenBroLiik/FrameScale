package by.xopilot.framescale;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import net.minecraft.client.Minecraft;

/** Read-only OpenGL capability inventory. Does not initialize Vulkan or NGX. */
final class GpuDiagnostics {
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
            +" | DLSS backend: not implemented (Vulkan device/NGX not tested)";
    }
}
