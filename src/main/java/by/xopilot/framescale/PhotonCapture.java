package by.xopilot.framescale;

import by.xopilot.framescale.mixin.IrisProbePlugin;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryStack;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** One-shot metadata only. No image copies, pixel reads, binding changes or retained GL handles. */
public final class PhotonCapture {
    private static volatile boolean armed;
    private static long deadline;
    private static int probeWidth,probeHeight,remaining;
    private static boolean reprojectionProbe;
    static String reproject(Consumer<String> message){
        if(armed||TemporalProbe.pending())return "Capture or timing already pending";
        String result=start(message);
        if(armed){TemporalProbe.resetSamples();reprojectionProbe=true;}
        return result;
    }
    static String series(int w,int h,int count,Consumer<String> message){
        if(armed||TemporalProbe.pending())return "Capture or timing already pending";
        String result=probe(w,h,message);
        if(armed&&probeWidth==w&&probeHeight==h&&remaining==1){remaining=count;deadline=System.nanoTime()+30_000_000_000L;}
        return result;
    }
    static String probe(int w,int h,Consumer<String> message){
        if(armed||TemporalProbe.pending())return "Capture or timing already pending";
        String result=start(message);
        if(armed){TemporalProbe.resetSamples();probeWidth=w;probeHeight=h;remaining=1;reprojectionProbe=false;}
        return result;
    }
    static String probeReport(){return TemporalProbe.status();}
    public static void shutdown(){TemporalProbe.release();}
    private static Object world;
    private static Consumer<String> reply;
    private static PhotonBridge.Settings settings;
    private static String last="Not captured",uniforms="No captured uniforms";
    private PhotonCapture() {}
    static String start(Consumer<String> message) {
        if(armed) return "Capture already armed";
        try {
            RenderSystem.assertOnRenderThread();
            if(Minecraft.getInstance().level==null) return "Enter a world first";
            if(!IrisProbePlugin.captureAccepted) return "Iris composite hook unavailable";
            if(!GL.getCapabilities().OpenGL45) return "Capture needs OpenGL 4.5";
            settings=PhotonBridge.read();
            if(!settings.taa()||!settings.enabled()) return "Enable Photon TAA and TAAU first";
            world=Minecraft.getInstance().level;
            reply=message;deadline=System.nanoTime()+5_000_000_000L;
            last="Waiting for Photon composite4";uniforms="No captured uniforms";armed=true;
            return "Capture armed for 5 seconds; close chat. /framescale capture report";
        } catch(ReflectiveOperationException|RuntimeException|LinkageError e) {
            return "Capture unavailable: "+e.getClass().getSimpleName();
        }
    }
    static String report(){return last;}
    static String uniformReport(){return uniforms;}
    static String stop(){if(armed) finish("Capture cancelled");TemporalProbe.release();return last;}
    static void tick(){
        TemporalProbe.tick();
        if(!armed&&!TemporalProbe.pending())TemporalProbe.release();
        if(armed&&(System.nanoTime()-deadline>=0||world!=Minecraft.getInstance().level))
            finish("Capture expired or world changed");
    }
    private static void finish(String s){
        armed=false;world=null;settings=null;probeWidth=probeHeight=remaining=0;reprojectionProbe=false;last=s;
        var callback=reply;reply=null;
        if(callback!=null) Minecraft.getInstance().execute(()->callback.accept(s));
    }
    private static Object field(Object obj,Class<?> type,String name) throws ReflectiveOperationException {
        Field f=type.getDeclaredField(name);
        if(!f.trySetAccessible()) throw new IllegalAccessException(name);
        return f.get(obj);
    }
    private static Object call(Object obj,String name) throws ReflectiveOperationException {
        return obj.getClass().getMethod(name).invoke(obj);
    }
    private static int number(Object o){return ((Number)o).intValue();}
    private record Texture(int w,int h,int format){
        @Override public String toString(){return w+"x"+h+" format=0x"+Integer.toHexString(format);}
    }
    private static Texture texture(int id){
        if(id<=0||!GL11.glIsTexture(id)) throw new IllegalStateException("Missing texture");
        int w=GL45.glGetTextureLevelParameteri(id,0,GL11.GL_TEXTURE_WIDTH);
        int h=GL45.glGetTextureLevelParameteri(id,0,GL11.GL_TEXTURE_HEIGHT);
        int f=GL45.glGetTextureLevelParameteri(id,0,GL11.GL_TEXTURE_INTERNAL_FORMAT);
        if(!PhotonCaptureContract.extent(w,h)) throw new IllegalStateException("Texture extent invalid");
        return new Texture(w,h,f);
    }
    /** Query only an active uniform of the exact expected GLSL type and size. */
    private static float[] uniform(int program,String key,int expectedType,int length){
        int count=GL20.glGetProgrami(program,GL20.GL_ACTIVE_UNIFORMS);
        if(count<0||count>4096) return null;
        try(var stack=MemoryStack.stackPush()){
            var size=stack.mallocInt(1);var type=stack.mallocInt(1);
            for(int i=0;i<count;i++){
                String name=GL20.glGetActiveUniform(program,i,256,size,type);
                if(!name.equals(key)&&!name.equals("iris_"+key)) continue;
                if(size.get(0)!=1||type.get(0)!=expectedType) return null;
                int location=GL20.glGetUniformLocation(program,name);
                if(location<0) return null;
                var data=stack.mallocFloat(length);
                GL20.glGetUniformfv(program,location,data);
                float[] values=new float[length];data.get(values);
                return PhotonCaptureContract.finite(values,length)?values:null;
            }
            return null;
        }
    }
    private static String show(float[] a){return a==null?"unavailable":Arrays.toString(a);}
    public static void observe(Object renderer){
        if(!armed) return;
        try {
            RenderSystem.assertOnRenderThread();
            if(world!=Minecraft.getInstance().level||System.nanoTime()-deadline>=0){finish("Capture expired or world changed");return;}
            int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            if(program==0) return;
            Class<?> composite=Class.forName("net.irisshaders.iris.pipeline.CompositeRenderer");
            Class<?> passType=Class.forName(composite.getName()+"$Pass");
            var passes=(List<?>)field(renderer,composite,"passes");
            if(passes.size()>512) throw new IllegalStateException("Too many passes");
            Object pass=null;
            for(Object p:passes){
                if(!PhotonCaptureContract.candidate((String)field(p,passType,"name"))) continue;
                Object shader=field(p,passType,"program");
                if(shader!=null&&number(call(shader,"getProgramId"))==program){
                    if(pass!=null) throw new IllegalStateException("Ambiguous pass");
                    pass=p;
                }
            }
            if(pass==null) return;
            if(!settings.equals(PhotonBridge.read())){finish("Photon settings changed; retry capture");return;}
            if(!PhotonCaptureContract.outputs((int[])field(pass,passType,"drawBuffers")))
                throw new IllegalStateException("Unexpected composite4 outputs");
            Object targets=field(renderer,composite,"renderTargets");
            Object color=targets.getClass().getMethod("get",int.class).invoke(targets,0);
            if(color==null) throw new IllegalStateException("Missing colortex0");
            Set<?> flipped=(Set<?>)field(pass,passType,"stageReadsFromAlt");
            boolean alt=flipped.contains(0);
            int colorId=number(call(color,alt?"getAltTexture":"getMainTexture"));
            int depthId=number(call(targets,"getDepthTexture"));
            var c=texture(colorId);var d=texture(depthId);
            if(!PhotonCaptureContract.colorFormat(c.format)||!PhotonCaptureContract.depthFormat(d.format))
                throw new IllegalStateException("Unknown input format");
            if(probeWidth>0&&!reprojectionProbe){
                if(TemporalProbe.pending())return;
                String result=TemporalProbe.run(program,colorId,depthId,probeWidth,probeHeight,PhotonTemporalMath.identity(),PhotonTemporalMath.identity(),false);
                if(--remaining==0)finish(result+" | series dispatched");
                return;
            }
            float[] mv=uniform(program,"gbufferModelView",GL20.GL_FLOAT_MAT4,16);
            float[] proj=uniform(program,"gbufferProjection",GL20.GL_FLOAT_MAT4,16);
            float[] pmv=uniform(program,"gbufferPreviousModelView",GL20.GL_FLOAT_MAT4,16);
            float[] pp=uniform(program,"gbufferPreviousProjection",GL20.GL_FLOAT_MAT4,16);
            float[] pos=uniform(program,"cameraPosition",GL20.GL_FLOAT_VEC3,3);
            float[] prev=uniform(program,"previousCameraPosition",GL20.GL_FLOAT_VEC3,3);
            float[] jitter=uniform(program,"taa_offset",GL20.GL_FLOAT_VEC2,2);
            float[] view=uniform(program,"view_res",GL20.GL_FLOAT_VEC2,2);
            // Iris can optimize out matrices used only via inverse uniforms.
            float[] invMv=uniform(program,"gbufferModelViewInverse",GL20.GL_FLOAT_MAT4,16);
            float[] invProj=uniform(program,"gbufferProjectionInverse",GL20.GL_FLOAT_MAT4,16);
            boolean camera=(mv!=null||invMv!=null)&&(proj!=null||invProj!=null)&&pmv!=null&&pp!=null&&pos!=null&&prev!=null;
            String temporal="Temporal math unavailable";
            float[] worldReprojection=null,handReprojection=null;
            PhotonTemporalMath.Extent extent=null;
            if(camera&&pmv!=null&&pp!=null&&pos!=null&&prev!=null&&jitter!=null&&view!=null){
                extent=PhotonTemporalMath.resolveExtent(view,settings.percent()/100f,c.w,c.h);
                var jitterNdc=PhotonTemporalMath.jitterNdc(jitter,settings.percent()/100f);
                worldReprojection=PhotonTemporalMath.currentToPreviousClipFromInverse(
                    invMv!=null?invMv:PhotonTemporalMath.invert(mv),invProj!=null?invProj:PhotonTemporalMath.invert(proj),pmv,pp,pos,prev);
                handReprojection=PhotonTemporalMath.currentToPreviousHandClipFromInverse(
                    invMv!=null?invMv:PhotonTemporalMath.invert(mv),invProj!=null?invProj:PhotonTemporalMath.invert(proj),pmv,pp);
                temporal="active extent="+extent.width()+"x"+extent.height()+" @ ("+extent.x()+","+extent.y()+")"
                    +" | jitter NDC="+show(jitterNdc)+" | world reprojection="+show(worldReprojection)
                    +" | hand reprojection="+show(handReprojection);
            }
            uniforms="Current MV="+show(mv)+" | Current Projection="+show(proj)
                +" | Inverse MV="+show(invMv)+" | Inverse Projection="+show(invProj)
                +" | Previous MV="+show(pmv)+" | Previous Projection="+show(pp)
                +" | Camera="+show(pos)+" | Previous camera="+show(prev)
                +" | Raw taa_offset="+show(jitter)+" | view_res="+show(view)+" | "+temporal;
            if(reprojectionProbe){
                reprojectionProbe=false;
                if(extent==null||worldReprojection==null||handReprojection==null)
                    throw new IllegalStateException("Photon camera reprojection uniforms incomplete");
                if(extent.width()>c.w||extent.height()>c.h)
                    throw new IllegalStateException("Photon active TAAU extent exceeds source");
                String result=TemporalProbe.run(program,colorId,depthId,extent.width(),extent.height(),
                    worldReprojection,handReprojection,true);
                finish("Photon camera reprojection dispatched | "+extent.width()+"x"+extent.height()
                    +" | "+result+" | static geometry + hand; dynamic objects unsupported");
                return;
            }
            finish("Photon composite4 candidate | colortex0 "+(alt?"alt":"main")+" "+c+" | depthtex0 candidate "+d
                +" | configured TAAU "+settings.percent()+"% | camera uniforms "+(camera?"present":"incomplete")
                +" | "+temporal+" | metadata only; no DLSS");
        } catch(java.io.IOException|ReflectiveOperationException|RuntimeException|LinkageError e) {
            finish("Capture rejected: "+e.getClass().getSimpleName()+": "+e.getMessage());
        }
    }
}