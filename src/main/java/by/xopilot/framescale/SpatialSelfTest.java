package by.xopilot.framescale;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Explicit small readback test. Never reads or writes Minecraft's framebuffer. */
final class SpatialSelfTest {
    private static final int[] PIXEL_STORE={
        GL11.GL_PACK_ALIGNMENT,GL11.GL_PACK_ROW_LENGTH,GL11.GL_PACK_SKIP_ROWS,GL11.GL_PACK_SKIP_PIXELS,
        GL11.GL_UNPACK_ALIGNMENT,GL11.GL_UNPACK_ROW_LENGTH,GL11.GL_UNPACK_SKIP_ROWS,GL11.GL_UNPACK_SKIP_PIXELS
    };
    static String run() throws IOException {
        if(!SpatialUpscaler.supported()) return "Spatial self-test unavailable: OpenGL 4.5 required";
        if(GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING)!=0
            ||GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING)!=0)
            return "Spatial self-test deferred: pixel transfer buffer in use";
        int[] previous=new int[PIXEL_STORE.length];
        for(int i=0;i<previous.length;i++) previous[i]=GL11.glGetInteger(PIXEL_STORE[i]);
        try {
            for(int i=0;i<previous.length;i++) GL11.glPixelStorei(PIXEL_STORE[i],i%4==0?1:0);
            try(var backend=new SpatialUpscaler()) {
                var constant=BufferUtils.createByteBuffer(24);
                for(int i=0;i<6;i++) constant.put((byte)37).put((byte)128).put((byte)241).put((byte)255);
                constant.flip();
                ByteBuffer out=execute(backend,2,3,7,11,constant);
                for(int i=0;i<77;i++) checkPixel(out,i,37,128,241,255);
                var pattern=BufferUtils.createByteBuffer(16);
                for(int i=0;i<2;i++) pattern.put((byte)0).put((byte)0).put((byte)0).put((byte)255)
                    .put((byte)255).put((byte)255).put((byte)255).put((byte)255);
                pattern.flip();
                var identity=execute(backend,2,2,2,2,pattern);
                for(int i=0;i<16;i++) if(identity.get(i)!=pattern.get(i)) throw new IllegalStateException("Identity mismatch");
                var edge=execute(backend,2,2,8,4,pattern);
                checkPixel(edge,0,0,0,0,255);checkPixel(edge,7,255,255,255,255);
                if(Byte.toUnsignedInt(edge.get(12))>=96||Byte.toUnsignedInt(edge.get(16))<=159)
                    throw new IllegalStateException("Edge response mismatch");
                var single=BufferUtils.createByteBuffer(4);
                single.put((byte)1).put((byte)2).put((byte)3).put((byte)128).flip();
                var alpha=execute(backend,1,1,9,3,single);
                for(int i=0;i<27;i++) checkPixel(alpha,i,1,2,3,128);
                return "Spatial self-test PASS on "+GL11.glGetString(GL11.GL_RENDERER)
                    +": constant, identity, edge, alpha, odd extents; direct FBO probe="+directSupported(backend)+". World rendering unchanged.";
            }
        } finally {
            for(int i=0;i<previous.length;i++) GL11.glPixelStorei(PIXEL_STORE[i],previous[i]);
        }
    }
    /** Probe only private disposable FBOs; never touch the game image. */
    static boolean directSupported(SpatialUpscaler backend) {
        if(GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING)!=0
            ||GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING)!=0) return false;
        int[] saved=new int[PIXEL_STORE.length];
        for(int i=0;i<saved.length;i++) saved[i]=GL11.glGetInteger(PIXEL_STORE[i]);
        int binding=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int src=0,dst=0,sf=0,df=0;
        try {
            for(int i=0;i<saved.length;i++) GL11.glPixelStorei(PIXEL_STORE[i],i%4==0?1:0);
            src=mutable(2,2);dst=mutable(5,3);
            var color=BufferUtils.createByteBuffer(16);
            for(int i=0;i<4;i++) color.put((byte)37).put((byte)128).put((byte)241).put((byte)255);
            color.flip();
            GL45.glTextureSubImage2D(src,0,0,0,2,2,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,color);
            sf=GL45.glCreateFramebuffers();df=GL45.glCreateFramebuffers();
            if(sf==0||df==0) return false;
            GL45.glNamedFramebufferTexture(sf,GL30.GL_COLOR_ATTACHMENT0,src,0);
            GL45.glNamedFramebufferTexture(df,GL30.GL_COLOR_ATTACHMENT0,dst,0);
            if(GL45.glCheckNamedFramebufferStatus(sf,GL30.GL_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE
                ||GL45.glCheckNamedFramebufferStatus(df,GL30.GL_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE) return false;
            backend.dispatchDirect(src,dst,0.65f);
            var result=BufferUtils.createByteBuffer(60);
            GL45.glGetTextureImage(dst,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,result);
            for(int i=0;i<15;i++) checkPixel(result,i,37,128,241,255);
            return true;
        } catch(RuntimeException|LinkageError e) { return false; }
        finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,binding);
            if(sf!=0) GL30.glDeleteFramebuffers(sf);
            if(df!=0) GL30.glDeleteFramebuffers(df);
            if(src!=0) GL11.glDeleteTextures(src);
            if(dst!=0) GL11.glDeleteTextures(dst);
            for(int i=0;i<saved.length;i++) GL11.glPixelStorei(PIXEL_STORE[i],saved[i]);
        }
    }
    private static int mutable(int w,int h) {
        int id=GL11.glGenTextures();
        if(id==0) throw new IllegalStateException("Texture allocation failed");
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,id);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,w,h,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(ByteBuffer)null);
            return id;
        } catch(RuntimeException|LinkageError e) { GL11.glDeleteTextures(id);throw e; }
    }
    private static ByteBuffer execute(SpatialUpscaler backend,int w,int h,int ow,int oh,ByteBuffer data) {
        int src=0,dst=0;
        try {
            src=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            dst=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            if(src==0||dst==0) throw new IllegalStateException("Texture allocation failed");
            GL45.glTextureStorage2D(src,1,GL11.GL_RGBA8,w,h);
            GL45.glTextureStorage2D(dst,1,GL11.GL_RGBA8,ow,oh);
            GL45.glTextureSubImage2D(src,0,0,0,w,h,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,data);
            backend.dispatch(src,dst,0.65f);
            var result=BufferUtils.createByteBuffer(ow*oh*4);
            GL45.glGetTextureImage(dst,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,result);
            return result;
        } finally {
            if(dst!=0) GL11.glDeleteTextures(dst);
            if(src!=0) GL11.glDeleteTextures(src);
        }
    }
    private static void checkPixel(ByteBuffer b,int pixel,int r,int g,int blue,int a) {
        int i=pixel*4;
        if(Byte.toUnsignedInt(b.get(i))!=r||Byte.toUnsignedInt(b.get(i+1))!=g
            ||Byte.toUnsignedInt(b.get(i+2))!=blue||Byte.toUnsignedInt(b.get(i+3))!=a)
            throw new IllegalStateException("Pixel mismatch at "+pixel);
    }
}
