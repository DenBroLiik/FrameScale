package by.xopilot.framescale;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Registered by Sodium's public metadata entrypoint; never loaded by FrameScale itself. */
public final class SodiumConfigEntry implements ConfigEntryPoint {
    @Override public void registerConfigLate(ConfigBuilder builder) {
        builder.registerOwnModOptions().addPage(builder.createExternalPage()
            .setName(Component.translatable("framescale.title"))
            .setScreenConsumer(parent -> Minecraft.getInstance().setScreen(FrameScale.createSettings(parent))));
    }
}
