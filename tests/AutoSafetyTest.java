package by.xopilot.framescale;

import java.nio.file.Files;
public final class AutoSafetyTest {
    private static void check(boolean v) { if(!v) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        var s=new SceneGuard(0,0,0,179,10);
        check(s.comparable(new SceneGuard(5,0,0,-179,12)));
        check(!s.comparable(new SceneGuard(9,0,0,179,10)));
        check(!s.comparable(new SceneGuard(0,0,0,90,10)));
        var p=new TrialPolicy();
        check(p.observe(true,true,false)==TrialPolicy.Verdict.WAIT);
        check(p.observe(true,true,false)==TrialPolicy.Verdict.ACCEPT);
        p=new TrialPolicy();
        check(p.observe(true,false,false)==TrialPolicy.Verdict.WAIT);
        check(p.observe(true,false,false)==TrialPolicy.Verdict.WAIT);
        check(p.observe(true,true,true)==TrialPolicy.Verdict.RESTORE);
        p=new TrialPolicy();p.invalidate();
        check(p.observe(true,true,false)==TrialPolicy.Verdict.WAIT);
        check(p.observe(true,true,true)==TrialPolicy.Verdict.RESTORE);
        var d=Files.createTempDirectory("framescale-test"); var file=d.resolve("prefs.properties");
        try {
            check(AutoPreferences.load(file).equals(AutoPreferences.DEFAULT));
            var custom=new AutoPreferences(90,85);custom.save(file);
            check(AutoPreferences.load(file).equals(custom));
            Files.writeString(file,"target=wrong\nfloor=200\n");
            check(AutoPreferences.load(file).equals(AutoPreferences.DEFAULT));
        } finally { Files.deleteIfExists(file);Files.deleteIfExists(d); }
        System.out.println("AutoSafety tests PASS");
    }
}
