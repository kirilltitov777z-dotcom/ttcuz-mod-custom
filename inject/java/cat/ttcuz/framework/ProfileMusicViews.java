package cat.ttcuz.framework;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/**
 * The drawn parts of the profile player: the round play button whose triangle folds into the two bars of pause, the
 * seek line, and the three bars that move while a track plays.
 */
final class ProfileMusicViews {
    private ProfileMusicViews() {}

    private static float dp(View v, float value) { return value * v.getResources().getDisplayMetrics().density; }

    /**
     * Play and pause in one: the triangle's two halves slide into the pause bars and back. While the sound is still
     * being fetched a thin arc turns round it; with a progress set, a ring round it fills as the track plays.
     */
    static final class PlayButton extends View {
        private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG), mark = new Paint(Paint.ANTI_ALIAS_FLAG),
                ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF oval = new RectF();
        private float morph, goal, progress = -1f;
        private boolean loading;
        private ValueAnimator turning;

        /** `fill` 0 draws no disc: the mark alone, as in the island. */
        PlayButton(Context c, int fill, int ink) {
            super(c);
            disc.setColor(fill);
            mark.setColor(ink);
            // the mark is two halves (a triangle cut down its middle, which become the two bars): filled and stroked
            // with round joins, the halves overlap along the cut, so no seam shows and only the outer corners round.
            // A CornerPathEffect rounded the corners on the cut too, a dent in the triangle's top and bottom edges
            mark.setStyle(Paint.Style.FILL_AND_STROKE);
            mark.setStrokeJoin(Paint.Join.ROUND);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeCap(Paint.Cap.ROUND);
            ring.setColor(ink);
            setContentDescription(Text.t("Играть"));
        }

        void colours(int fill, int ink) { disc.setColor(fill); mark.setColor(ink); ring.setColor(ink); invalidate(); }

        boolean playing() { return morph > .5f; }

        void set(boolean playing, boolean loading, boolean animate) {
            float target = playing ? 1f : 0f;
            boolean wasLoading = this.loading;
            this.loading = loading;
            setContentDescription(playing ? Text.t("Пауза") : Text.t("Играть"));
            if (loading != wasLoading) invalidate();
            if (target == goal) return;
            goal = target;
            if (turning != null) turning.cancel();
            if (!animate || !Motion.enabled(getContext()) || !isShown()) { morph = target; invalidate(); return; }
            turning = ValueAnimator.ofFloat(morph, target);
            turning.setDuration(Motion.time(240L));
            turning.setInterpolator(Motion.BOTH);
            turning.addUpdateListener(a -> { morph = (Float) a.getAnimatedValue(); invalidate(); });
            turning.start();
        }

        /** 0..1 of the track for the ring round the mark; below 0, no ring. */
        void progress(float value) {
            if (Math.abs(value - progress) < .002f) return;
            progress = value;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, r = Math.min(w, h) / 2f;
            if ((disc.getColor() >>> 24) != 0) canvas.drawCircle(cx, cy, r, disc);
            boolean bare = (disc.getColor() >>> 24) == 0;
            float stroke = Math.max(1.5f, r * (bare ? .1f : .07f));
            ring.setStrokeWidth(stroke);
            float inset = stroke / 2f + (bare ? 0 : r * .1f);
            oval.set(cx - r + inset, cy - r + inset, cx + r - inset, cy + r - inset);
            if (loading) {
                long now = SystemClock.uptimeMillis();
                ring.setAlpha(255);
                canvas.drawArc(oval, (now % 1000) * .36f, 80f, false, ring);
                postInvalidateOnAnimation();
            } else if (progress >= 0f) {
                ring.setAlpha(60);
                canvas.drawOval(oval, ring);
                ring.setAlpha(255);
                canvas.drawArc(oval, -90f, 360f * Math.min(1f, progress), false, ring);
            }
            float s = r * (bare ? .78f : .62f);
            // the round stroke adds half its width all round: the shape is drawn that much smaller
            mark.setStrokeWidth(s * .12f);
            s *= .88f;
            float m = morph;
            // play: a triangle, its tip a little right of the middle so it looks centred
            float left = cx - s * .36f, right = cx + s * .48f, mid = (left + right) / 2f, top = cy - s * .5f, bottom = cy + s * .5f;
            // pause: two bars
            float bar = s * .28f, gap = s * .22f, pTop = cy - s * .42f, pBottom = cy + s * .42f;
            float a0 = cx - gap / 2f - bar, a1 = cx - gap / 2f, b0 = cx + gap / 2f, b1 = cx + gap / 2f + bar;
            path.reset();
            quad(path, l(left, a0, m), l(top, pTop, m), l(mid, a1, m), l(cy - s * .25f, pTop, m),
                    l(mid, a1, m), l(cy + s * .25f, pBottom, m), l(left, a0, m), l(bottom, pBottom, m));
            quad(path, l(mid, b0, m), l(cy - s * .25f, pTop, m), l(right, b1, m), l(cy, pTop, m),
                    l(right, b1, m), l(cy, pBottom, m), l(mid, b0, m), l(cy + s * .25f, pBottom, m));
            canvas.drawPath(path, mark);
        }

        private static float l(float from, float to, float t) { return from + (to - from) * t; }

