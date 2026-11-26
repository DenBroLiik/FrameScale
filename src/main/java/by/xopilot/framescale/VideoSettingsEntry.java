package by.xopilot.framescale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;
import java.util.Locale;
import java.util.function.Function;

/** Best-effort UI entry, without linking to Sodium's private GUI classes. */
final class VideoSettingsEntry {
    static void add(ScreenEvent.Init.Post event,Function<Screen,Screen> factory) {
        Screen screen=event.getScreen();
        String name=screen.getClass().getName();
        String simple=screen.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        boolean sodium=(name.startsWith("net.caffeinemc.mods.sodium.") || name.startsWith("me.jellysquid.mods.sodium."))
            && (simple.contains("option") || simple.contains("config"));
        boolean mainOptions=name.equals("net.minecraft.client.gui.screens.OptionsScreen")
            || name.equals("net.minecraft.client.gui.screens.options.OptionsScreen");
        if (!(screen instanceof VideoSettingsScreen || sodium || mainOptions)) return;
        if (screen.width<330 || screen.height<100) return;
        int w=90,h=20,x=screen.width-w-6,y=5;
        // Do not replace, move or cover an existing standard widget.
        for(var listener:screen.children()) if(listener instanceof AbstractWidget widget
            && widget.visible && x<widget.getX()+widget.getWidth() && x+w>widget.getX()
            && y<widget.getY()+widget.getHeight() && y+h>widget.getY()) return;
        event.addListener(Button.builder(Component.literal("FrameScale"),
            b->Minecraft.getInstance().setScreen(factory.apply(screen))).bounds(x,y,w,h).build());
    }
}
