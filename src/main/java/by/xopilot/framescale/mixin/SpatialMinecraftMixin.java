package by.xopilot.framescale.mixin;

import by.xopilot.framescale.SpatialWorld;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class SpatialMinecraftMixin {
    @Inject(method="getMainRenderTarget",at=@At("HEAD"),cancellable=true,require=0)
    private void fs$scopedTarget(CallbackInfoReturnable<RenderTarget> cir) {
        SpatialWorld.getterSeen();
        RenderTarget target=SpatialWorld.scopedTarget();
        if(target!=null) cir.setReturnValue(target);
    }
    @Inject(method="close",at=@At("HEAD"),require=0)
    private void fs$close(CallbackInfo ci) { by.xopilot.framescale.PhotonCapture.shutdown(); SpatialWorld.shutdown(); }
}
