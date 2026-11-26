package by.xopilot.framescale;

/** Coalesces edits; a held slider cannot trigger expensive shader compilation. */
final class ApplyScheduler {
    private int remaining;
    private boolean pending, dragging;
    void changed() { pending=true; remaining=6; }
    void beginDrag() { dragging=true; }
    void endDrag() { dragging=false; if(pending) remaining=1; }
    void clearPending() { pending=false; remaining=0; }
    void cancel() { clearPending(); dragging=false; }
    boolean pending() { return pending; }
    boolean ready() {
        if(!pending || dragging) return false;
        if(--remaining>0) return false;
        pending=false;
        return true;
    }
}
