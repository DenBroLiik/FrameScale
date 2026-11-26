package by.xopilot.framescale.mixin;

import by.xopilot.framescale.PhotonCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Read-only, opt-in observation after Iris binds the actual program and uniforms. */
@Pseudo
@Mixin(targets="net.irisshaders.iris.pipeline.CompositeRenderer",remap=false)
abstract class PhotonCaptureMixin {
    @Inject(method="renderAll",at=@At(value="INVOKE",target="Lnet/irisshaders/iris/pathways/FullScreenQuadRenderer;renderQuad()V",remap=false),require=0)
    private void fs$capture(CallbackInfo ci) { PhotonCapture.observe(this); }
}
