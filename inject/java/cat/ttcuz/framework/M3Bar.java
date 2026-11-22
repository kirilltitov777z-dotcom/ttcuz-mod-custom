package cat.ttcuz.framework;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.View;

/**
 * The Material 3 island of lens.html (.m3): a tonal bar, the indicator pill that travels to the chosen tab while that
 * tab widens to 132 dp and shows its name, outlined icons that fill when chosen, the create button as a FAB pill.
 * Every transition is the demo's CSS one, played by hand: same curve, same duration, same delay. Sizes in dp.
 */
final class M3Bar extends View implements NavigationIsland.Bar, Choreographer.FrameCallback {
    private static final float H = 64, PAD = 8, GAP = 4, ITEM = 56, ADD = 76, WIDE = 132, ICON = 26, LABEL = 90;
    /** Google's Material Symbols (Rounded): outlined, and the "_fill1" one for the chosen tab, as the demo's FILL axis. */
    private static final String[] ICONS = {"home", "group", "add", "chat_bubble", "person"};

    private final float dp;
    /** TikTok's row (px of this view) and the scale that fits the demo's bar into it, never larger than the demo. */
    private final RectF room = new RectF();
    private float s = 1f;
    private boolean dark, labels = true, placed, looping;
    private int[] roles = {0, 1, 2, 3, 4};
    private String[] names = new String[5], badges = new String[5];
    private int create = 2, sel;
    /** Each transition as the CSS keeps it: where it started, where it goes, and when (uptime ms). */
    private float[] wFrom = new float[5], wTo = new float[5], lbFrom = new float[5], lbTo = new float[5], aFrom = new float[5], aTo = new float[5], fFrom = new float[5], fTo = new float[5];
    private float pillFrom, pillTo, pillWFrom, pillWTo;
    private long changed = -10_000L;
    /**
     * The item a finger is on (-1: none), and the item whose press plays, in or out: .mi:active scales the item to
     * .94 (ease, .15 s), and the create button's FAB (.ab) to .88 on top of that (cubic-bezier(.3,0,0,1), .2 s).
     */
    private int held = -1, pressed = -1;
    private float outerFrom = 1f, innerFrom = 1f;
    private long pressAt = -10_000L;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG), count = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics fm = new Paint.FontMetrics(), cm = new Paint.FontMetrics();
    private final RectF r = new RectF();
    private final float[] w = new float[5];

    M3Bar(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;
        text.setTypeface(Build.VERSION.SDK_INT >= 28 ? Typeface.create(Typeface.DEFAULT, 600, false) : Typeface.DEFAULT_BOLD);
        text.setTextSize(15f);
        count.setTypeface(Build.VERSION.SDK_INT >= 28 ? Typeface.create(Typeface.DEFAULT, 700, false) : Typeface.DEFAULT_BOLD);
        count.setTextSize(11f); count.setTextAlign(Paint.Align.CENTER);
        setWillNotDraw(false);
    }

    /** CSS cubic-bezier(x1,y1,x2,y2) at time t (0..1): the curve's x solved for t by bisection, then its y. */
    static float bezier(float x1, float y1, float x2, float y2, float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        float lo = 0f, hi = 1f, s = t;
        for (int i = 0; i < 24; i++) {
            s = (lo + hi) / 2f;
            float x = 3 * (1 - s) * (1 - s) * s * x1 + 3 * (1 - s) * s * s * x2 + s * s * s;
            if (x < t) lo = s; else hi = s;
        }
        return 3 * (1 - s) * (1 - s) * s * y1 + 3 * (1 - s) * s * s * y2 + s * s * s;
    }
    private static float emph(float t) { return bezier(.3f, 0f, 0f, 1f, t); }
    private static float ease(float t) { return bezier(.25f, .1f, .25f, 1f, t); }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    // the demo's clock a touch faster (a quarter was too quick): every transition keeps its shape and is over sooner
    private float since() { return (SystemClock.uptimeMillis() - changed) / 910f; }
    private float width(int i) { return lerp(wFrom[i], wTo[i], emph(since() / .5f)); }
    private float labelRoom(int i) { return lerp(lbFrom[i], lbTo[i], emph(since() / .5f)); }
    /** Opacity of a name: in over .3 s after .12 s, out over .2 s at once (the demo's two transitions). */
    private float labelAlpha(int i) { float t = since(); return lerp(aFrom[i], aTo[i], aTo[i] > aFrom[i] ? ease((t - .12f) / .3f) : ease(t / .2f)); }
    private float filled(int i) { return lerp(fFrom[i], fTo[i], ease(since() / .25f)); }
    private float outer() { return lerp(outerFrom, held >= 0 ? .94f : 1f, ease((SystemClock.uptimeMillis() - pressAt) / 150f)); }
    private float inner() { return lerp(innerFrom, held >= 0 && held == create ? .88f : 1f, emph((SystemClock.uptimeMillis() - pressAt) / 200f)); }
    /** A press starts or ends: both scales go on from where they are now. */
    private void pressTo(int item) { outerFrom = outer(); innerFrom = inner(); held = item; if (item >= 0) pressed = item; pressAt = SystemClock.uptimeMillis(); kick(); }

    /** set(i) of the demo: every value goes on from where it is now towards the new choice. */
    /** dp added to every tab but the create button, so the bar spans the room as the glass bar does (the demo's is
     *  as narrow as its items; over TikTok's row that looked lost in the middle). */
    private float spread;

    private void choose(int tab, boolean animate) {
        float now0 = since();
        int tabs = 0; for (int i = 0; i < roles.length; i++) if (i != create) tabs++;
        float natural = 2 * PAD + GAP * (roles.length - 1) + (create >= 0 ? ADD : 0) + ITEM * tabs + (labels && tabs > 0 ? WIDE - ITEM : 0);
        spread = room.isEmpty() || tabs == 0 ? 0f : Math.max(0f, (room.width() / (dp * s) - natural) / tabs);
        for (int i = 0; i < roles.length; i++) {
            float wNow = width(i), lbNow = labelRoom(i), aNow = labelAlpha(i), fNow = filled(i);
            boolean on = i == tab;
            wTo[i] = i == create ? ADD : (on && labels ? WIDE : ITEM) + spread;
            lbTo[i] = on && labels ? LABEL : 0f;
            aTo[i] = on && labels ? 1f : 0f;
            fTo[i] = on ? 1f : 0f;
            if (animate) { wFrom[i] = wNow; lbFrom[i] = lbNow; aFrom[i] = aNow; fFrom[i] = fNow; }
            else { wFrom[i] = wTo[i]; lbFrom[i] = lbTo[i]; aFrom[i] = aTo[i]; fFrom[i] = fTo[i]; }
        }
        float pillNow = lerp(pillFrom, pillTo, emph(now0 / .5f)), pwNow = lerp(pillWFrom, pillWTo, emph(now0 / .5f));
        float left = PAD;
        for (int k = 0; k < tab; k++) left += wTo[k] + GAP;
        // the pill is its tab's own width, stretch included: the demo's fixed 132 left a gap at the tab's end
        pillTo = left; pillWTo = tab >= 0 && tab < wTo.length ? wTo[tab] : (labels ? WIDE : ITEM) + spread;
        if (animate) { pillFrom = pillNow; pillWFrom = pwNow; } else { pillFrom = pillTo; pillWFrom = pillWTo; }
        sel = tab; changed = animate ? SystemClock.uptimeMillis() : -10_000L;
        kick();
    }

    // ------------------------------------------------------------------ NavigationIsland.Bar

    @Override public void place(float left, float top, float right, float bottom) {
        if (room.left == left && room.top == top && room.right == right && room.bottom == bottom) return;
        room.set(left, top, right, bottom);
        // as tall as TikTok's row (the demo's 64 dp at most), the bar then stretched to the row's width (spread)
        s = Math.max(.6f, Math.min(1f, Math.min(room.height() / (H * dp), room.width() / (408 * dp))));
        if (placed) choose(sel, false);
        invalidate();
    }

    @Override public void tabs(int[] roles, String[] names, int create, int chosen) {
        this.roles = roles; this.names = names; this.create = create; badges = new String[roles.length];
        wFrom = new float[roles.length]; wTo = new float[roles.length]; lbFrom = new float[roles.length]; lbTo = new float[roles.length];
        aFrom = new float[roles.length]; aTo = new float[roles.length]; fFrom = new float[roles.length]; fTo = new float[roles.length];
        if (chosen >= 0) sel = chosen;
        if (sel >= roles.length || sel == create) sel = create == 0 ? 1 : 0;
        placed = true; choose(sel, false);
    }

    @Override public void badges(String[] now) { badges = now; invalidate(); }

    @Override public void select(int tab) { if (tab != sel && tab >= 0 && tab < roles.length && tab != create) choose(tab, true); }

    @Override public void settings(boolean dark, boolean labels, float glass) {
        boolean relabel = this.labels != labels;
        this.dark = dark; this.labels = labels;
        if (placed && relabel) choose(sel, true); else invalidate();
    }

    /** The bar in px: centred in TikTok's row. */
    private float barWidth() { float t = 2 * PAD + GAP * (roles.length - 1); for (int i = 0; i < roles.length; i++) t += width(i); return t; }
    private float barLeft() { return room.centerX() - barWidth() * dp * s / 2f; }
    private float barTop() { return room.centerY() - H * dp * s / 2f; }

    private int item(float x) {
        float at = (x - barLeft()) / (dp * s) - PAD;
        for (int i = 0; i < roles.length; i++) { float wi = width(i); if (at >= -GAP / 2 && at < wi + GAP / 2) return i; at -= wi + GAP; }
        return -1;
    }

    @Override public boolean hit(float x, float y) {
        float left = barLeft(), top = barTop();
        return !room.isEmpty() && x >= left && x <= left + barWidth() * dp * s && y >= top && y <= top + H * dp * s;
    }

    @Override public int down(float x) { pressTo(item(x)); return -1; }
    @Override public void move(float x) { if (held >= 0 && item(x) != held) release(); }
    @Override public int up() {
        int tab = held; release();
        if (tab < 0) return -1;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (tab != create) choose(tab, true);
        return tab;
    }
    @Override public void cancel() { release(); }
    private void release() { if (held >= 0) pressTo(-1); }
    @Override public void capture() {}

    // ------------------------------------------------------------------ motion and drawing

    private void kick() { invalidate(); if (!looping) { looping = true; Choreographer.getInstance().postFrameCallback(this); } }

    @Override public int tabAt(float x) { return item(x); }

    /** Anti-burn: the icons', names' and marks' brightness now and where it eases to. */
    private float dimNow = 1f, dimTo = 1f;
    @Override public void dim(float level) { if (level != dimTo) { dimTo = level; kick(); } }

    @Override public void doFrame(long now) {
        invalidate();
        boolean busy = since() < .5f || SystemClock.uptimeMillis() - pressAt < 200;
        if (Math.abs(dimTo - dimNow) > .002f) { dimNow += (dimTo - dimNow) * .14f; busy = true; } else dimNow = dimTo;
        if (busy && isAttachedToWindow()) Choreographer.getInstance().postFrameCallback(this); else looping = false;
    }

    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); Choreographer.getInstance().removeFrameCallback(this); looping = false; }

    /** Back on screen: a frame booked before the app went to the background may never have come (see LensBar). */
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) { Choreographer.getInstance().removeFrameCallback(this); looping = false; kick(); }
    }

    @Override protected void onDraw(Canvas c) {
        if (!placed || room.isEmpty()) return;
        // --m3-* of the demo, dark and light
        // the demo's --m3-* tones, built from the accent (the demo's are one green's): the bar a whisper of it, the pill
        // and the icons stronger, the create button the accent itself
        int accent = Accent.colour();
        int barColour = Expressive.mix(dark ? 0xFF1B1B1E : 0xFFECECEF, accent, dark ? .07f : .08f),
                pillColour = Expressive.mix(barColour, accent, dark ? .32f : .28f),
                ic = Expressive.mix(dark ? 0xFFF2F2F5 : 0xFF17171C, accent, dark ? .28f : .42f),
                lb = Expressive.mix(dark ? 0xFFDADADF : 0xFF2A2A30, accent, dark ? .38f : .5f),
                fab = accent, fabIc = light(accent) ? 0xFF111111 : 0xFFFFFFFF;
        int k = c.save();
        c.translate(barLeft(), barTop()); c.scale(dp * s, dp * s);
        float bw = barWidth();
        fill.setColor(barColour); r.set(0, 0, bw, H); c.drawRoundRect(r, H / 2, H / 2, fill);
        float t = emph(since() / .5f), pl = lerp(pillFrom, pillTo, t), pw = lerp(pillWFrom, pillWTo, t);
        fill.setColor(pillColour); r.set(pl, PAD, pl + pw, PAD + 48); c.drawRoundRect(r, 24, 24, fill);
        text.getFontMetrics(fm);
        float x = PAD, outer = outer(), inner = inner();
        for (int i = 0; i < roles.length; i++) {
            float wi = width(i); w[i] = wi;
            int sv = c.save();
            if (i == pressed) c.scale(outer, outer, x + wi / 2, PAD + 24);
            if (i == create) {
                // the FAB pill: 64 x 44, 16 round, the plus 32 dp
                float cx = x + wi / 2, sc = i == pressed ? inner : 1f;
                c.scale(sc, sc, cx, PAD + 24);
                fill.setColor(fab); fill.setAlpha(Math.round(255 * dimNow)); r.set(cx - 32, PAD + 2, cx + 32, PAD + 46); c.drawRoundRect(r, 16, 16, fill);
                glyph(c, "add", cx - 16, PAD + 8, 32, fabIc, 1f);
            } else {
                r.set(x, PAD, x + wi, PAD + 48);
                c.clipRect(r);
                String name = names[i] == null ? "" : names[i];
                float room = Math.min(text.measureText(name), labelRoom(i)), content = ICON + 8 + room, ix = x + (wi - content) / 2, iy = PAD + 11;
                String icon = ICONS[Math.max(0, Math.min(4, roles[i]))];
                float f = filled(i);
                if (f < 1f) glyph(c, icon, ix, iy, ICON, ic, 1 - f);
                if (f > 0f) glyph(c, icon + "_fill1", ix, iy, ICON, ic, f);
                float a = labelAlpha(i);
                if (a > 0f && !name.isEmpty()) { text.setColor(lb); text.setAlpha(Math.round(255 * a * dimNow)); c.drawText(name, ix + ICON + 8, PAD + 24 - (fm.ascent + fm.descent) / 2, text); }
                c.restoreToCount(sv); sv = c.save();
                String n = badges[i];
                if (n != null) {
                    // .bd: 19 tall, as wide as its number and at least 19, 7 above and 9 right of the icon's box
                    float cw = n.isEmpty() ? 10 : Math.max(19, count.measureText(n)), right = ix + ICON + 9, top = iy - 7, h = n.isEmpty() ? 10 : 19;
                    fill.setColor(fab); fill.setAlpha(Math.round(255 * dimNow)); r.set(right - cw, top, right, top + h); c.drawRoundRect(r, h / 2, h / 2, fill);
                    if (!n.isEmpty()) { count.setColor(fabIc); count.setAlpha(Math.round(255 * dimNow)); count.getFontMetrics(cm); c.drawText(n, r.centerX(), top + h / 2 - (cm.ascent + cm.descent) / 2, count); }
                }
            }
            c.restoreToCount(sv);
            x += wi + GAP;
        }
        c.restoreToCount(k);
    }

    private void glyph(Canvas c, String name, float x, float y, float size, int colour, float alpha) {
        Path p = Symbols.path(name);
        if (p == null) return;
        fill.setColor(colour); fill.setAlpha(Math.round(255 * alpha * dimNow));
        int q = c.save(); c.translate(x, y); c.scale(size / 24f, size / 24f); c.drawPath(p, fill); c.restoreToCount(q);
    }

    private static boolean light(int c) {
        return (.299f * android.graphics.Color.red(c) + .587f * android.graphics.Color.green(c) + .114f * android.graphics.Color.blue(c)) / 255f > .62f;
    }
}
