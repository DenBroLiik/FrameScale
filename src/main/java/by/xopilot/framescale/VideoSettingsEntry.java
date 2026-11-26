package by.xopilot.framescale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;
import java.util.function.Function;

/** Vanilla fallback; Sodium uses its registered external page instead. */
final class VideoSettingsEntry {
    static void add(ScreenEvent.Init.Post event,Function<Screen,Screen> factory) {
        if (CompatInventory.loaded().containsKey("sodium")) return;
        Screen screen=event.getScreen();
        if (!(screen instanceof VideoSettingsScreen) || screen.width<330 || screen.height<100) return;
        int w=90,h=20,x=screen.width-w-6,y=5;
        for(var listener:screen.children()) if(listener instanceof AbstractWidget widget
            && widget.visible && x<widget.getX()+widget.getWidth() && x+w>widget.getX()
            && y<widget.getY()+widget.getHeight() && y+h>widget.getY()) return;
        event.addListener(Button.builder(Component.literal("FrameScale"),
            b->Minecraft.getInstance().setScreen(factory.apply(screen))).bounds(x,y,w,h).build());
    }
}
