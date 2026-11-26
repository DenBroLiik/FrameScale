package by.xopilot.framescale;

/** Reject duplicate, omitted and out-of-order markers instead of mixing unrelated frame intervals. */
final class PassSequence {
    private int next;
    private boolean invalid;
    void reset() { next=0; invalid=false; }
    boolean accept(int marker) {
        if(invalid||marker!=next||marker>=PassMetrics.MARKS) { invalid=true; return false; }
        next++; return true;
    }
    boolean complete() { return !invalid&&next==PassMetrics.MARKS; }
}
