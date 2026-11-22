package cat.ttcuz.framework;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Sorting a profile's Reposts, Favourites and Liked tabs: a small round button in the grid's top right corner, as
 * TikTok's own floating controls are drawn, opens a choice -- newest first (TikTok's own order), oldest first, most
 * liked, most viewed. The tab's remaining pages are loaded first (as RepostSelect does: the list is moved to its end,
 * which makes TikTok ask for the next page, until no more come), then the videos are put in order in the list TikTok's
 * adapter draws from, and it is told to draw them again.
 */
final class ProfileSort {
    private ProfileSort() {}

    static final int NEWEST = 0, OLDEST = 1, LIKED = 2, VIEWED = 3;
    private static final String AWEME = "com.ss.android.ugc.aweme.feed.model.Aweme";

    /** grid → its button, laid in the window's decor and moved with the grid on every frame */
    private static final Map<ViewGroup, View> buttons = new WeakHashMap<>(), archives = new WeakHashMap<>();
    /** grid → the order TikTok gave it (the items as they came), to go back to "newest first" */
    private static final Map<ViewGroup, List<Object>> original = new WeakHashMap<>();
    private static final Map<ViewGroup, Boolean> supported = new WeakHashMap<>();
    static boolean busy;
    private static final Rect seen = new Rect();

    /** Whether a grid is one of the three tabs (its fragment's class), decided once per grid. */
    private static boolean sortable(ViewGroup list) {
        Boolean known = supported.get(list);
        if (known != null) return known;
        Object fragment = RepostSelect.fragmentOf(list);
        if (fragment == null) return false; // asked again when it is attached
        String name = fragment.getClass().getName();
        boolean is = name.endsWith("RepostProfileListFragment") || name.endsWith("AwemeListFragmentImpl")
                || name.contains("Collect");
        supported.put(list, is);
        return is;
    }

    /** On every frame of a profile grid (ProfileGrid's watch): the button follows the grid, shown while it is. */
    static void place(ViewGroup list) {
        try {
            boolean show = list.isShown() && list.getGlobalVisibleRect(seen) && seen.height() > dp(list, 80) && sortable(list);
            follow(list, buttons, show, 0, "swap_vert", "Сортировка", () -> choose(list));
            follow(list, archives, show, 1, "download", "Скачать все видео архивом", () -> ProfileArchive.start(list));
        } catch (Throwable error) { Diary.note("profile sort: " + error); }
    }

    /** One button of the row in the grid's top right corner, the slot-th from the right. */
    private static void follow(ViewGroup list, Map<ViewGroup, View> made, boolean show, int slot, String glyph, String label, Runnable action) {
        View button = made.get(list);
        if (!show) { if (button != null && button.getVisibility() != View.GONE) button.setVisibility(View.GONE); return; }
        if (button == null) {
            button = make(list, glyph, label, action);
            if (button == null) return;
            made.put(list, button);
        }
        View decor = button.getRootView();
        int[] origin = new int[2];
        decor.getLocationOnScreen(origin);
        int size = dp(list, 36), margin = dp(list, 10);
        // whole pixels: a button between pixels is drawn soft and shimmers while the grid moves
        float x = Math.round(seen.right - origin[0] - (size + margin) * (slot + 1)), y = Math.round(seen.top - origin[1] + margin);
        if (button.getTranslationX() != x) button.setTranslationX(x);
        if (button.getTranslationY() != y) button.setTranslationY(y);
        if (button.getVisibility() != View.VISIBLE) button.setVisibility(View.VISIBLE);
    }

