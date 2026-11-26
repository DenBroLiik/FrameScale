package by.xopilot.framescale;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.network.chat.Component;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;
import org.slf4j.LoggerFactory;

/**
 * Experimental world-only scope. Owns a reduced color/depth target and optional immutable compute
 * staging textures; never resizes Minecraft's native target or changes the window size.
 */
public final class SpatialWorld {
    private static final SpatialWorld INSTANCE=new SpatialWorld();
    private static int scale=100;
    private static String state="Off";
    private static boolean syncing,needsSync,irisPresent;
    private static RenderTarget scoped;
    private static boolean getterSeen,wrapperSeen;
    public static void getterSeen() { getterSeen=true; }
    public static void wrapperSeen() { wrapperSeen=true; }
    private TextureTarget low;
    private SpatialUpscaler backend;
    private int input,output;
    private boolean direct;
    private final SpatialCost cost=new SpatialCost();
    private Resolution sourceSize,outputSize;
    private Object world;
    private SpatialWorld() {}
    static int load() { return scale; }
    static boolean available() {
        try { return getterSeen && wrapperSeen && blocked().isEmpty() && SpatialUpscaler.supported(); }
        catch(RuntimeException|LinkageError e) { return false; }
    }
    private static String blocked() {
        var mods=CompatInventory.loaded();
        return SpatialPolicy.blocked(mods,mods.containsKey("iris")&&IrisSpatialGuard.shadersDisabled());
    }
    static void beginSync() { syncing=true; }
    static void endSync() { syncing=false;needsSync=false; }
    static boolean needsSync() { return needsSync; }
    static void save(int requested) {
        if(syncing||requested==scale) return;
        String result=set(requested);
        var gui=Minecraft.getInstance().gui;
        if(gui!=null) gui.getChat().addMessage(Component.literal("[FrameScale] "+result));
    }
    static String set(int requested) {
        if(!SpatialPolicy.validScale(requested)) return "Scale must be 50–100";
        if(requested==100) {
            scale=100;needsSync=true;state="Off";INSTANCE.release();return status();
        }
        String blocked=blocked();
        if(!blocked.isEmpty()) { needsSync=true;return "Spatial mode blocked: "+blocked+" (unvalidated framebuffer integration)"; }
        if(!available()) {needsSync=true;return "Spatial mode requires OpenGL 4.5 and observed world/getter hooks (enter a world first)";}
        FrameScale.disableAuto();FrameScale.stopActiveProfiler();
        if(scale!=requested) INSTANCE.release();
        irisPresent=CompatInventory.loaded().containsKey("iris");
        scale=requested;needsSync=true;state="Enabled; waiting for supported world frame";
        return status();
    }
    static Component format(int requested) {
        var w=Minecraft.getInstance().getWindow();
        if(requested==100) return Component.translatable("framescale.spatial.off");
        if(w.getWidth()<=0||w.getHeight()<=0) return Component.literal(requested+"%");
        return Component.literal(new Resolution(w.getWidth(),w.getHeight()).transition(requested));
    }
    static String status() { return "Spatial "+scale+"% | "+state+" | independent RGBA8 backend; not DLSS"; }
    static String costStart() {
        var self=INSTANCE;
        if(scale==100||self.low==null) return "Enable Spatial and render a world frame first";
        self.cost.start((self.direct?"Direct":"Staging")+" "+self.sourceSize.label()+" → "+self.outputSize.label());
        return "Spatial GPU timing enabled; one sample per eight active frames";
    }
    static String costReport() { return INSTANCE.cost.report(); }
    static String costStop() { INSTANCE.cost.stop();return costReport(); }
    /** Only the render thread, and only inside the wrapped world call, sees this target. */
    public static RenderTarget scopedTarget() { return RenderSystem.isOnRenderThread()?scoped:null; }
    public static void render(Runnable original) {
        var self=INSTANCE;
        var mc=Minecraft.getInstance();
        if(scoped!=null || scale==100 || mc.level==null || mc.screen!=null
            || mc.options.graphicsMode().get()==GraphicsStatus.FABULOUS || mc.gameRenderer.currentEffect()!=null) {
            if(scoped==null && scale!=100) state="Paused: menu, world or unsupported graphics/post effect";
            original.run();return;
        }
        if(irisPresent&&!IrisSpatialGuard.shadersDisabled()) {
            scale=100;needsSync=true;state="Stopped: Iris shaders enabled or unknown";self.release();
            original.run();return;
        }
        var nativeTarget=mc.getMainRenderTarget();
        try {
            self.prepare(nativeTarget,mc.level);
            self.low.setClearColor(0,0,0,0);
            self.low.clear(Minecraft.ON_OSX);
            self.low.bindWrite(true);
        } catch(java.io.IOException|RuntimeException|LinkageError e) {
            self.fail(e);
            nativeTarget.bindWrite(true);
            original.run();return;
        }
        // Preserve vanilla/other wrappers' exception semantics and restore the scope even on failure.
        scoped=self.low;
        boolean complete=false;
        try {
            original.run();complete=true;
        } finally {
            scoped=null;
            try {
                if(complete) {
                    try { self.present(nativeTarget);state="Active "+self.sourceSize.label()+" → "+self.outputSize.label()+(self.direct?" | direct":" | staging"); }
                    catch(RuntimeException|LinkageError e) {
                        try { self.blitFallback(nativeTarget); }
                        finally { self.fail(e); }
                    }
                }
            } finally { nativeTarget.bindWrite(true); }
        }
    }
    private static int formatOf(int texture) {
        if(texture<=0||!GL11.glIsTexture(texture)) throw new IllegalStateException("Invalid render texture");
        return GL45.glGetTextureLevelParameteri(texture,0,GL11.GL_TEXTURE_INTERNAL_FORMAT);
    }
    private static int allocate(Resolution r) {
        int id=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
        if(id==0) throw new IllegalStateException("Cannot allocate spatial staging texture");
        try {
            GL45.glTextureStorage2D(id,1,GL11.GL_RGBA8,r.width(),r.height());
            if(GL45.glGetTextureLevelParameteri(id,0,GL11.GL_TEXTURE_WIDTH)!=r.width()
                ||GL45.glGetTextureLevelParameteri(id,0,GL11.GL_TEXTURE_HEIGHT)!=r.height())
                throw new IllegalStateException("Spatial staging allocation failed");
            return id;
        } catch(RuntimeException|LinkageError e) { GL11.glDeleteTextures(id);throw e; }
    }
    private void prepare(RenderTarget nativeTarget,Object currentWorld) throws java.io.IOException {
        if(!SpatialPolicy.validOutput(nativeTarget.width,nativeTarget.height)
            || nativeTarget.viewWidth!=nativeTarget.width||nativeTarget.viewHeight!=nativeTarget.height
            || !nativeTarget.useDepth||formatOf(nativeTarget.getColorTextureId())!=GL11.GL_RGBA8)
            throw new IllegalStateException("Unsupported native framebuffer format or extent");
        var dst=new Resolution(nativeTarget.width,nativeTarget.height);
        var src=dst.scaled(scale);
        if(low==null||world!=currentWorld||!dst.equals(outputSize)||!src.equals(sourceSize)) {
            release();
            backend=new SpatialUpscaler();
            low=new TextureTarget(src.width(),src.height(),true,Minecraft.ON_OSX);
            direct=SpatialSelfTest.directSupported(backend);
            if(!direct) { input=allocate(src);output=allocate(dst); }
            sourceSize=src;outputSize=dst;world=currentWorld;
        }
        if(formatOf(low.getColorTextureId())!=GL11.GL_RGBA8
            ||formatOf(low.getDepthTextureId())!=formatOf(nativeTarget.getDepthTextureId())
            ||GL45.glCheckNamedFramebufferStatus(low.frameBufferId,GL30.GL_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE
            ||GL45.glCheckNamedFramebufferStatus(nativeTarget.frameBufferId,GL30.GL_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Framebuffer incompatible with spatial color/depth transfer");
    }
    private static void copy(int src,int dst,Resolution r) {
        GL43.glCopyImageSubData(src,GL11.GL_TEXTURE_2D,0,0,0,0,
            dst,GL11.GL_TEXTURE_2D,0,0,0,0,r.width(),r.height(),1);
    }
    private void present(RenderTarget target) {
        boolean timing=cost.begin();
        try {
            if(direct) backend.dispatchDirect(low.getColorTextureId(),target.getColorTextureId(),0.65f);
            else {
                copy(low.getColorTextureId(),input,sourceSize);
                backend.dispatch(input,output,0.65f);
                copy(output,target.getColorTextureId(),outputSize);
            }
            blit(target,GL11.GL_DEPTH_BUFFER_BIT,GL11.GL_NEAREST);
        } finally { if(timing) cost.end(); }
    }
    private void blitFallback(RenderTarget target) {
        blit(target,GL11.GL_COLOR_BUFFER_BIT,GL11.GL_LINEAR);
        blit(target,GL11.GL_DEPTH_BUFFER_BIT,GL11.GL_NEAREST);
    }
    private void blit(RenderTarget target,int mask,int filter) {
        boolean scissor=GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        try {
            if(scissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL45.glBlitNamedFramebuffer(low.frameBufferId,target.frameBufferId,0,0,
                sourceSize.width(),sourceSize.height(),0,0,outputSize.width(),outputSize.height(),mask,filter);
        } finally { if(scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST); }
    }
    static void tick() {
        if(scale==100||Minecraft.getInstance().level==null) INSTANCE.release();
    }
    public static void shutdown() { scale=100;INSTANCE.release(); }
    private void fail(Throwable e) {
        LoggerFactory.getLogger("FrameScale").warn("Spatial world mode stopped",e);
        state="Stopped: "+e.getClass().getSimpleName()+": "+e.getMessage();
        scale=100;needsSync=true;release();
    }
    private void release() {
        // All call sites are render/client-thread callbacks; never release while redirected.
        if(scoped!=null) return;
        cost.stop();
        if(backend!=null) {try {backend.close();} catch(RuntimeException|LinkageError ignored) {} backend=null;}
        if(low!=null) {try {low.destroyBuffers();} catch(RuntimeException|LinkageError ignored) {} low=null;}
        if(input!=0) {GL11.glDeleteTextures(input);input=0;}
        if(output!=0) {GL11.glDeleteTextures(output);output=0;}
        sourceSize=outputSize=null;world=null;direct=false;
    }
}
