package by.xopilot.framescale;

import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** One-shot private-output diagnostic. Never replaces the Iris draw or feeds NGX. */
final class TemporalProbe {
    private static final int[] FORMATS={0x881a,0x822e,0x822f,0x8232};
    private static int start,end,program,width,height;
    private static final int[] outputs=new int[4];
    private static final FrameStats samples=new FrameStats(120);
    static void resetSamples(){samples.clear();}
    static void release(){cleanup();for(int i=0;i<4;i++){if(outputs[i]!=0)GL11.glDeleteTextures(outputs[i]);outputs[i]=0;}if(program!=0)GL20.glDeleteProgram(program);program=width=height=0;}
    private static long deadline;
    private static String status="Not run";
    static String status(){return status;}
    static boolean pending(){return end!=0;}
    static void tick(){
        if(!pending())return;
        try {
            if(GL15.glGetQueryObjecti(end,GL15.GL_QUERY_RESULT_AVAILABLE)!=0
                &&GL15.glGetQueryObjecti(start,GL15.GL_QUERY_RESULT_AVAILABLE)!=0){
                long ns=GL33.glGetQueryObjectui64(end,GL15.GL_QUERY_RESULT)-GL33.glGetQueryObjectui64(start,GL15.GL_QUERY_RESULT);
                if(ns>=0)samples.add(ns/1e6);
                var snap=samples.snapshot();
                status=String.format(java.util.Locale.ROOT,"%d samples | mean %.3f ms | p95 %.3f ms | identity motion, no DLSS",snap.count(),snap.meanMs(),snap.p95Ms());
                cleanup();
            }else if(System.nanoTime()-deadline>=0){status="GPU timing expired";cleanup();}
        }catch(RuntimeException|LinkageError e){status="Timing unavailable";cleanup();}
    }
    static void cleanup(){
        if(start!=0)GL15.glDeleteQueries(start);
        if(end!=0)GL15.glDeleteQueries(end);
        start=end=0;
    }
    private record Binding(int name,int level,boolean layered,int layer,int access,int format){
        static Binding read(int u){return new Binding(GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME,u),
            GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LEVEL,u),GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYERED,u)!=0,
            GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYER,u),GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_ACCESS,u),
            GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_FORMAT,u));}
        void restore(int u){GL42.glBindImageTexture(u,name,level,layered,layer,access,format);}
    }
    private static int samplerUnit(int program,String name,int expected){
        int loc=GL20.glGetUniformLocation(program,name);
        if(loc<0)loc=GL20.glGetUniformLocation(program,"iris_"+name);
        if(loc<0)throw new IllegalStateException("Missing active sampler "+name);
        int unit=GL20.glGetUniformi(program,loc);
        if(unit<0||unit>=GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS))throw new IllegalStateException("Sampler unit invalid");
        int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        try{
            GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);
            if(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)!=expected)throw new IllegalStateException("Sampler texture mismatch: "+name);
            int sampler=GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,unit);
            int compare=sampler==0?GL45.glGetTextureParameteri(expected,GL14.GL_TEXTURE_COMPARE_MODE)
                :GL33.glGetSamplerParameteri(sampler,GL14.GL_TEXTURE_COMPARE_MODE);
            if(compare!=GL11.GL_NONE)throw new IllegalStateException("Comparison sampler unsupported");
        }finally{GL13.glActiveTexture(active);}
        return unit;
    }
    static String run(int irisProgram,int color,int depth,int w,int h,float[] worldReprojection,float[] handReprojection,boolean cameraOnly) throws IOException {
        RenderSystem.assertOnRenderThread();
        if(pending())throw new IllegalStateException("Timing pending");
        if(!PhotonCaptureContract.extent(w,h)||(long)w*h*17>128L*1024*1024)
            throw new IllegalArgumentException("Diagnostic allocation exceeds budget");
        if(!PhotonCaptureContract.finite(worldReprojection,16)||!PhotonCaptureContract.finite(handReprojection,16))
            throw new IllegalArgumentException("Invalid reprojection matrices");
        if(GL11.glGetInteger(GL42.GL_MAX_IMAGE_UNITS)<4)throw new IllegalStateException("Four image units required");
        for(int tex:new int[]{color,depth})
            if(GL45.glGetTextureLevelParameteri(tex,0,GL11.GL_TEXTURE_WIDTH)<w
                ||GL45.glGetTextureLevelParameteri(tex,0,GL11.GL_TEXTURE_HEIGHT)<h)
                throw new IllegalArgumentException("Explicit crop outside source");
        int cu=samplerUnit(irisProgram,"colortex0",color),du=samplerUnit(irisProgram,"depthtex0",depth);
        if(cu==du)throw new IllegalStateException("Aliased sampler units");
        int shader=0;Binding[] saved=new Binding[4];
        for(int i=0;i<4;i++)saved[i]=Binding.read(i);
        try {
            if(width!=w||height!=h){release();width=w;height=h;}
            if(program==0)try(var in=TemporalProbe.class.getResourceAsStream("/assets/framescale/shaders/temporal_inputs.comp")){
            if(in==null)throw new IOException("Missing temporal shader");
            shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
            GL20.glShaderSource(shader,new String(in.readAllBytes(),StandardCharsets.UTF_8));GL20.glCompileShader(shader);
            if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
            program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);GL20.glLinkProgram(program);
            if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
            }
            for(int i=0;i<4;i++){
                if(outputs[i]==0){outputs[i]=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
                GL45.glTextureStorage2D(outputs[i],1,FORMATS[i],w,h);
                if(outputs[i]==0||GL45.glGetTextureLevelParameteri(outputs[i],0,GL11.GL_TEXTURE_WIDTH)!=w)
                    throw new IllegalStateException("Output allocation failed");}
                GL42.glBindImageTexture(i,outputs[i],0,false,0,GL15.GL_WRITE_ONLY,FORMATS[i]);
                if(GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME,i)!=outputs[i])throw new IllegalStateException("Image binding failed");
            }
            GL20.glUseProgram(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"sceneColor"),cu);
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"sceneDepth"),du);
            GL20.glUniform2i(GL20.glGetUniformLocation(program,"sourceOrigin"),0,0);
            GL20.glUniform2i(GL20.glGetUniformLocation(program,"renderExtent"),w,h);
            int worldLoc=GL20.glGetUniformLocation(program,"currentToPreviousClip");
            int handLoc=GL20.glGetUniformLocation(program,"currentToPreviousHandClip");
            if(worldLoc<0||handLoc<0)throw new IllegalStateException("Reprojection uniforms missing");
            try(var stack=MemoryStack.stackPush()){
                var world=stack.floats(worldReprojection);
                var hand=stack.floats(handReprojection);
                GL20.glUniformMatrix4fv(worldLoc,false,world);
                GL20.glUniformMatrix4fv(handLoc,false,hand);
            }
            int gx=(w+7)/8,gy=(h+7)/8;
            if(gx>GL30.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT,0)||gy>GL30.glGetIntegeri(GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT,1))
                throw new IllegalStateException("Dispatch exceeds limits");
            start=GL15.glGenQueries();end=GL15.glGenQueries();
            if(start==0||end==0)throw new IllegalStateException("Timestamp allocation failed");
            GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT|GL42.GL_FRAMEBUFFER_BARRIER_BIT|GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            GL33.glQueryCounter(start,GL33.GL_TIMESTAMP);
            GL43.glDispatchCompute(gx,gy,1);
            GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
            GL33.glQueryCounter(end,GL33.GL_TIMESTAMP);
            deadline=System.nanoTime()+5_000_000_000L;
            status="Dispatched "+w+"x"+h+" private outputs; timing pending; "+(cameraOnly?"camera-only reprojection":"identity reprojection");
            return status;
        }catch(IOException|RuntimeException|LinkageError e){status="Diagnostic rejected";throw e;}
        finally{
            for(int i=3;i>=0;i--)saved[i].restore(i);
            GL20.glUseProgram(irisProgram);
            if(status.equals("Diagnostic rejected"))release();
            if(shader!=0)GL20.glDeleteShader(shader);
        }
    }
}
