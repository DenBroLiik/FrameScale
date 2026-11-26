package by.xopilot.framescale.mixin;

import by.xopilot.framescale.PassProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional observer only: no redirects, cancellations, local capture or modifications to arguments. */
@Pseudo
@Mixin(targets="net.irisshaders.iris.pipeline.IrisRenderingPipeline",remap=false)
abstract class IrisPipelineMixin {
    @Inject(method="beginLevelRendering",at=@At("HEAD"),require=0)
    private void fs$begin(CallbackInfo ci) { PassProfiler.mark(0); }
    @Inject(method="beginLevelRendering",at=@At("RETURN"),require=0)
    private void fs$beginEnd(CallbackInfo ci) { PassProfiler.mark(1); }
    @Inject(method="renderShadows",at=@At("HEAD"),require=0)
    private void fs$shadow(CallbackInfo ci) { PassProfiler.mark(2); }
    @Inject(method="renderShadows",at=@At(value="INVOKE",target="Lnet/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V",remap=false),require=0)
    private void fs$prepare(CallbackInfo ci) { PassProfiler.mark(3); }
    @Inject(method="renderShadows",at=@At("RETURN"),require=0)
    private void fs$shadowEnd(CallbackInfo ci) { PassProfiler.mark(4); }
    @Inject(method="beginHand",at=@At("HEAD"),require=0)
    private void fs$hand(CallbackInfo ci) { PassProfiler.mark(5); }
    @Inject(method="beginTranslucents",at=@At("HEAD"),require=0)
    private void fs$deferred(CallbackInfo ci) { PassProfiler.mark(6); }
    @Inject(method="beginTranslucents",at=@At("RETURN"),require=0)
    private void fs$translucent(CallbackInfo ci) { PassProfiler.mark(7); }
    @Inject(method="finalizeLevelRendering",at=@At("HEAD"),require=0)
    private void fs$composite(CallbackInfo ci) { PassProfiler.mark(8); }
    @Inject(method="finalizeLevelRendering",at=@At(value="INVOKE",target="Lnet/irisshaders/iris/pipeline/FinalPassRenderer;renderFinalPass()V",remap=false),require=0)
    private void fs$final(CallbackInfo ci) { PassProfiler.mark(9); }
    @Inject(method="finalizeLevelRendering",at=@At("RETURN"),require=0)
    private void fs$end(CallbackInfo ci) { PassProfiler.mark(10); }
}