    private static View make(final ViewGroup list, String glyph, String label, final Runnable action) {
        Context context = list.getContext();
        View root = list.getRootView();
        if (!(root instanceof FrameLayout)) return null;
        ImageView button = new ImageView(context);
        button.setImageDrawable(Symbols.drawable(glyph, 0xFFFFFFFF));
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // a 20 dp glyph in the middle of a 36 dp circle, the same 8 dp all round
        int pad = (dp(list, 36) - dp(list, 20)) / 2;
        button.setPadding(pad, pad, pad, pad);
        button.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        GradientDrawable round = new GradientDrawable();
        round.setShape(GradientDrawable.OVAL);
        round.setColor(0x8C000000);
        button.setBackground(round);
        button.setElevation(dp(list, 3));
        button.setContentDescription(Text.t(label));
        button.setOnClickListener(v -> action.run());
        Motion.press(button);
        int size = dp(list, 36);
        ((FrameLayout) root).addView(button, new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.LEFT));
        return button;
    }

    private static void choose(final ViewGroup list) {
        if (busy) return;
        String[] names = {Text.t("Сначала новые"), Text.t("Сначала старые"), Text.t("Популярные"), Text.t("Больше просмотров")};
        new ModDialog.Builder(list.getContext()).setTitle(Text.t("Сортировка"))
                .setItems(names, (dialog, which) -> sort(list, which)).show();
    }

    // ------------------------------------------------- the list TikTok's adapter draws from

    static Object adapter(ViewGroup list) {
        try { return list.getClass().getMethod("getAdapter").invoke(list); } catch (Throwable error) { return null; }
    }

    private static int count(ViewGroup list) {
        Object adapter = adapter(list);
        try { return adapter == null ? 0 : (Integer) adapter.getClass().getMethod("getItemCount").invoke(adapter); }
        catch (Throwable error) { return 0; }
    }

    /** The adapter's list of videos: the List field (of it or its parents) whose items lead to the most videos. */
    @SuppressWarnings("unchecked")
    static List<Object> items(Object adapter) {
        List<Object> best = null;
        int found = 0;
        for (Class<?> type = adapter.getClass(); type != null && type != Object.class; type = type.getSuperclass())
            for (Field field : type.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(adapter);
                    if (!(value instanceof List)) continue;
                    List<Object> list = (List<Object>) value;
                    int videos = 0;
                    for (int i = 0; i < list.size() && i < 6; i++) if (aweme(list.get(i), 0) != null) videos++;
                    if (videos > found) { found = videos; best = list; }
                } catch (Throwable ignored) { }
            }
        return best;
    }

    /** The video an item of the list is, or holds (two fields deep at most). */
    static Object aweme(Object item, int depth) {
        if (item == null) return null;
        if (item.getClass().getName().equals(AWEME)) return item;
        if (depth >= 2 || item instanceof CharSequence || item instanceof Number || item.getClass().isArray()) return null;
        for (Class<?> type = item.getClass(); type != null && type != Object.class; type = type.getSuperclass())
            for (Field field : type.getDeclaredFields()) {
                if (field.getType().isPrimitive() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(item);
                    if (value != null && value.getClass().getName().equals(AWEME)) return value;
                    if (value != null && !(value instanceof View) && value.getClass().getName().startsWith("X.")) {
                        Object inner = aweme(value, depth + 1);
                        if (inner != null) return inner;
                    }
                } catch (Throwable ignored) { }
            }
        return null;
    }

    private static long number(Object aweme, String getter, String count) {
        try {
            Object holder = getter == null ? aweme : NativeRead.get(aweme, getter);
            return NativeRead.number(NativeRead.get(holder, count));
        } catch (Throwable error) { return 0L; }
    }

    // ------------------------------------------------- sorting

    private static void sort(final ViewGroup list, final int how) {
        busy = true;
        Screen.say(Text.t("Загружаем все видео..."));
        loadAll(list, () -> {
            busy = false;
            try {
                Object adapter = adapter(list);
                List<Object> items = adapter == null ? null : items(adapter);
                if (items == null || items.isEmpty()) { Screen.say(Text.t("Не удалось отсортировать этот список")); return; }
                List<Object> first = original.get(list);
                if (first == null || !first.containsAll(items)) { first = new ArrayList<>(items); original.put(list, first); }
                final Map<Object, Integer> place = new IdentityHashMap<>();
                for (int i = 0; i < first.size(); i++) place.put(first.get(i), i);
                Comparator<Object> by;
                if (how == OLDEST) by = (a, b) -> Integer.compare(rank(place, b), rank(place, a));
                else if (how == LIKED) by = (a, b) -> Long.compare(number(aweme(b, 0), "getStatistics", "getDiggCount"), number(aweme(a, 0), "getStatistics", "getDiggCount"));
                else if (how == VIEWED) by = (a, b) -> Long.compare(number(aweme(b, 0), "getStatistics", "getPlayCount"), number(aweme(a, 0), "getStatistics", "getPlayCount"));
                else by = (a, b) -> Integer.compare(rank(place, a), rank(place, b));
                List<Object> sorted = new ArrayList<>(items);
                Collections.sort(sorted, by);
                // the same list object TikTok holds, in place: nothing of its own is replaced
                for (int i = 0; i < sorted.size(); i++) items.set(i, sorted.get(i));
                adapter.getClass().getMethod("notifyDataSetChanged").invoke(adapter);
                list.getClass().getMethod("scrollToPosition", int.class).invoke(list, 0);
                Screen.say(Text.t("Отсортировано") + ": " + items.size());
            } catch (Throwable error) {
                Diary.note("profile sort: " + error);
                Screen.say(Text.t("Не удалось отсортировать этот список"));
            }
        });
    }

    private static int rank(Map<Object, Integer> place, Object item) {
        Integer at = place.get(item);
        return at == null ? Integer.MAX_VALUE : at;
    }

    /** Every page of the tab, loaded the way TikTok does it: its last card shown, again while the count grows. */
    static void loadAll(final ViewGroup list, final Runnable then) {
        final int[] last = {-1}, still = {0}, rounds = {0};
        list.post(new Runnable() {
            @Override public void run() {
                if (!list.isAttachedToWindow()) { busy = false; return; }
                int size = count(list);
                still[0] = size == last[0] ? still[0] + 1 : 0;
                last[0] = size;
                if (still[0] >= 5 || ++rounds[0] > 200) { then.run(); return; }
                try { list.getClass().getMethod("scrollToPosition", int.class).invoke(list, Math.max(0, size - 1)); }
                catch (Throwable error) { then.run(); return; }
                list.postDelayed(this, 600L);
            }
        });
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
}
