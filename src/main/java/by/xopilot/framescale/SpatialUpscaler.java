package by.xopilot.framescale;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * LDR RGBA8 spatial compute backend. Only scoped RGBA8 2D textures on the
 * current render context are accepted. No temporal inputs, framebuffer ownership,
 * CPU readback or allocation per output pixel. Supports direct and immutable staging paths.
 */
final class SpatialUpscaler implements AutoCloseable {
    private static final String RESOURCE="/assets/framescale/shaders/spatial.comp";
    private int program, strengthLocation;
    static boolean supported() {
        var c=GL.getCapabilities();
        return c.OpenGL45 && GL11.glGetInteger(GL42.GL_MAX_IMAGE_UNITS)>=2;
    }
    SpatialUpscaler() throws IOException {
        RenderSystem.assertOnRenderThread();
        if(!supported()) throw new IllegalStateException("OpenGL 4.5 compute/DSA unavailable");
        try(var in=SpatialUpscaler.class.getResourceAsStream(RESOURCE)) {
            if(in==null) throw new IOException("Missing "+RESOURCE);
            String source=new String(in.readAllBytes(),StandardCharsets.UTF_8);
            int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
            if(shader==0) throw new IllegalStateException("Cannot allocate compute shader");
            try {
                GL20.glShaderSource(shader,source);
                GL20.glCompileShader(shader);
                if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)
                    throw new IllegalStateException("Spatial shader compile: "+GL20.glGetShaderInfoLog(shader));
                program=GL20.glCreateProgram();
                if(program==0) throw new IllegalStateException("Cannot allocate program");
                GL20.glAttachShader(program,shader);
                GL20.glLinkProgram(program);
                if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)
                    throw new IllegalStateException("Spatial link: "+GL20.glGetProgramInfoLog(program));
                strengthLocation=GL20.glGetUniformLocation(program,"edgeStrength");
                if(strengthLocation<0) throw new IllegalStateException("Missing edgeStrength uniform");
                GL20.glDetachShader(program,shader);
            } catch(RuntimeException|LinkageError e) { close();throw e; }
            finally { GL20.glDeleteShader(shader); }
        }
    }
    private record ImageBinding(int name,int level,boolean layered,int layer,int access,int format) {
        static ImageBinding read(int unit) {
            return new ImageBinding(GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME,unit),
                GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LEVEL,unit),
                GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYERED,unit)!=0,
                GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYER,unit),
                GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_ACCESS,unit),
                GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_FORMAT,unit));
        }
        void restore(int unit) {
            GL42.glBindImageTexture(unit,name,level,layered,layer,access,format);
        }
    }
    private static Resolution texture(int id,boolean direct) {
        if(id<=0||!GL11.glIsTexture(id)) throw new IllegalArgumentException("Invalid texture");
        if((!direct&&GL45.glGetTextureParameteri(id,GL42.GL_TEXTURE_IMMUTABLE_FORMAT)==0)
            ||GL45.glGetTextureLevelParameteri(id,0,GL11.GL_TEXTURE_INTERNAL_FORMAT)!=GL11.GL_RGBA8)
            throw new IllegalArgumentException("Expected compatible RGBA8 texture");
        return new Resolution(GL45.glGetTextureLevelParameteri(id,0,GL11.GL_TEXTURE_WIDTH),
            GL45.glGetTextureLevelParameteri(id,0,GL11.GL_TEXTURE_HEIGHT));
    }
    /**
     * Caller guarantees distinct GL_TEXTURE_2D objects and that earlier incoherent
     * writes have been made visible. Dispatch is asynchronous; barrier makes the
     * result visible to subsequent texture sampling, image access and texture readback.
     */
    void dispatch(int input,int output,float strength) {
        dispatch(input,output,strength,false);
    }
    void dispatchDirect(int input,int output,float strength) {
        dispatch(input,output,strength,true);
    }
    private void dispatch(int input,int output,float strength,boolean direct) {
        RenderSystem.assertOnRenderThread();
        if(program==0) throw new IllegalStateException("Backend closed");
        if(input==output||!Float.isFinite(strength)||strength<0||strength>1)
            throw new IllegalArgumentException("Aliased textures or invalid strength");
        Resolution src=texture(input,direct),dst=texture(output,direct);
        if(dst.width()<src.width()||dst.height()<src.height()) throw new IllegalArgumentException("Upscale only");
        int gx=(dst.width()+7)/8,gy=(dst.height()+7)/8;
        if(gx>GL30.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT,0)
            ||gy>GL30.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT,1))
            throw new IllegalArgumentException("Compute dispatch exceeds device limits");
        int previous=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        var first=ImageBinding.read(0);var second=ImageBinding.read(1);
        try {
            GL20.glUseProgram(program);
            GL20.glUniform1f(strengthLocation,strength);
            GL42.glBindImageTexture(0,input,0,false,0,GL15_READ_ONLY,GL11.GL_RGBA8);
            GL42.glBindImageTexture(1,output,0,false,0,GL15_WRITE_ONLY,GL11.GL_RGBA8);
            if(direct && (GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME,0)!=input
                ||GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME,1)!=output))
                throw new IllegalStateException("Direct image binding failed");
            GL43.glDispatchCompute(gx,gy,1);
            GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                |GL42.GL_TEXTURE_FETCH_BARRIER_BIT|GL42.GL_TEXTURE_UPDATE_BARRIER_BIT
                |GL42.GL_FRAMEBUFFER_BARRIER_BIT);
        } finally {
            second.restore(1);
            first.restore(0);
            GL20.glUseProgram(previous);
        }
    }
    private static final int GL15_READ_ONLY=org.lwjgl.opengl.GL15.GL_READ_ONLY;
    private static final int GL15_WRITE_ONLY=org.lwjgl.opengl.GL15.GL_WRITE_ONLY;
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if(program!=0) { GL20.glDeleteProgram(program);program=0; }
    }
}