        private static void quad(Path p, float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3) {
            p.moveTo(x0, y0);
            p.lineTo(x1, y1);
            p.lineTo(x2, y2);
            p.lineTo(x3, y3);
            p.close();
        }
    }

    /**
     * The seek line: a thin rounded track, the played part in the accent. Touched, it thickens and grows a thumb, and
     * the place under the finger is told on every move and once more when the finger lifts.
     */
    static final class SeekLine extends View {
        interface Moved { void to(int ms, boolean done); }

        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), played = new Paint(Paint.ANTI_ALIAS_FLAG),
                thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bar = new RectF();
        private int duration, position, dragAt = -1;
        /** while playing the line runs on by the clock between the player's reports: from position at playingSince */
        private boolean running;
        private long since;
        private float grab;
        private boolean loading;
        private ValueAnimator grabbing;
        private Moved moved;

        SeekLine(Context c, int accent, int text) {
            super(c);
            track.setColor((text & 0xFFFFFF) | 0x2E000000);
            played.setColor(accent);
            thumb.setColor(accent);
            setContentDescription(Text.t("Перемотка"));
        }

        void onMoved(Moved m) { moved = m; }

        boolean dragging() { return dragAt >= 0; }

        void set(int position, int duration, boolean loading, boolean playing) {
            long now = SystemClock.uptimeMillis();
            // a report close to where the line already runs keeps it running: re-basing on each one made it stutter back
            boolean near = running && playing && duration == this.duration && Math.abs(runAt(now) - position) < 300;
            boolean changed = !near && (position != this.position || duration != this.duration) || loading != this.loading || playing != running;
            if (!near) { this.position = position; since = now; }
            else if (!playing) { this.position = runAt(now); since = now; }
            this.duration = duration;
            this.loading = loading;
            running = playing;
            if (changed || running) invalidate();
        }

        /** Where the line is now: the last position, plus the time since it while playing. */
        private int runAt(long now) {
            return running ? (int) Math.min(duration, position + (now - since)) : position;
        }

        /** The played part and the knob take a colour (the cover's), flowing to it. */
        void colour(int to) {
            ValueAnimator flow = ValueAnimator.ofObject(new android.animation.ArgbEvaluator(), played.getColor(), to);
            flow.setDuration(Motion.time(450L));
            flow.addUpdateListener(a -> { int c = (Integer) a.getAnimatedValue(); played.setColor(c); thumb.setColor(c); invalidate(); });
            flow.start();
        }

        private void grab(boolean on) {
            if (grabbing != null) grabbing.cancel();
            grabbing = ValueAnimator.ofFloat(grab, on ? 1f : 0f);
            grabbing.setDuration(Motion.time(160L));
            grabbing.setInterpolator(Motion.OUT);
            grabbing.addUpdateListener(a -> { grab = (Float) a.getAnimatedValue(); invalidate(); });
            grabbing.start();
        }

        private int at(float x) {
            float pad = dp(this, 8), width = getWidth() - pad * 2;
            float part = width <= 0 ? 0 : Math.max(0f, Math.min(1f, (x - pad) / width));
            return Math.round(part * duration);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (duration <= 0) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    dragAt = at(e.getX());
                    grab(true);
                    if (moved != null) moved.to(dragAt, false);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    dragAt = at(e.getX());
                    if (moved != null) moved.to(dragAt, false);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    int to = at(e.getX());
                    boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                    dragAt = -1;
                    if (up) { position = to; since = SystemClock.uptimeMillis(); }
                    grab(false);
                    if (moved != null) moved.to(up ? to : position, true);
                    invalidate();
                    return true;
            }
            return false;
        }

        @Override protected void onDraw(Canvas canvas) {
            float pad = dp(this, 8), cy = getHeight() / 2f, half = dp(this, 2f) + grab * dp(this, 1.5f);
            float left = pad, right = getWidth() - pad;
            bar.set(left, cy - half, right, cy + half);
            canvas.drawRoundRect(bar, half, half, track);
            if (loading && duration <= 0) {
                // the track is being fetched: a short piece of the accent runs along the line
                float width = right - left, run = width * .28f;
                float t = (SystemClock.uptimeMillis() % 1100) / 1100f;
                float from = left - run + (width + run) * t;
                bar.set(Math.max(left, from), cy - half, Math.min(right, from + run), cy + half);
                if (bar.width() > 0) canvas.drawRoundRect(bar, half, half, played);
                postInvalidateOnAnimation();
                return;
            }
            int shown = dragAt >= 0 ? dragAt : runAt(SystemClock.uptimeMillis());
            if (running && dragAt < 0 && isShown()) postInvalidateOnAnimation();
            float part = duration <= 0 ? 0f : Math.max(0f, Math.min(1f, shown / (float) duration));
            float x = left + (right - left) * part;
            if (x > left) {
                bar.set(left, cy - half, x, cy + half);
                canvas.drawRoundRect(bar, half, half, played);
            }
            float knob = dp(this, 7f) * grab;
            if (knob > .5f) canvas.drawCircle(x, cy, knob, thumb);
        }
    }

    /** Three bars rising and falling while a track plays, resting low while it is paused. */
    static final class Bars extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bar = new RectF();
        private boolean on;
        private float level;

        Bars(Context c, int colour) {
            super(c);
            paint.setColor(colour);
        }

        void set(boolean playing) {
            if (on == playing) return;
            on = playing;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            boolean moving = on && Motion.enabled(getContext()) && isShown();
            long now = SystemClock.uptimeMillis();
            // eased in and out, so the bars settle rather than freeze when the track is paused
            level += ((on ? 1f : 0f) - level) * .18f;
            float w = getWidth(), h = getHeight(), width = w / 5f, gap = width;
            for (int i = 0; i < 3; i++) {
                float wave = (float) (.5 + .5 * Math.sin(now * (.0072 + i * .0011) + i * 1.9));
                float part = .22f + level * .78f * wave;
                float x = i * (width + gap), top = h - h * part;
                bar.set(x, top, x + width, h);
                canvas.drawRoundRect(bar, width / 2f, width / 2f, paint);
            }
            if (moving || Math.abs(level - (on ? 1f : 0f)) > .01f) postInvalidateOnAnimation();
        }
    }
}
