package by.xopilot.framescale;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.List;

/** Sodium external page with flat controls and automatic application. */
final class FrameScaleScreen extends Screen {
    private static final int PANEL=0xE51C2026, ROW=0xFF282E35, HOVER=0xFF38434D, GREEN=0xFF66D8A4;
    private final Screen parent;
    private final Runnable stopProfiler;
    private final ApplyScheduler scheduler=new ApplyScheduler();
    private PhotonBridge.Settings baseline;
    private boolean loaded,enabled,error,applying;
    private int percent,left,top,panelWidth;
    private Component status=Component.empty();
    private Button toggle;
    private ScaleSlider slider;

    FrameScaleScreen(Screen parent,Runnable stopProfiler) {
        super(Component.translatable("framescale.title"));
        this.parent=parent; this.stopProfiler=stopProfiler;
    }
    @Override protected void init() {
        if(!loaded) refresh();
        panelWidth=Math.min(460,width-16);
        left=(width-panelWidth)/2; top=Math.max(4,(height-172)/2);
        int x=left+8,w=panelWidth-16,y=top+24;
        toggle=null; slider=null;
        if(baseline!=null && height>=148) {
            toggle=addRenderableWidget(new FlatButton(x,y,w,20,toggleLabel(),b->{
                enabled=!enabled; updateControls(); if(slider!=null) slider.sync(); changed();
            }));
            slider=addRenderableWidget(new ScaleSlider(x,y+24,w,baseline.allowed()));
            int gap=3,pw=(w-3*gap)/4; int[] presets={100,85,75,67};
            for(int i=0;i<presets.length;i++) {
                int p=presets[i];
                Button preset=addRenderableWidget(new FlatButton(x+i*(pw+gap),y+48,pw,20,Component.literal(p+"%"),b->{
                    percent=p; slider.sync(); changed();
                }));
                preset.active=baseline.allowed().contains(p);
            }
        } else {
            addRenderableWidget(new FlatButton(x,y,w,20,Component.translatable("framescale.refresh"),b->{refresh(); rebuildWidgets();}));
        }
        addRenderableWidget(new FlatButton(x,height-24,w,20,Component.translatable("gui.back"),b->onClose()));
    }
    private Component toggleLabel() { return Component.translatable(enabled?"framescale.enabled":"framescale.disabled"); }
    private void updateControls() { if(toggle!=null) toggle.setMessage(toggleLabel()); }
    private void changed() {
        if(baseline==null||applying) return;
        if(baseline.enabled()==enabled && baseline.percent()==percent && (!enabled || baseline.taa())) {
            scheduler.clearPending(); status=Component.translatable("framescale.applied"); return;
        }
        scheduler.changed(); error=false; status=Component.translatable("framescale.pending");
    }
    @Override public void tick() {
        if(scheduler.ready()) applyChanges();
    }
    private void refresh() {
        loaded=true; baseline=null; scheduler.cancel();
        var d=CompatPolicy.evaluate(CompatInventory.loaded());
        if(!d.scale()) { error=true; status=Component.literal(d.reason()); return; }
        try {
            baseline=PhotonBridge.read(); enabled=baseline.enabled(); percent=baseline.percent();
            error=false; status=Component.translatable("framescale.ready",baseline.pack());
        } catch(ReflectiveOperationException|RuntimeException|LinkageError e) { fail(e); }
    }
    private void fail(Throwable e) {
        error=true;
        String detail=e.getMessage();
        status=Component.translatable("framescale.error",detail==null?e.getClass().getSimpleName():detail);
    }
    private boolean applyChanges() {
        scheduler.cancel();
        if(baseline==null||applying) return false;
        var decision=CompatPolicy.evaluate(CompatInventory.loaded());
        if(!decision.scale()) { refresh(); rebuildWidgets(); return false; }
        if(baseline.enabled()==enabled && baseline.percent()==percent && (!enabled||baseline.taa())) return true;
        applying=true;
        try {
            stopProfiler.run();
            baseline=PhotonBridge.apply(baseline,enabled,percent);
            enabled=baseline.enabled(); percent=baseline.percent();
            updateControls(); if(slider!=null) slider.sync();
            error=false; status=Component.translatable("framescale.applied");
            return true;
        } catch(ReflectiveOperationException|RuntimeException|LinkageError e) {
            refresh(); fail(e); rebuildWidgets(); return false;
        } finally { applying=false; }
    }
    @Override public void render(GuiGraphics g,int mx,int my,float pt) {
        super.render(g,mx,my,pt);
        g.drawString(font,title,left+8,top+7,0xFFFFFF,false);
        g.fill(left+8,top+19,left+panelWidth-8,top+20,GREEN);
        int y=baseline!=null&&height>=148?top+98:top+49;
        if(y+8<height-26) {
            g.enableScissor(left+8,y,left+panelWidth-8,height-26);
            g.drawWordWrap(font,status,left+8,y,panelWidth-16,error?0xFFFFAAAA:0xFFB4C6C4);
            g.disableScissor();
        }
    }
    @Override public void renderBackground(GuiGraphics g,int mx,int my,float pt) {
        g.fill(0,0,width,height,0xF0171A1F);
        g.fill(left,top,left+panelWidth,height-2,PANEL);
    }
    @Override public void onClose() {
        if(applying) return;
        if(scheduler.pending() && !applyChanges()) return;
        if(minecraft!=null) minecraft.setScreen(parent);
    }
    @Override public void removed() { scheduler.cancel(); }
    private final class FlatButton extends Button {
        FlatButton(int x,int y,int w,int h,Component text,OnPress press) {
            super(x,y,w,h,text,press,DEFAULT_NARRATION);
        }
        @Override protected void renderWidget(GuiGraphics g,int mx,int my,float pt) {
            int x=getX(),y=getY();
            g.fill(x,y,x+getWidth(),y+getHeight(),isHoveredOrFocused()&&active?HOVER:ROW);
            if(isFocused()) g.fill(x,y,x+2,y+getHeight(),GREEN);
            g.drawCenteredString(font,getMessage(),x+getWidth()/2,y+(getHeight()-8)/2,active?0xFFFFFF:0x888888);
        }
    }
    private final class ScaleSlider extends AbstractSliderButton {
        private final List<Integer> values;
        ScaleSlider(int x,int y,int w,List<Integer> values) {
            super(x,y,w,20,Component.empty(),position(values,percent));
            this.values=List.copyOf(values); updateMessage();
        }
        void sync() { value=position(values,percent); updateMessage(); }
        @Override public void onClick(double mx,double my) { scheduler.beginDrag(); super.onClick(mx,my); }
        @Override public void onRelease(double mx,double my) { super.onRelease(mx,my); scheduler.endDrag(); }
        @Override protected void updateMessage() { setMessage(Component.literal(NativeScaleOption.transition(enabled?percent:NativeScaleOption.OFF))); }
        @Override protected void applyValue() {
            int next=values.get((int)Math.round(value*(values.size()-1)));
            if(next!=percent) { percent=next; changed(); }
        }

    }
    private static double position(List<Integer> values,int p) {
        return values.size()<=1?0:Math.max(0,values.indexOf(p))/(double)(values.size()-1);
    }
}
