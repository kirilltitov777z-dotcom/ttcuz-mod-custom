package cat.ttcuz.framework;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

/**
 * What a "!" says, as a drop instead of a dialog (the way the fake-streak plugin's menu opens from its flame): the
 * drop stretches down out of the button on a neck, the neck snaps (a tick), and the drop springs open into a grey card
 * with the words -- its width a moment behind its height, so it stretches on the way -- while the screen behind dims
 * and the words come in one after the other. A tap anywhere pulls it back into the button, which springs back.
 */
final class InfoDrop extends View {
    private static final int GREY = 0xF5232327;
    /** seconds the drop hangs on its neck before it tears off and the spring starts */
    private static final float PINCH = .17f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), seedPaint = new Paint(Paint.ANTI_ALIAS_FLAG),
            dim = new Paint();
    private final TextPaint titleInk = new TextPaint(Paint.ANTI_ALIAS_FLAG), textInk = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF start = new RectF(), end = new RectF(), rect = new RectF();
    private final Path shape = new Path(), neck = new Path();
    private final String title, text;
    private final View anchor;
    private StaticLayout titleLayout, textLayout;

    private float growX, growY, shiftY, seed, dimAmount, dropDistance, radius;
    private boolean neckAlive, neckWasAlive, closing;
    private ValueAnimator running;

    private InfoDrop(View anchor, String title, String text) {
        super(anchor.getContext());
        this.anchor = anchor;
        this.title = title == null ? "" : title;
        this.text = text == null ? "" : text;
        fill.setColor(GREY);
        seedPaint.setColor(GREY);
        dim.setColor(0xFF000000);
        float sp = getResources().getDisplayMetrics().scaledDensity;
        titleInk.setColor(0xFFFFFFFF);
        titleInk.setTextSize(15 * sp);
        titleInk.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        textInk.setColor(0xDEFFFFFF);
        textInk.setTextSize(14 * sp);
        setContentDescription(this.title + ". " + this.text);
        setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
    }

    /** Opens the words out of `anchor` (the "!") over its window. */
    static void show(View anchor, String title, String text) {
        try {
            View root = anchor.getRootView();
            if (!(root instanceof FrameLayout) || root.getWidth() == 0) return;
            // one at a time: another "!" pulls the last one back first
            for (int i = ((FrameLayout) root).getChildCount() - 1; i >= 0; i--) {
                View child = ((FrameLayout) root).getChildAt(i);
                if (child instanceof InfoDrop) ((InfoDrop) child).dismiss();
            }
            InfoDrop drop = new InfoDrop(anchor, title, text);
            ((FrameLayout) root).addView(drop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            drop.post(drop::open);
        } catch (Throwable error) {
            Diary.note("info drop: " + error);
        }
    }

    /** The "!" itself: a grey circle with the mark (inside its padding, which only makes it easier to hit). */
    static View button(Context c, final String title, final String text) {
        View dot = new View(c) {
            final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG), sign = new Paint(Paint.ANTI_ALIAS_FLAG);
            {
                disc.setColor(GREY);
                sign.setColor(0xFFFFFFFF);
                sign.setTextAlign(Paint.Align.CENTER);
                sign.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            }
            @Override protected void onDraw(Canvas canvas) {
                float side = Math.min(getWidth(), getHeight()) - getPaddingLeft() * 2, cx = getWidth() / 2f, cy = getHeight() / 2f;
                canvas.drawCircle(cx, cy, side / 2f, disc);
                sign.setTextSize(side * .62f);
                canvas.drawText("!", cx, cy - (sign.descent() + sign.ascent()) / 2f, sign);
            }
        };
        dot.setContentDescription(Text.t("Что это"));
        dot.setOnClickListener(v -> show(v, title, text));
        return dot;
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    /** Where it grows from (a small circle in the middle of the "!") and where it ends (the card, over it). */
    private void measure() {
        float margin = dp(14), pad = dp(16), gap = dp(6);
        int[] at = new int[2], origin = new int[2];
        anchor.getLocationOnScreen(at);
        getLocationOnScreen(origin);
        float side = Math.max(dp(22), Math.min(Math.min(anchor.getWidth(), anchor.getHeight()) - anchor.getPaddingLeft() * 2f, dp(30)));
        float cx = at[0] - origin[0] + anchor.getWidth() / 2f, cy = at[1] - origin[1] + anchor.getHeight() / 2f;
        start.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f);

        float widest = Math.min(dp(300), getWidth() - margin * 2);
        int room = (int) (widest - pad * 2);
        int need = (int) Math.ceil(Math.max(StaticLayout.getDesiredWidth(title, titleInk), StaticLayout.getDesiredWidth(text, textInk)));
        int width = Math.max((int) dp(140), Math.min(room, need + 1));
        titleLayout = title.isEmpty() ? null : StaticLayout.Builder.obtain(title, 0, title.length(), titleInk, width).build();
        textLayout = StaticLayout.Builder.obtain(text, 0, text.length(), textInk, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.15f).build();
        float h = pad * 2 + (titleLayout == null ? 0 : titleLayout.getHeight() + gap) + textLayout.getHeight();
        float w = width + pad * 2;
        float left = Math.max(margin, Math.min(getWidth() - w - margin, cx - w / 2f));
        float top = Math.max(margin + dp(24), Math.min(getHeight() - h - margin, start.top - dp(6)));
        end.set(left, top, left + w, top + h);
        dropDistance = Math.max(dp(50), side * 2f);
    }

    private void open() {
        if (getWidth() == 0) { post(this::open); return; }
        measure();
        squish(anchor, true);
        if (!Motion.enabled(getContext())) { set(1f, 1f, 1f, 0f, false); seed = 0f; dimAmount = 1f; return; }
        final float total = PINCH + Spring.DURATION;
        running = ValueAnimator.ofFloat(0f, total);
        running.setDuration((long) (total * 1000));
        running.setInterpolator(new LinearInterpolator());
        neckWasAlive = false;
        running.addUpdateListener(a -> {
            if (closing) return;
            float t = (Float) a.getAnimatedValue();
            if (t < PINCH) {
                // the drop stretches down on its neck
                float p = t / PINCH, e = p * p * (3f - 2f * p);
                seed = Math.min(1f, p * 2.2f);
                dimAmount = 0f;
                setAlpha(Math.min(1f, p * 3.5f));
                set(0f, 0f, p, dropDistance * e, true);
            } else {
                // torn off, it springs open into the card
                float s = t - PINCH, y = Spring.at(s), x = Spring.at(s - .05f);
                seed = 1f - Math.min(1f, y * 4f);
                dimAmount = Math.min(1f, y);
                setAlpha(1f);
                set(x, y, 1f, dropDistance * Math.max(0f, 1f - y), false);
            }
            if (neckWasAlive && !neckAlive && Motion.haptics()) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            neckWasAlive = neckAlive;
        });
        running.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (closing) return;
                seed = 0f;
                dimAmount = 1f;
                set(1f, 1f, 1f, 0f, false);
                running = null;
            }
        });
        running.start();
        announceForAccessibility(getContentDescription());
    }

    /** Pulled back into the "!", fading at the very end; the "!" springs back. */
    void dismiss() {
        if (closing) return;
        closing = true;
        if (running != null) running.cancel();
        if (!Motion.enabled(getContext())) { gone(); return; }
        final float fromX = growX, fromY = growY, shiftFrom = shiftY, dimFrom = dimAmount;
        seed = 0f;
        ValueAnimator back = ValueAnimator.ofFloat(0f, 1f);
        back.setDuration(300);
        back.setInterpolator(new AccelerateDecelerateInterpolator());
        back.addUpdateListener(a -> {
            float v = (Float) a.getAnimatedValue(), rest = 1f - v;
            dimAmount = dimFrom * rest;
            setAlpha(1f - Math.max(0f, Math.min(1f, (v - .7f) / .3f)));
            set(fromX * rest, fromY * rest, 1f, shiftFrom * rest, false);
        });
        back.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { gone(); }
        });
        back.start();
    }

    private void gone() {
        squish(anchor, false);
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
    }

    /** The "!" gives a little under the drop as it leaves, and springs back once it is pulled in again. */
    private static void squish(View view, boolean in) {
        view.animate().cancel();
        if (in) view.animate().scaleX(.82f).scaleY(.82f).setDuration(90).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        else view.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(new android.view.animation.OvershootInterpolator(2.6f)).start();
    }

    /** The shape for one moment: how far it has grown (each way), the neck's stretch, how far down it hangs. */
    private void set(float x, float y, float pinch, float shift, boolean neckOn) {
        growX = x;
        growY = y;
        shiftY = shift;
        rect.left = l(start.left, end.left, x);
        rect.right = l(start.right, end.right, x);
        rect.top = l(start.top, end.top, y);
        rect.bottom = l(start.bottom, end.bottom, y);
        if (pinch < 1f || shift != 0f) {
            // hanging on the neck it narrows and lengthens a little, then rounds out as it tears off
            float k = .55f + .45f * pinch, bump = 4f * pinch * (1f - pinch);
            float w = rect.width() * k * (1f - .2f * bump), h = rect.height() * k * (1f + .35f * bump);
            float cx = rect.centerX(), cy = rect.centerY() + shift;
            rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        }
        float mix = Math.max(0f, Math.min(1f, (x + y) / 2f));
        radius = Math.min(l(start.width() / 2f, dp(22), mix), Math.min(rect.width(), rect.height()) / 2f);
        neckAlive = false;
        if (neckOn) {
            float half = start.width() / 2f, body = Math.min(Math.min(rect.width(), rect.height()) / 2f, dp(30));
            neckAlive = Metaball.bridge(neck, start.centerX(), start.centerY(), half * .72f, rect.centerX(), rect.top + body, body);
        }
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) dismiss();
        // the touch that closes it goes no further: it was meant for the drop, not what is under it
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        if (rect.isEmpty()) return;
        if (dimAmount > 0f) {
            dim.setAlpha(Math.round(90 * Math.min(1f, dimAmount)));
            canvas.drawRect(0, 0, getWidth(), getHeight(), dim);
        }
        if (seed > .01f) {
            // the "!" melting into the drop
            seedPaint.setAlpha(Math.round((GREY >>> 24) * Math.min(1f, seed)));
            canvas.drawCircle(start.centerX(), start.centerY(), start.width() / 2f * .9f, seedPaint);
        }
        if (neckAlive) {
            // body and neck one shape: no seam where they meet
            shape.reset();
            shape.addRoundRect(rect, radius, radius, Path.Direction.CW);
            if (shape.op(neck, Path.Op.UNION)) canvas.drawPath(shape, fill);
            else { canvas.drawPath(neck, fill); canvas.drawRoundRect(rect, radius, radius, fill); }
        } else {
            canvas.drawRoundRect(rect, radius, radius, fill);
        }
        // the words, laid out at the card's full size and cut to its shape, coming in one after the other
        float open = Math.max(0f, Math.min(1f, Math.min(growX, growY)));
        if (open <= .34f || textLayout == null) return;
        int save = canvas.save();
        canvas.clipRect(rect);
        float pad = dp(16), y = end.top + pad, shift = dp(8);
        StaticLayout[] parts = {titleLayout, textLayout};
        TextPaint[] inks = {titleInk, textInk};
        int[] alphas = {255, 0xDE};
        for (int i = 0; i < 2; i++) {
            if (parts[i] == null) continue;
            float t = Math.max(0f, Math.min(1f, (open - .34f - .05f * i) / .3f)), eased = t * t * (3f - 2f * t);
            inks[i].setAlpha(Math.round(alphas[i] * eased));
            int inner = canvas.save();
            canvas.translate(end.left + pad, y + (1f - eased) * shift);
            parts[i].draw(canvas);
            canvas.restoreToCount(inner);
            y += parts[i].getHeight() + dp(6);
        }
        canvas.restoreToCount(save);
    }

    private static float l(float a, float b, float t) { return a + (b - a) * t; }

    /** A spring with a slight overshoot, 0 to 1 and a touch past it, then settling: what gives the drop its weight. */
    static final class Spring {
        static final float DURATION = .9f;
        private static final double DAMPING = .74, OMEGA = 12.0;

        static float at(float seconds) {
            if (seconds <= 0f) return 0f;
            double damped = OMEGA * Math.sqrt(1.0 - DAMPING * DAMPING), decay = Math.exp(-DAMPING * OMEGA * seconds);
            double wave = Math.cos(damped * seconds) + (DAMPING * OMEGA / damped) * Math.sin(damped * seconds);
            return (float) (1.0 - decay * wave);
        }
    }

    /** The neck between two circles, as between drops in the Dynamic Island; false once it has torn. */
    static final class Metaball {
        private static final float HALF_PI = (float) (Math.PI / 2), PI = (float) Math.PI, SPREAD = .5f, HANDLE = 2.4f;

        static boolean bridge(Path out, float x1, float y1, float r1, float x2, float y2, float r2) {
            out.reset();
            float dx = x2 - x1, dy = y2 - y1, d = (float) Math.hypot(dx, dy);
            if (r1 <= 0f || r2 <= 0f || d > r1 + r2 * 2.5f || d <= Math.abs(r1 - r2)) return false;
            float u1 = 0f, u2 = 0f;
            if (d < r1 + r2) {
                u1 = (float) Math.acos(unit((r1 * r1 + d * d - r2 * r2) / (2f * r1 * d)));
                u2 = (float) Math.acos(unit((r2 * r2 + d * d - r1 * r1) / (2f * r2 * d)));
            }
            float between = (float) Math.atan2(dy, dx), maxSpread = (float) Math.acos(unit((r1 - r2) / d));
            float a1 = between + u1 + (maxSpread - u1) * SPREAD, a2 = between - u1 - (maxSpread - u1) * SPREAD;
            float a3 = between + PI - u2 - (PI - u2 - maxSpread) * SPREAD, a4 = between - PI + u2 + (PI - u2 - maxSpread) * SPREAD;
            float p1ax = x1 + r1 * cos(a1), p1ay = y1 + r1 * sin(a1), p1bx = x1 + r1 * cos(a2), p1by = y1 + r1 * sin(a2);
            float p2ax = x2 + r2 * cos(a3), p2ay = y2 + r2 * sin(a3), p2bx = x2 + r2 * cos(a4), p2by = y2 + r2 * sin(a4);
            float total = r1 + r2;
            float handle = Math.min(SPREAD * HANDLE, (float) Math.hypot(p2ax - p1ax, p2ay - p1ay) / total) * Math.min(1f, d * 2f / total);
            float h1 = r1 * handle, h2 = r2 * handle;
            out.moveTo(p1ax, p1ay);
            out.cubicTo(p1ax + h1 * cos(a1 - HALF_PI), p1ay + h1 * sin(a1 - HALF_PI), p2ax + h2 * cos(a3 + HALF_PI), p2ay + h2 * sin(a3 + HALF_PI), p2ax, p2ay);
            out.lineTo(p2bx, p2by);
            out.cubicTo(p2bx + h2 * cos(a4 - HALF_PI), p2by + h2 * sin(a4 - HALF_PI), p1bx + h1 * cos(a2 + HALF_PI), p1by + h1 * sin(a2 + HALF_PI), p1bx, p1by);
            out.close();
            return true;
        }

        private static float cos(float a) { return (float) Math.cos(a); }
        private static float sin(float a) { return (float) Math.sin(a); }
        private static float unit(float v) { return Math.max(-1f, Math.min(1f, v)); }
    }
}
