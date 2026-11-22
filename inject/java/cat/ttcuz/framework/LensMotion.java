package cat.ttcuz.framework;

/**
 * The glass island's motion exactly as the main bar of lens.html moves (mk(): tick(), the pointer handlers, near()
 * and ctr()): the lens follows the finger on a stiff spring, grows while held, and on release springs to the nearest
 * tab and goes flat 160 ms later. Plain Java, so it runs in tests. Lengths in dp along the bar from its left edge; the
 * demo's bar is 453 x 68 and everything here scales with the real bar's height the way the demo's would.
 */
final class LensMotion {
    /** The bar (set by the view from TikTok's row), how many tabs, and which one is the create button (-1: none). */
    float width = 453f, height = 68f;
    int n = 5, create = 2;
    /** x: the lens's centre, v its speed, tx where it goes; g: how grown (0 rest pill .. 1 lens), gv, tg. */
    float x, v, tx, g, gv, tg;
    boolean dragging;
    int sel;
    /** The demo's cov: the lens covers the tabs, so no tab is shown chosen (hysteresis .7 up, .45 down). */
    boolean covered;
    /** The create button pressed: scale .85 for 180 ms, eased over .15 s (.tab.tap). */
    float squash = 1f;
    /** .lens.on: its shadow, on from the touch until the lens goes flat, faded over .3 s (0..1, linear; the view eases it). */
    boolean lit;
    float shade;
    /** The «Названия» switch: the demo crossfades its two bars over .45 s (1: with names). */
    float names = 1f;
    boolean labels = true;
    private float squashFor, flatIn = -1f;
    /** A haptic the last call asked for, in the demo's hap() milliseconds (0: none); the view plays and clears it. */
    int buzz;

    float unit() { return height / 68f; }
    float pad() { return 10f * unit(); }
    float cell() { return (width - 2 * pad()) / n; }
    /** The lens is as wide as the demo's 99 px over its 86.6 px cell. */
    float lensWidth() { return cell() * 99f / 86.6f; }
    float centre(int i) { return pad() + i * cell() + cell() / 2f; }
    int near(float c) { return Math.max(0, Math.min(n - 1, (int) Math.floor((c - pad()) / cell()))); }
    private float clamp(float c) { return Math.max(centre(0), Math.min(centre(n - 1), c)); }
    float grown() { return Math.max(0f, Math.min(1f, g)); }
    /** The tab drawn chosen (in the accent), or -1 while the lens covers them. */
    int shown() { return covered || sel == create ? -1 : sel; }

    /** A new bar size or tab set: everything at rest on the chosen tab. */
    void settle() { if (sel >= n || sel == create) sel = create == 0 ? 1 : 0; x = tx = centre(sel); v = 0f; g = gv = tg = 0f; dragging = covered = lit = false; flatIn = -1f; squash = 1f; squashFor = 0f; shade = 0f; names = labels ? 1f : 0f; }

    /** TikTok chose a tab itself (back, a link): the lens goes there unless a finger holds it. */
    void follow(int tab) {
        if (dragging || tab < 0 || tab >= n || tab == create || tab == sel) return;
        // as the demo's go(): the lens swells a little on its way there, then goes flat
        sel = tab; tx = centre(tab); tg = 1f; lit = true; flatIn = .35f;
    }

    /**
     * One frame of real time (up to a quarter second, in steps of at most 8 ms so the springs stay stable): after
     * TikTok holds the main thread while it opens a tab, the lens is where it should be by now instead of crawling on.
     * True while anything still moves.
     */
    boolean step(float dt) {
        dt = Math.max(0f, Math.min(dt, .25f));
        int steps = Math.max(1, (int) Math.ceil(dt / .008f));
        boolean busy = false;
        for (int i = 0; i < steps; i++) busy = tick(dt / steps);
        return busy;
    }

    private boolean tick(float dt) {
        if (flatIn > 0f && (flatIn -= dt) <= 0f && !dragging) { tg = 0f; lit = false; }
        float fade = dt / .3f; shade += Math.max(-fade, Math.min(fade, (lit ? 1f : 0f) - shade));
        float swap = dt / .45f; names += Math.max(-swap, Math.min(swap, (labels ? 1f : 0f) - names));
        if (squashFor > 0f) squashFor -= dt;
        float want = squashFor > 0f ? .85f : 1f, room = dt / .15f * .15f;
        squash += Math.max(-room, Math.min(room, want - squash));
        float k = dragging ? 320 : 95, c = dragging ? 36 : 19, edge = 3 * unit();
        v += ((tx - x) * k - v * c) * dt;
        x = Math.max(centre(0) - edge, Math.min(centre(n - 1) + edge, x + v * dt));
        gv += ((tg - g) * 130 - gv * 21) * dt;
        g += gv * dt;
        float o = grown();
        covered = covered ? o > .45f : o > .7f;
        boolean busy = dragging || Math.abs(v) > .05f || Math.abs(tx - x) > .05f || Math.abs(gv) > .001f || Math.abs(tg - g) > .001f
                || flatIn > 0f || squashFor > 0f || squash != 1f || shade != (lit ? 1f : 0f) || names != (labels ? 1f : 0f);
        if (!busy) { x = tx; g = tg; v = gv = 0f; covered = grown() > .7f; }
        return busy;
    }

    /** A finger landed at c. Returns the create button if that is it (it opens on release), else -1. */
    int down(float c) {
        if (near(c) == create) { squashFor = .18f; buzz = 10; return create; }
        dragging = true; tg = 1f; lit = true; flatIn = -1f; sel = near(c); tx = centre(sel); buzz = 10;
        return -1;
    }

    void move(float c) {
        if (!dragging) return;
        tx = clamp(c);
        int i = near(c);
        if (i == create) i = sel;
        if (i != sel) buzz = 6;
        sel = i;
    }

    /** The finger left: the tab the lens settles on, never the create button (-1 when nothing was held). */
    int up() {
        if (!dragging) return -1;
        dragging = false;
        float c = clamp(tx);
        int k = near(c);
        if (k == create) k = c < pad() + (create + .5f) * cell() ? create - 1 : create + 1;
        if (k < 0 || k >= n) k = sel;
        sel = k; tx = centre(k); flatIn = .16f;
        return k;
    }

    void cancel() { if (!dragging) return; dragging = false; tx = centre(sel); flatIn = .16f; }
}
