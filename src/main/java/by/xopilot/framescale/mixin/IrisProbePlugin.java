package by.xopilot.framescale.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;
import java.util.List;
import java.util.Set;

/** Probe the optional Iris class without loading it. Never applies on an unknown pipeline shape. */
public final class IrisProbePlugin implements IMixinConfigPlugin {
    public static volatile boolean accepted;
    public static volatile boolean captureAccepted;
    private static final String COMPOSITE="net.irisshaders.iris.pipeline.CompositeRenderer";
    private static final String TARGET="net.irisshaders.iris.pipeline.IrisRenderingPipeline";
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String target,String mixin) {
        if(COMPOSITE.equals(target)&&mixin.endsWith(".PhotonCaptureMixin")) {
            try {
                var node=MixinService.getService().getBytecodeProvider().getClassNode(target);
                var method=find(node,"renderAll","()V");
                if(method==null||!singleCall(method,"net/irisshaders/iris/pathways/FullScreenQuadRenderer","renderQuad")) return false;
                boolean passes=node.fields.stream().anyMatch(f->f.name.equals("passes")&&f.desc.equals("Lcom/google/common/collect/ImmutableList;"));
                boolean targets=node.fields.stream().anyMatch(f->f.name.equals("renderTargets")&&f.desc.equals("Lnet/irisshaders/iris/targets/RenderTargets;"));
                if(!passes||!targets) return false;
                var pass=MixinService.getService().getBytecodeProvider().getClassNode(COMPOSITE+"$Pass");
                for(String name:List.of("name","program","stageReadsFromAlt","drawBuffers"))
                    if(pass.fields.stream().noneMatch(f->f.name.equals(name))) return false;
                captureAccepted=true;return true;
            } catch(Exception|LinkageError e) { return false; }
        }
        if(!TARGET.equals(target)) return false;
        try {
            var node=MixinService.getService().getBytecodeProvider().getClassNode(target);
            for(String name:List.of("beginLevelRendering","beginHand","beginTranslucents","finalizeLevelRendering"))
                if(find(node,name,"()V")==null) return false;
            MethodNode shadows=null;
            for(var m:node.methods) if(m.name.equals("renderShadows")&&m.desc.startsWith("(L")&&m.desc.endsWith("Camera;)V")) {
                if(shadows!=null) return false;shadows=m;
            }
            if(shadows==null||!singleCall(shadows,"net/irisshaders/iris/pipeline/CompositeRenderer","renderAll")) return false;
            if(!singleCall(find(node,"finalizeLevelRendering","()V"),"net/irisshaders/iris/pipeline/FinalPassRenderer","renderFinalPass")) return false;
            accepted=true;return true;
        } catch(Exception|LinkageError e) { return false; }
    }
    private static MethodNode find(ClassNode node,String name,String desc) {
        for(var m:node.methods) if(m.name.equals(name)&&m.desc.equals(desc)) return m;
        return null;
    }
    private static boolean singleCall(MethodNode method,String owner,String name) {
        int count=0;
        for(var insn:method.instructions) if(insn instanceof MethodInsnNode call
            &&call.owner.equals(owner)&&call.name.equals(name)&&call.desc.equals("()V")) count++;
        return count==1;
    }
    @Override public void acceptTargets(Set<String> mine,Set<String> others) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target,ClassNode node,String mixin,IMixinInfo info) {}
    @Override public void postApply(String target,ClassNode node,String mixin,IMixinInfo info) {}
}
