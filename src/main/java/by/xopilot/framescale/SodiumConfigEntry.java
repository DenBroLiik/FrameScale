package by.xopilot.framescale;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Native Sodium slider. No custom screen, widget or mixin on this path. */
public final class SodiumConfigEntry implements ConfigEntryPoint {
    static final ResourceLocation ID=ResourceLocation.fromNamespaceAndPath("framescale","render_scale");
    @Override public void registerConfigLate(ConfigBuilder builder) {
        var slider=builder.createIntegerOption(ID)
            .setName(Component.translatable("framescale.native.scale"))
            .setTooltip(v->NativeScaleOption.tooltip(v))
            .setStorageHandler(()->{})
            .setDefaultValue(NativeScaleOption.OFF)
            .setRange(NativeScaleOption.OFF,100,1)
            .setBinding(NativeScaleOption::save,NativeScaleOption::load)
            .setValueFormatter(NativeScaleOption::format)
            .setEnabledProvider(state->NativeScaleOption.available(),ConfigState.UPDATE_ON_REBUILD);
        builder.registerOwnModOptions().addPage(builder.createOptionPage()
            .setName(Component.literal("FrameScale")).addOption(slider));
    }
}
