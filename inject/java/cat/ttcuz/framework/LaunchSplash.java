package cat.ttcuz.framework;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The start of TikTok with the mod: on black, a light fill rises from the bottom, "ttcuz" with its mark comes up into
 * it and stays while TikTok loads; once the feed is there (its buttons are on screen) the same runs backwards -- the
 * name goes down and out, the fill sinks to the bottom -- and TikTok is under it.
 *
 * Laid over the main screen's window from its creation, each time TikTok's main screen is made anew. Everything moves by translation and alpha
 * on a hardware layer: the RenderThread draws it with no layout and no redraw of TikTok under it, so TikTok's own start
 * is not slowed. It never waits longer than MOST for TikTok; a tap is not passed through while it is up.
 */
final class LaunchSplash {
    private LaunchSplash() {}

    static final String KEY = "launch_splash";
    private static final long LEAST = 900, MOST = 7000;
    private static final int FILL = 0xFFDCDCD8;

    static boolean on(Context context) {
        // off unless switched on: the start screen itself is ttcuz now (build.py, SPLASH_LOGO)
        return context.getSharedPreferences(Ttcuz.PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false);
    }

    static void set(Context context, boolean value) {
        context.getSharedPreferences(Ttcuz.PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply();
    }

    private static java.lang.ref.WeakReference<Activity> waiting = new java.lang.ref.WeakReference<>(null);

    /**
     * TikTok's main screen was created: the first time in this process, and not brought back from saved state. Its
     * window is not touched yet -- TikTok may still ask for window features, which a decor made now would refuse.
     */
    static void created(Activity activity, boolean restored) {
        // every new main screen, not the first of the process: left with Back, TikTok's process often lives on, and
        // the next start (a new main screen in it) went without the splash -- it showed every other time
        if (restored || !activity.getClass().getName().equals("com.ss.android.ugc.aweme.main.MainActivity")) return;
        waiting = new java.lang.ref.WeakReference<>(activity);
    }

    /** It is started: its content is set and nothing is drawn yet, so the cover is there from the very first frame. */
    static void started(Activity activity) {
        if (waiting.get() != activity) return;
        waiting.clear();
        try {
            if (!on(activity) || !Motion.enabled(activity)) return;
            show(activity);
        } catch (Throwable error) { Diary.note("launch splash: " + error); }
    }

    private static void show(Activity activity) {
        final View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof FrameLayout)) return;
        final Context c = activity;
        final float d = c.getResources().getDisplayMetrics().density;

        final FrameLayout cover = new FrameLayout(c);
        cover.setBackgroundColor(Color.BLACK);
        cover.setClickable(true);   // TikTok under it takes no tap until it is gone
        cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        final View fill = new View(c);
        fill.setBackgroundColor(FILL);
        cover.addView(fill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final LinearLayout brand = new LinearLayout(c);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(c);
        android.graphics.Bitmap glyph = Panel.glyph(c);
        if (glyph != null) { mark.setImageBitmap(glyph); mark.setColorFilter(new PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)); }
        brand.addView(mark, new LinearLayout.LayoutParams(Math.round(34 * d), Math.round(34 * d)));
        TextView word = new TextView(c);
        word.setText("ttcuz");
        word.setTextColor(Color.BLACK);
        word.setTextSize(30);
        word.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        word.setIncludeFontPadding(false);
        LinearLayout.LayoutParams wordAt = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wordAt.leftMargin = Math.round(10 * d);
        brand.addView(word, wordAt);
        brand.setAlpha(0f);
        cover.addView(brand, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        cover.setTranslationZ(10000f);
        ((FrameLayout) decor).addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final long started = android.os.SystemClock.uptimeMillis();
        final float lift = 14 * d;
        final android.view.animation.Interpolator move = new android.view.animation.PathInterpolator(.65f, 0f, .35f, 1f),
                soft = new android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f);

        // in: once the cover has its size, the fill rises from the bottom edge, then the name comes up into it
        cover.post(() -> {
            int h = Math.max(1, cover.getHeight());
            fill.setTranslationY(h);
            fill.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            brand.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            fill.animate().translationY(0f).setDuration(Motion.time(520L)).setInterpolator(move)
                    .withEndAction(() -> fill.setLayerType(View.LAYER_TYPE_NONE, null)).start();
            brand.setTranslationY(lift);
            brand.animate().alpha(1f).translationY(0f).setStartDelay(Motion.time(420L)).setDuration(Motion.time(380L)).setInterpolator(soft).start();
        });

        // out: when TikTok's feed is on screen (or it took too long), backwards
        final boolean[] leaving = {false};
        final ViewTreeObserver.OnPreDrawListener[] watch = new ViewTreeObserver.OnPreDrawListener[1];
        final Runnable leave = () -> {
            if (leaving[0] || !cover.isAttachedToWindow()) return;
            leaving[0] = true;
            if (watch[0] != null && decor.getViewTreeObserver().isAlive()) decor.getViewTreeObserver().removeOnPreDrawListener(watch[0]);
            int h = Math.max(1, cover.getHeight());
            cover.setBackgroundColor(Color.TRANSPARENT);   // TikTok shows wherever the fill has gone
            fill.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            brand.animate().cancel();
            brand.animate().alpha(0f).translationY(lift).setStartDelay(0).setDuration(Motion.time(260L)).setInterpolator(soft).start();
            fill.animate().translationY(h).setStartDelay(Motion.time(200L)).setDuration(Motion.time(520L)).setInterpolator(move)
                    .withEndAction(() -> { if (cover.getParent() instanceof ViewGroup) ((ViewGroup) cover.getParent()).removeView(cover); }).start();
        };
        watch[0] = () -> {
            long now = android.os.SystemClock.uptimeMillis();
            // the feed's buttons are TikTok's sign that its first video is up; cheap to ask (a handful of views)
            if (now - started >= LEAST && (RailPresence.onScreen(decor) || !activityFront(decor))) decor.post(leave);
            return true;
        };
        decor.getViewTreeObserver().addOnPreDrawListener(watch[0]);
        decor.postDelayed(leave, MOST);
    }

    /** Whether the window is still in front (TikTok opened over itself by a link: there is no feed to wait for). */
    private static boolean activityFront(View decor) {
        return decor.hasWindowFocus() || decor.getWindowVisibility() == View.VISIBLE;
    }
}
