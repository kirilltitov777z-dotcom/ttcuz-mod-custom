package cat.ttcuz.framework;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.Window;
import android.view.WindowManager;

public final class Brightness {
    static final String KEY = "own_brightness";
    private static volatile Boolean cached;
    private static volatile boolean told;

    private Brightness() {
    }

    public static boolean isEnabled() {
        Boolean bool = cached;
        if (bool != null) {
            return bool.booleanValue();
        }
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return true;
        }
        boolean z = prefs.getBoolean(KEY, true);
        cached = Boolean.valueOf(z);
        return z;
    }

    public static void setEnabled(boolean z) {
        cached = Boolean.valueOf(z);
        SharedPreferences prefs = prefs();
        if (prefs != null) {
            prefs.edit().putBoolean(KEY, z).apply();
        }
    }

    private static SharedPreferences prefs() {
        Context context = Ttcuz.context();
        if (context == null) {
            return null;
        }
        try {
            return context.getSharedPreferences("ttcuz", 0);
        } catch (Throwable unused) {
            return null;
        }
    }

    public static void setAttributes(Window window, WindowManager.LayoutParams layoutParams) {
        if (layoutParams != null) {
            try {
                if (layoutParams.screenBrightness >= 0.0f && isEnabled()) {
                    if (!told) {
                        told = true;
                        Diary.detail("brightness: a window asked for " + layoutParams.screenBrightness + " and was not given it");
                    }
                    layoutParams.screenBrightness = -1.0f;
                }
            } catch (Throwable unused) {
            }
        }
        // Android lays the whole window out again on every setAttributes, even one that changes nothing; TikTok sends
        // the same parameters again as one switches tabs, 5-9 ms of relayout each. The same ones are not passed on.
        if (layoutParams != null && unchanged(window, layoutParams)) return;
        window.setAttributes(layoutParams);
        if (layoutParams != null) synchronized (applied) { WindowManager.LayoutParams copy = new WindowManager.LayoutParams(); copy.copyFrom(layoutParams); applied.put(window, copy); }
    }

    /** What each window was last given through here (a copy: TikTok often edits the window's own object in place). */
    private static final java.util.Map<Window, WindowManager.LayoutParams> applied = new java.util.WeakHashMap<>();

    /**
     * Whether these parameters are the ones the window already has. The window's own object is the baseline unless
     * that is the very object passed (edited in place): then what was last applied through here is.
     */
    private static boolean unchanged(Window window, WindowManager.LayoutParams wanted) {
        try {
            WindowManager.LayoutParams current = window.getAttributes(), base;
            if (wanted == current) { synchronized (applied) { base = applied.get(window); } if (base == null) return false; }
            else base = current;
            WindowManager.LayoutParams probe = new WindowManager.LayoutParams();
            probe.copyFrom(base);
            return probe.copyFrom(wanted) == 0;
        } catch (Throwable error) { return false; }
    }

    public static boolean flat() {
        return isEnabled();
    }

    static void apply(Activity activity) {
        if (activity == null || !isEnabled()) {
            return;
        }
        try {
            Window window = activity.getWindow();
            if (window == null) {
                return;
            }
            WindowManager.LayoutParams attributes = window.getAttributes();
            if (attributes != null && attributes.screenBrightness >= 0.0f) {
                attributes.screenBrightness = -1.0f;
                window.setAttributes(attributes);
            }
            if (Build.VERSION.SDK_INT >= 26 && window.getColorMode() == 2) {
                window.setColorMode(0);
            }
            if (Build.VERSION.SDK_INT >= 35) {
                window.setDesiredHdrHeadroom(1.0f);
            }
        } catch (Throwable th) {
            Diary.detail("brightness: " + String.valueOf(th));
        }
    }
}
