"""Run the glass island's motion (LensMotion, the springs and gestures of lens.html) on a plain JVM."""
from pathlib import Path
import unittest
import test_settings_safety_runtime as safety

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'inject/java/cat/ttcuz/framework/LensMotion.java'

HARNESS = r'''package cat.ttcuz.framework;
public final class LensHarness {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    static void near(float a, float b, String what) { check(Math.abs(a - b) < .05f, what + ": " + a + " != " + b); }
    static void run(LensMotion m, float seconds) { for (float t = 0; t < seconds; t += 1 / 60f) m.step(1 / 60f); }
    public static void main(String[] args) {
        LensMotion m = new LensMotion();
        // the demo's bar: 453 x 68, 10 padding, a 86.6 cell, a 99 lens
        near(m.cell(), 86.6f, "cell"); near(m.lensWidth(), 99f, "lens"); near(m.centre(0), 53.3f, "first centre");
        m.settle(); near(m.x, m.centre(0), "rest on home"); check(m.shown() == 0, "home chosen");
        // touch Inbox: the lens grows and goes there, the tabs lose their accent while it covers them
        check(m.down(m.centre(3)) == -1 && m.dragging && m.sel == 3 && m.buzz == 10, "held on inbox"); m.buzz = 0;
        run(m, .6f); check(Math.abs(m.x - m.centre(3)) < .5f, "lens on inbox"); check(m.g > .9f && m.covered && m.shown() == -1, "grown and covering");
        check(m.lit, "its shadow is on");
        m.move(m.centre(2)); check(m.sel == 3 && m.buzz == 0, "the create button is never chosen while dragging");
        m.move(m.centre(4)); check(m.sel == 4 && m.buzz == 6, "crossing a tab ticks");
        check(m.up() == 4 && !m.dragging, "lands on profile");
        run(m, .1f); check(m.tg == 1f, "still grown 100 ms after the release");
        run(m, 1.5f); near(m.x, m.centre(4), "settled on profile"); near(m.g, 0, "flat again"); check(m.shown() == 4 && !m.lit && m.shade == 0, "profile chosen, no shadow");
        // released over the create button: the nearer side wins
        m.down(m.centre(1)); m.move(m.pad() + 2.4f * m.cell()); check(m.up() == 1, "left of the middle goes to friends");
        m.down(m.centre(3)); m.move(m.pad() + 2.6f * m.cell()); check(m.up() == 3, "right of the middle goes to inbox");
        run(m, 2f);
        // the create button squeezes and is opened, never held
        check(m.down(m.centre(2)) == 2 && !m.dragging, "create button"); run(m, .1f); check(m.squash < 1, "squeezed");
        run(m, 1f); near(m.squash, 1, "back");
        // TikTok's own choice: the lens swells on its way there and goes flat
        m.follow(1); check(m.sel == 1 && m.tg == 1f, "follows TikTok with the lens"); run(m, 2f); near(m.x, m.centre(1), "on friends"); near(m.g, 0, "flat");
        m.down(m.centre(0)); m.follow(3); check(m.sel == 0, "a held lens is not moved"); m.cancel(); run(m, 2f);
        // a stalled main thread: a quarter second at once lands where the lens should be, stable
        m.down(m.centre(4)); m.up(); for (int i = 0; i < 8; i++) m.step(.25f);
        check(!Float.isNaN(m.x) && Math.abs(m.x - m.centre(4)) < .05f && Math.abs(m.g) < .01f, "big steps settle: " + m.x + " " + m.g);
        // the names switch crossfades over .45 s
        m.labels = false; m.step(.2f); check(m.names > .4f && m.names < .7f, "halfway: " + m.names); run(m, .5f); near(m.names, 0, "names gone");
        // four tabs, no create button, a lower bar
        LensMotion four = new LensMotion(); four.n = 4; four.create = -1; four.height = 48; four.width = 330; four.settle();
        near(four.pad(), 10 * 48 / 68f, "padding scales"); four.down(four.centre(3)); check(four.up() == 3, "last tab");
    }
}
'''


class NavigationIslandTest(unittest.TestCase):
    run_java = safety.SafetyRuntimeTest.run_java

    def test_lens_springs_release_and_tabs(self):
        self.run_java({
            'cat/ttcuz/framework/LensMotion.java': SOURCE.read_text(encoding='utf-8'),
            'cat/ttcuz/framework/LensHarness.java': HARNESS,
            'Test.java': 'public class Test{public static void main(String[] a){cat.ttcuz.framework.LensHarness.main(a);}}',
        })


    def test_material_curves_match_css(self):
        # M3Bar plays the demo's CSS transitions by hand: its cubic-bezier must give what a browser gives
        source = (ROOT / 'inject/java/cat/ttcuz/framework/M3Bar.java').read_text(encoding='utf-8')
        start = source.index('    static float bezier(')
        end = source.index('\n    }\n', start) + 6
        self.run_java({
            'cat/ttcuz/framework/Curve.java': 'package cat.ttcuz.framework;public final class Curve{' + source[start:end] + r'''
                static void near(float a, float b, String what) { if (Math.abs(a - b) > .003f) throw new AssertionError(what + ": " + a + " != " + b); }
                public static void main(String[] args) {
                    near(bezier(.25f, .1f, .25f, 1f, .5f), .8024f, "ease at .5");
                    near(bezier(.25f, .1f, .25f, 1f, .25f), .4085f, "ease at .25");
                    near(bezier(.3f, 0f, 0f, 1f, .5f), .8704f, "emphasized at .5");
                    near(bezier(.3f, 0f, 0f, 1f, 0f), 0f, "start"); near(bezier(.3f, 0f, 0f, 1f, 1f), 1f, "end");
                }}''',
            'Test.java': 'public class Test{public static void main(String[] a){cat.ttcuz.framework.Curve.main(a);}}',
        })


if __name__ == '__main__':
    unittest.main()
