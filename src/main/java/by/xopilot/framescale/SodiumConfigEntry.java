package by.xopilot.framescale;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public final class SodiumConfigEntry implements ConfigEntryPoint {
    static final ResourceLocation ID=ResourceLocation.fromNamespaceAndPath("framescale","render_scale");
    static final ResourceLocation TARGET=ResourceLocation.fromNamespaceAndPath("framescale","target_fps");
    static final ResourceLocation FLOOR=ResourceLocation.fromNamespaceAndPath("framescale","quality_floor");
    static final ResourceLocation EFFECTS=ResourceLocation.fromNamespaceAndPath("framescale","effect_budget");
    @Override public void registerConfigLate(ConfigBuilder builder) {
        var slider=builder.createIntegerOption(ID)
            .setName(Component.translatable("framescale.native.scale"))
            .setTooltip(NativeScaleOption::tooltip)
            .setStorageHandler(()->{})
            .setDefaultValue(NativeScaleOption.OFF)
            .setRange(NativeScaleOption.AUTO,100,1)
            .setBinding(NativeScaleOption::save,NativeScaleOption::load)
            .setValueFormatter(NativeScaleOption::format)
            .setEnabledProvider(state->NativeScaleOption.available(),ConfigState.UPDATE_ON_REBUILD);
        var target=builder.createIntegerOption(TARGET)
            .setName(Component.translatable("framescale.auto.target"))
            .setTooltip(Component.translatable("framescale.auto.target.tip"))
            .setStorageHandler(()->{}).setDefaultValue(AutoQuality.DEFAULT_TARGET)
            .setRange(30,240,1).setBinding(FrameScale::autoTarget,FrameScale::autoTarget)
            .setValueFormatter(v->Component.literal(v+" FPS"));
        var floor=builder.createIntegerOption(FLOOR)
            .setName(Component.translatable("framescale.auto.floor"))
            .setTooltip(Component.translatable("framescale.auto.floor.tip"))
            .setStorageHandler(()->{}).setDefaultValue(AutoQuality.DEFAULT_FLOOR)
            .setRange(50,100,1).setBinding(FrameScale::autoFloor,FrameScale::autoFloor)
            .setValueFormatter(v->Component.literal(v+"%"));
        var effects=builder.createIntegerOption(EFFECTS)
            .setName(Component.translatable("framescale.effects.name"))
            .setTooltip(EffectControls::tooltip)
            .setStorageHandler(()->{}).setDefaultValue(0)
            .setRange(0,SamplingBudget.Preset.values().length-1,1)
            .setBinding(EffectControls::save,EffectControls::load)
            .setValueFormatter(EffectControls::format)
            .setEnabledProvider(state->EffectControls.available(),ConfigState.UPDATE_ON_REBUILD);
        builder.registerOwnModOptions().addPage(builder.createOptionPage()
            .setName(Component.literal("FrameScale"))
            .addOption(slider).addOption(target).addOption(floor).addOption(effects));
    }
}
