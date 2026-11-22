package cat.ttcuz.framework;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

/**
 * The row of round stories and lives at the top of the Inbox, hidden when the setting asks: TikTok 47.7.3 puts it in
 * as the first row of the Inbox's list (X.0NOS in app:id/l8h). It is folded away -- gone, no height -- and given back
 * as it was when the setting is turned off. Looked for only after the window is laid out, at most every 200 ms.
 */
public final class InboxStories {
    private InboxStories() {}

    static final String KEY = "inbox_hide_stories";
    private static final int LIST = 0x7f0a3c57;
    private static final String ROW = "X.0NOS";
    /** a folded row's own height, to give it back */
    private static final int HEIGHT = 0x7e0c0391;
    private static final int WATCHED = 0x7e0c0392;
    private static Boolean on;

    private static SharedPreferences prefs() { Context c = Ttcuz.context(); return c == null ? null : c.getSharedPreferences(Ttcuz.PREFS, Context.MODE_PRIVATE); }

    static boolean hidden() {
        if (on == null) { SharedPreferences p = prefs(); on = p != null && p.getBoolean(KEY, false); }
        return on;
    }

    static void setHidden(boolean value) { on = value; SharedPreferences p = prefs(); if (p != null) p.edit().putBoolean(KEY, value).apply(); }

    static void watch(Activity activity) {
        final View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor == null || Boolean.TRUE.equals(decor.getTag(WATCHED))) return;
        decor.setTag(WATCHED, Boolean.TRUE);
        final long[] last = {0L};
        final boolean[] pending = {false};
        final Runnable check = () -> { pending[0] = false; last[0] = android.os.SystemClock.uptimeMillis(); apply(decor); };
        decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (pending[0]) return;
                long wait = 200L - (android.os.SystemClock.uptimeMillis() - last[0]);
                if (wait <= 0) check.run();
                else { pending[0] = true; decor.postDelayed(check, wait); }
            }
        });
    }

    private static void apply(View decor) {
        try {
            View list = decor.findViewById(LIST);
            if (!(list instanceof ViewGroup)) return;
            ViewGroup rows = (ViewGroup) list;
            boolean hide = hidden();
            for (int i = 0; i < Math.min(3, rows.getChildCount()); i++) {
                View row = rows.getChildAt(i);
                if (!ROW.equals(row.getClass().getName())) continue;
                ViewGroup.LayoutParams lp = row.getLayoutParams();
                if (hide) {
                    if (row.getVisibility() == View.GONE && lp != null && lp.height == 0) continue;
                    if (lp != null && row.getTag(HEIGHT) == null) row.setTag(HEIGHT, lp.height);
                    row.setVisibility(View.GONE);
                    if (lp != null) { lp.height = 0; row.setLayoutParams(lp); }
                } else if (row.getTag(HEIGHT) instanceof Integer) {
                    row.setVisibility(View.VISIBLE);
                    if (lp != null) { lp.height = (Integer) row.getTag(HEIGHT); row.setLayoutParams(lp); }
                    row.setTag(HEIGHT, null);
                }
            }
        } catch (Throwable error) { Diary.note("inbox stories: " + error); }
    }

    /**
     * Called from X.0NOS.onMeasure (added by the patcher): true when this row is the Inbox's stories row and it is to be
     * hidden, so it is measured to no height before it is ever drawn.
     */
    public static boolean measured(View row) {
        try { return hidden() && row.getParent() instanceof View && ((View) row.getParent()).getId() == LIST; }
        catch (Throwable error) { return false; }
    }

    /** The setting changed: every Inbox on screen now. */
    static void refresh(Activity activity) {
        View decor = activity == null || activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor != null) apply(decor);
    }
}
