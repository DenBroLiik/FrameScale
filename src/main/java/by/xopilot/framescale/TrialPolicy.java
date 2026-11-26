package by.xopilot.framescale;

/** Lowering quality is provisional until two comparable, beneficial windows agree. */
final class TrialPolicy {
    enum Verdict { WAIT, ACCEPT, RESTORE }
    private int good,bad;
    private boolean invalid;
    void invalidate() { invalid=true; }
    Verdict observe(boolean comparable,boolean beneficial,boolean cooldownElapsed) {
        if(!comparable) invalid=true;
        if(invalid || bad>=2) return cooldownElapsed?Verdict.RESTORE:Verdict.WAIT;
        if(beneficial) { good++; bad=0; } else { bad++; good=0; }
        if(good>=2) return Verdict.ACCEPT;
        return bad>=2&&cooldownElapsed?Verdict.RESTORE:Verdict.WAIT;
    }
    boolean invalidated() { return invalid||bad>=2; }
}
