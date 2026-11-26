package by.xopilot.framescale.mixin;

import by.xopilot.framescale.SpatialWorld;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(GameRenderer.class)
abstract class SpatialGameRendererMixin {
    @WrapMethod(method="renderLevel",require=0)
    private void fs$world(DeltaTracker delta,Operation<Void> original) {
        SpatialWorld.wrapperSeen();
        SpatialWorld.render(()->original.call(delta));
    }
}
