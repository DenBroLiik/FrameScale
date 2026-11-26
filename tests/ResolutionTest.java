package by.xopilot.framescale;
public class ResolutionTest {
    public static void main(String[] args) {
        check(new Resolution(1920,1200).transition(75).equals("1440×900 → 1920×1200"));
        check(new Resolution(1920,1080).transition(75).equals("1440×810 → 1920×1080"));
        check(new Resolution(2560,1600).scaled(100).equals(new Resolution(2560,1600)));
        check(new Resolution(1279,719).scaled(67).equals(new Resolution(856,481)));
        check(new Resolution(1,1).scaled(50).equals(new Resolution(1,1)));
        try { new Resolution(0,1); throw new AssertionError(); } catch(IllegalArgumentException expected) {}
        System.out.println("Resolution tests PASS");
    }
    private static void check(boolean b) { if(!b) throw new AssertionError(); }
}
