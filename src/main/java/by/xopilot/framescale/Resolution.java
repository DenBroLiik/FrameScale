package by.xopilot.framescale;

/** Pixel dimensions, never GUI-scaled coordinates. */
record Resolution(int width,int height) {
    Resolution { if(width<1||height<1) throw new IllegalArgumentException("Invalid framebuffer size"); }
    Resolution scaled(int percent) {
        if(percent<50||percent>100) throw new IllegalArgumentException("Invalid scale");
        return new Resolution(Math.max(1,(int)((long)width*percent/100)),Math.max(1,(int)((long)height*percent/100)));
    }
    String label() { return width+"×"+height; }
    String transition(int percent) { return scaled(percent).label()+" → "+label(); }
}
