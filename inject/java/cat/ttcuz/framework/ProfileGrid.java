package cat.ttcuz.framework;

import android.graphics.Outline;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import java.lang.ref.WeakReference;

/**
 * Rounds the video cards of a profile grid, on every profile. The grid is found by its look rather than
 * by a class name TikTok may change: a recycling list showing at least two cells about a third of its
 * width wide and taller than wide (one cell is enough for an actual RecyclerView). Wide rows are left as they are.
 */
final class ProfileGrid {
    private ProfileGrid() {}

    private static final int WATCHED = 0x7e0c0338, ROUNDED = 0x7e0c0339;
    /** Every tab of a profile (videos, reposts, favourites, likes) is a list of its own: all of them are kept, not only the first found. */
    private static final java.util.List<WeakReference<ViewGroup>> grids = new java.util.ArrayList<>();
    private static long searched;
    /**
     * As the groups of the mod's menu: a row of cards reads as one piece -- its outer corners round, the corners where
     * two cards meet nearly square, a hair between them. Where an outline can only be one radius (before Android 13) the
     * cards keep the one radius they had.
     */
    private static final ViewOutlineProvider CORNERS = new ViewOutlineProvider() {
        @Override public void getOutline(View view, Outline outline) {
            float density = view.getResources().getDisplayMetrics().density;
            int gap = Math.round(1.5f * density);
            int l = gap, t = gap, r = Math.max(gap + 1, view.getWidth() - gap), b = Math.max(gap + 1, view.getHeight() - gap);
            if (android.os.Build.VERSION.SDK_INT < 33 || !(view.getParent() instanceof View)) { outline.setRoundRect(l, t, r, b, 14f * density); return; }
            int column = column(view, ((View) view.getParent()).getWidth()), columns = columns(view, ((View) view.getParent()).getWidth());
            float outer = 18f * density, inner = 5f * density;
            float left = column == 0 ? outer : inner, right = column == columns - 1 ? outer : inner;
            android.graphics.Path shape = new android.graphics.Path();
            shape.addRoundRect(new android.graphics.RectF(l, t, r, b), new float[]{left, left, right, right, right, right, left, left}, android.graphics.Path.Direction.CW);
            outline.setPath(shape);
        }
    };

    private static int columns(View cell, int listWidth) { return Math.max(1, Math.round(listWidth / (float) Math.max(1, cell.getWidth()))); }
    private static int column(View cell, int listWidth) {
        int columns = columns(cell, listWidth);
        return Math.max(0, Math.min(columns - 1, Math.round(cell.getLeft() / (listWidth / (float) columns))));
    }

    private static boolean onScreen(View view) {
        if (view == null || !view.isAttachedToWindow() || !view.isShown()) return false;
        android.graphics.Rect r = new android.graphics.Rect();
        return view.getGlobalVisibleRect(r) && r.right > r.left && r.bottom > r.top;
    }

    private static boolean known(ViewGroup list) {
        for (int i = grids.size() - 1; i >= 0; i--) {
            ViewGroup held = grids.get(i).get();
            if (held == null || !held.isAttachedToWindow()) { grids.remove(i); continue; }
            if (held == list) return true;
        }
        return false;
    }

    private static boolean anyShowing() {
        for (WeakReference<ViewGroup> ref : grids) if (onScreen(ref.get())) return true;
        return false;
    }

    /** Called while a profile header is bound: looks for lists that are showing and keeps rounding every one of them. */
    static void tick(View anchor) {
        try {
            long time = SystemClock.uptimeMillis();
            // the window is walked to find the tabs' lists: often while none is known, rarely once they are (a new tab
            // is a rare thing; each known list keeps itself rounded on its own frames)
            if (anchor == null || time - searched < (anyShowing() ? 1500 : 300)) return;
            searched = time;
            View root = anchor.getRootView();
            // Scrolling the header away must not stop the work: a tab that is already known keeps the profile "on screen".
            if (!ProfileLayout.visible(root) && !anyShowing()) return;
            java.util.List<ViewGroup> found = new java.util.ArrayList<>();
            find(root, found, 0);
            for (ViewGroup list : found) {
                if (!known(list)) grids.add(new WeakReference<>(list));
                watch(list);
                round(list);
            }
        } catch (Throwable error) {
            Diary.note("profile grid: " + error);
        }
    }

    private static void watch(final ViewGroup list) {
        if (Boolean.TRUE.equals(list.getTag(WATCHED))) return;
        VideoOpenTransition.watch(list);
        list.setTag(WATCHED, true);
        list.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {android.os.Trace.beginSection("ttcuz profile grid");try{
                if (list.isShown()) round(list);
                ProfileSort.place(list);
                return true;
            }finally{android.os.Trace.endSection();}}
        });
    }

    private static void round(ViewGroup list) {
        int width = list.getWidth();
        if (width <= 0) return;
        for (int i = 0; i < list.getChildCount(); i++) {
            View cell = list.getChildAt(i);
            if (!isCell(cell, width)) continue;
            // a recycled card can come back in another column: its corners follow it
            Integer at = column(cell, width), was = cell.getTag(ROUNDED) instanceof Integer ? (Integer) cell.getTag(ROUNDED) : null;
            if (at.equals(was) && cell.getOutlineProvider() == CORNERS) continue;
            cell.setTag(ROUNDED, at);
            if (cell.getOutlineProvider() != CORNERS) { cell.setOutlineProvider(CORNERS); cell.setClipToOutline(true); }
            else cell.invalidateOutline();
        }
    }

    private static boolean isCell(View cell, int listWidth) {
        int w = cell.getWidth();
        return w > listWidth * .28f && w < listWidth * .4f && cell.getHeight() > w * 1.1f;
    }

    /** Hit-test only registered visible lists, once on down/up; never walk the window on animation frames. */
    static View cellAt(View root, float screenX, float screenY) {
        if (grids.isEmpty()) return null;
        android.graphics.Rect visible = new android.graphics.Rect();
        int[] origin = new int[2];
        for (int i = grids.size() - 1; i >= 0; i--) {
            ViewGroup list = grids.get(i).get();
            if (list == null || !list.isAttachedToWindow()) { grids.remove(i); continue; }
            if (!ProfileLayout.onScreen(list, root.getRootView())) continue;
            for (int j = list.getChildCount() - 1; j >= 0; j--) {
                View cell = list.getChildAt(j);
                if (!isCell(cell, list.getWidth()) || !cell.isShown() || !cell.getLocalVisibleRect(visible)) continue;
                cell.getLocationOnScreen(origin);
                if (visible.contains((int)(screenX - origin[0]), (int)(screenY - origin[1]))) return cell;
            }
        }
        return null;
    }

    private static boolean recycler(View view) {
        for (Class<?> type = view.getClass(); type != null && type != View.class; type = type.getSuperclass())
            if (type.getName().endsWith("RecyclerView")) return true;
        return false;
    }

    /** Collect every card list, including native RecyclerViews with one private/liked post. */
    private static void find(View view, java.util.List<ViewGroup> out, int depth) {
        if (!(view instanceof ViewGroup) || !view.isShown() || depth > 40) return;
        ViewGroup group = (ViewGroup) view;
        if (group.getWidth() > 0) {
            int cells = 0;
            for (int i = 0; i < group.getChildCount(); i++) if (isCell(group.getChildAt(i), group.getWidth())) cells++;
            if ((cells >= 2 && (recycler(group) || onScreen(group))) || (cells == 1 && recycler(group) && onScreen(group))) { out.add(group); return; }
        }
        for (int i = 0; i < group.getChildCount(); i++) find(group.getChildAt(i), out, depth + 1);
    }
}
