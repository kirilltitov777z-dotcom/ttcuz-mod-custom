package cat.ttcuz.framework;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The status bar hidden while the video feed is on screen: it comes back by itself on the inbox, a profile or any
 * other screen, and a swipe from the top shows it for a moment, as in any full-screen app. "The feed" is told by its
 * button rail being on screen (RailPresence), checked a moment after each layout of the window.
 */
final class StatusBar {
    private StatusBar() {}

    static final String KEY = "feed_hide_status_bar";
    private static volatile Boolean on;
    /** Windows watched, and whether this hid their bar. */
    private static final Map<View, Boolean> hid = new WeakHashMap<>();
    private static final int PENDING = 0x7e0c0362;

    static boolean on() {
        Boolean now = on;
        if (now == null) {
            Context c = Ttcuz.context();
            if (c == null) return false;
            on = now = c.getSharedPreferences(Ttcuz.PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false);
        }
        return now;
    }

    static void set(boolean value) {
        Context c = Ttcuz.context();
        if (c != null) c.getSharedPreferences(Ttcuz.PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply();
        on = value;
    }

    /** A TikTok screen came to the front: its window is watched from now on. */
    static void watch(Activity activity) {
        // shortcut: Android 11+ only (WindowInsetsController); older phones keep the bar
        if (android.os.Build.VERSION.SDK_INT < 30 || activity.getClass().getName().startsWith("cat.ttcuz.")) return;
        View decor = activity.getWindow().getDecorView();
        boolean known;
        synchronized (hid) { known = hid.containsKey(decor); if (!known) hid.put(decor, Boolean.FALSE); }
        if (!known) decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> later(decor));
        later(decor);
    }

    private static void later(View decor) {
        if (decor.getTag(PENDING) != null) return;
        decor.setTag(PENDING, Boolean.TRUE);
        decor.postDelayed(() -> { decor.setTag(PENDING, null); check(decor); }, 200L);
    }

    private static void check(View decor) {
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        WindowInsetsController bars = decor.getWindowInsetsController();
        WindowInsets insets = decor.getRootWindowInsets();
        if (bars == null || insets == null) return;
        boolean feed = on() && decor.hasWindowFocus() && RailPresence.onScreen(decor);
        boolean ours;
        synchronized (hid) { ours = Boolean.TRUE.equals(hid.get(decor)); }
        if (feed) {
            if (insets.isVisible(WindowInsets.Type.statusBars())) {
                bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                bars.hide(WindowInsets.Type.statusBars());
            }
            if (!ours) synchronized (hid) { hid.put(decor, Boolean.TRUE); }
        } else if (ours && decor.hasWindowFocus()) {
            // only a bar this hid is shown again; TikTok's own full-screen pages are left as they are
            bars.show(WindowInsets.Type.statusBars());
            synchronized (hid) { hid.put(decor, Boolean.FALSE); }
        }
    }
}
