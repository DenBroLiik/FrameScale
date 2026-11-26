package by.xopilot.framescale;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.List;

/** Draft changes are local to this screen until Apply; no render hooks or framebuffer replacement. */
final class FrameScaleScreen extends Screen {
    private final Screen parent;
    private final Runnable stopProfiler;
    private PhotonBridge.Settings baseline;
    private boolean loaded, enabled;
    private int percent;
    private Component status = Component.empty();
    private boolean error;
    private Button toggle, apply;
    private ScaleSlider slider;

    FrameScaleScreen(Screen parent, Runnable stopProfiler) {
        super(Component.translatable("framescale.title"));
        this.parent=parent; this.stopProfiler=stopProfiler;
    }
    @Override protected void init() {
        if (!loaded) refresh();
        int w=Math.min(300,width-20), x=(width-w)/2, y=30;
        if (baseline!=null) {
            toggle=addRenderableWidget(Button.builder(toggleLabel(),b->{
                enabled=!enabled; updateControls();
            }).bounds(x,y,w,20).build());
            slider=addRenderableWidget(new ScaleSlider(x,y+24,w,baseline.allowed()));
            int gap=4, pw=(w-3*gap)/4;
            int[] presets={100,85,75,67};
            for(int i=0;i<presets.length;i++){
                int p=presets[i];
                Button preset=addRenderableWidget(Button.builder(Component.literal(p+"%"),b->{
                    percent=p; slider.sync(); updateControls();
                }).bounds(x+i*(pw+gap),y+48,pw,20).build());
                preset.active=baseline.allowed().contains(p);
            }
            apply=addRenderableWidget(Button.builder(Component.translatable("framescale.apply"),b->applyChanges())
                .bounds(x,y+72,w,20).build());
            updateControls();
        } else {
            addRenderableWidget(Button.builder(Component.translatable("framescale.refresh"),b->{refresh(); rebuildWidgets();})
                .bounds(x,y,w,20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.back"),b->onClose())
            .bounds(x,Math.max(y+96,height-26),w,20).build());
    }
    private Component toggleLabel() { return Component.translatable(enabled?"framescale.enabled":"framescale.disabled"); }
    private void refresh() {
        loaded=true; baseline=null;
        var decision=CompatPolicy.evaluate(CompatInventory.loaded());
        if (!decision.scale()) { error=true; status=Component.literal(decision.reason()); return; }
        try {
            baseline=PhotonBridge.read(); enabled=baseline.enabled(); percent=baseline.percent();
            error=false; status=Component.translatable("framescale.ready",baseline.pack());
        } catch (ReflectiveOperationException|RuntimeException|LinkageError e) { fail(e); }
    }
    private void fail(Throwable e) {
        error=true;
        String detail=e.getMessage();
        status=Component.translatable("framescale.error",detail==null?e.getClass().getSimpleName():detail);
    }
    private void updateControls() {
        if (baseline==null || apply==null) return;
        toggle.setMessage(toggleLabel());
        apply.active=baseline.enabled()!=enabled || baseline.percent()!=percent || (enabled&&!baseline.taa());
    }
    private void applyChanges() {
        var decision=CompatPolicy.evaluate(CompatInventory.loaded());
        if(!decision.scale()) { error=true; status=Component.literal(decision.reason()); apply.active=false; return; }
        try {
            stopProfiler.run();
            baseline=PhotonBridge.apply(baseline,enabled,percent);
            enabled=baseline.enabled(); percent=baseline.percent();
            error=false; status=Component.translatable("framescale.applied");
            updateControls();
        } catch (ReflectiveOperationException|RuntimeException|LinkageError e) {
            refresh(); fail(e); rebuildWidgets();
        }
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
        super.render(graphics,mouseX,mouseY,partialTick);
        graphics.drawCenteredString(font,title,width/2,10,0xFFFFFF);
        int w=Math.min(300,width-20), x=(width-w)/2;
        int y=baseline==null?58:126;
        if (y+10<height-26) graphics.drawWordWrap(font,status,x,y,w,error?0xFFAAAA:0xB8E6B8);
    }
    @Override public void onClose() { if(minecraft!=null) minecraft.setScreen(parent); }
    private final class ScaleSlider extends AbstractSliderButton {
        private final List<Integer> values;
        ScaleSlider(int x,int y,int w,List<Integer> values) {
            super(x,y,w,20,Component.empty(),position(values,percent));
            this.values=List.copyOf(values); updateMessage();
        }
        private void sync() { value=position(values,percent); updateMessage(); }
        @Override protected void updateMessage() {
            setMessage(Component.translatable("framescale.scale",percent));
        }
        @Override protected void applyValue() {
            percent=values.get((int)Math.round(value*(values.size()-1)));
            updateControls();
        }
    }
    static double position(List<Integer> values,int p) {
        return values.size()<=1?0:Math.max(0,values.indexOf(p))/(double)(values.size()-1);
    }
}
