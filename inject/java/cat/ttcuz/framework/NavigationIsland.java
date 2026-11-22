package cat.ttcuz.framework;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The bottom island of lens.html over TikTok's five-tab row: the liquid glass bar (LensBar) or the Material 3 one
 * (M3Bar), in a view of its own laid over the bottom of the window. TikTok's row stays exactly where it is, only
 * invisible (alpha 0): it keeps its routing, badges and accessibility, and a tab chosen on the island is a tap
 * replayed on that row's cell, so TikTok opens it itself. Switched off, the row gets its alpha back and nothing else
 * was ever changed.
 */
final class NavigationIsland {
    private NavigationIsland() {}

    /** What NavigationIsland asks of either island. Coordinates are the island view's px; lengths in dp. */
    interface Bar {
        /** The room the island may use: TikTok's row, px of the island's view. */
        void place(float left, float top, float right, float bottom);
        /** The tabs as TikTok's row has them, and the chosen one (-1: unknown), shown at once, not animated to. */
        void tabs(int[] roles, String[] names, int create, int chosen);
        void badges(String[] badges);
        void select(int tab);
        void settings(boolean dark, boolean labels, float glass);
        boolean hit(float x, float y);
        /** A finger landed on the bar: the create button when that is it (it opens on release), else -1. */
        int down(float x);
        void move(float x);
        /** The finger left: the tab to open (-1: none). */
        int up();
        void cancel();
        /** Called on every pre-draw while shown: a chance to record what is behind. */
        void capture();
        /** The tab under a finger at x (-1: none). */
        int tabAt(float x);
        /** Anti-burn: how bright the tabs' icons, names and marks are now (1 full); the bar eases there itself. */
        void dim(float level);
    }

    // ------------------------------------------------------------------ settings

    /** Held on Inbox: TikTok's unread count on it is no longer shown (asked yes or no). */
    static final String KEY_NO_INBOX_COUNT = "island_hide_inbox_count";
    static boolean inboxCountHidden() { SharedPreferences p = prefs(); return p != null && p.getBoolean(KEY_NO_INBOX_COUNT, false); }

    public static final String KEY = "navigation_island", KEY_STYLE = "island_style", KEY_NO_LABELS = "island_hide_labels", KEY_GLASS = "island_glass";
    /** The two islands of the demo. */
    static final int GLASS = 0, MATERIAL = 1;
    private static volatile Boolean on, labelsHidden;
    private static volatile int style = -1, glass = -1;

    private static SharedPreferences prefs() { Context c = Ttcuz.context(); return c == null ? null : c.getSharedPreferences(Ttcuz.PREFS, Context.MODE_PRIVATE); }

    static boolean isEnabled() {
        Boolean known = on; if (known != null) return known;
        SharedPreferences p = prefs(); if (p == null) return true;
        boolean value = true; try { value = p.getBoolean(KEY, true); } catch (Throwable ignored) {}
        return on = value;
    }
    static void setEnabled(boolean value) { on = value; put(KEY, value); changed(); }

    static int style() {
        int known = style; if (known >= 0) return known;
        SharedPreferences p = prefs(); if (p == null) return GLASS;
        return style = p.getInt(KEY_STYLE, GLASS) == MATERIAL ? MATERIAL : GLASS;
    }
    static void setStyle(int value) { style = value; SharedPreferences p = prefs(); if (p != null) p.edit().putInt(KEY_STYLE, value).apply(); changed(); }

    /** The demo's «Названия» switch: the tab names under the icons (glass) or beside the chosen one (Material 3). */
    static boolean labels() {
        Boolean known = labelsHidden; if (known != null) return !known;
        SharedPreferences p = prefs(); if (p == null) return true;
        return !(labelsHidden = p.getBoolean(KEY_NO_LABELS, false));
    }
    static void setLabels(boolean value) { labelsHidden = !value; put(KEY_NO_LABELS, !value); changed(); }

    /** The demo's slider, «прозрачнее» 0 to «матовее» 100. */
    static int glass() {
        int known = glass; if (known >= 0) return known;
        SharedPreferences p = prefs(); if (p == null) return 70;
        return glass = Math.max(0, Math.min(100, p.getInt(KEY_GLASS, 70)));
    }
    static void setGlass(int value) { glass = value; SharedPreferences p = prefs(); if (p != null) p.edit().putInt(KEY_GLASS, value).apply(); changed(); }

    static void reloadSettings() { on = null; labelsHidden = null; style = -1; glass = -1; changed(); }

    private static void put(String key, boolean value) { SharedPreferences p = prefs(); if (p != null) p.edit().putBoolean(key, value).apply(); }

    /** Applied on each screen's next frame: off gives TikTok its row back, another style builds the other island. */
    private static void changed() {
        for (State state : new ArrayList<>(states.values())) { state.dirty = true; View root = state.root.get(); if (root != null) root.invalidate(); }
    }

    /** The glass tint setting picks the theme; auto follows the system's dark mode. */
    private static boolean dark(Context context) {
        int tint = Blur.tint();
        if (tint == Blur.TINT_DARK) return true;
        if (tint == Blur.TINT_LIGHT) return false;
        return (context.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    // ------------------------------------------------------------------ screens

    private static final Map<View, State> states = new WeakHashMap<>();
    /** TikTok 47.7.3's seek bar over a video (com.ss.android.ugc.aweme.feed.widget.LineProgressBar, app:id/ngb). */
    private static final int SEEK_BAR = 0x7f0a4850;
    private static WeakReference<View> active = new WeakReference<>(null);
    static boolean isRail(View view) { return active.get() == view; }

    static void watch(Activity activity) {
        // the feed's scroll effect goes on the main screen's feeds
        try { FeedScroll.attach(activity); } catch (Throwable error) { Diary.note("feed scroll: " + error); }
        if (!activity.getClass().getName().equals("com.ss.android.ugc.aweme.main.MainActivity")) return;
        View root = activity.getWindow().getDecorView(); State state = states.get(root);
        if (state == null) { state = new State(root); states.put(root, state); }
        state.screen = new WeakReference<>(activity);
        state.attach();
        IslandTouch.hook(activity);
    }
    static void stop(Activity activity) { State state = states.get(activity.getWindow().getDecorView()); if (state != null) { state.detach(); state.cancel(); } }
    static void destroy(Activity activity) { State state = states.remove(activity.getWindow().getDecorView()); if (state != null) { state.detach(); state.restore(); } }

    /** From the window's touch hook: true when the island took the event (TikTok never sees it). */
    static boolean touch(Activity activity, MotionEvent event, Window.Callback base) {
        State state = states.get(activity.getWindow().getDecorView());
        return state != null && state.touch(event, base);
    }

    // ------------------------------------------------------------------ TikTok's row

    static int role(String text) {
        if(text==null)return -1;String label=text.trim().toLowerCase(Locale.ROOT);
        if(label.equals("главная")||label.equals("home")||label.equals("for you")||label.equals("accueil")||label.equals("pour toi")||label.equals("головна"))return 0;
        if(label.equals("сообщество")||label.equals("community")||label.equals("друзья")||label.equals("friends")||label.equals("amis")||label.equals("communauté")||label.equals("communaute")||label.equals("découvrir")||label.equals("decouvrir")||label.equals("друзі")||label.equals("explore"))return 1;
        if(label.equals("входящие")||label.equals("inbox")||label.equals("boîte de réception")||label.equals("boite de reception")||label.equals("messages")||label.equals("вхідні"))return 3;
        if(label.equals("профиль")||label.equals("profile")||label.equals("profil")||label.equals("профіль"))return 4;
        return -1;
    }
    static int role(View view,int depth) {
        if(depth>5)return -1;
        int found=role(view instanceof TextView?String.valueOf(((TextView)view).getText()):String.valueOf(view.getContentDescription()));
        if(found>=0)return found;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){found=role(group.getChildAt(i),depth+1);if(found>=0)return found;}}
        return -1;
    }
    private static boolean selected(View view,int depth) {
        if(view.isSelected()||view.isActivated())return true;
        if(depth<5&&view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(selected(group.getChildAt(i),depth+1))return true;}
        return false;
    }
    /** The row's tabs, left to right: each a named tab, at most one unnamed (the create button), Home and Profile always there. */
    private static ArrayList<View> cells(ViewGroup rail) {
        ArrayList<View> cells=new ArrayList<>();
        for(int i=0;i<rail.getChildCount();i++){View child=rail.getChildAt(i);if(child.getVisibility()==View.VISIBLE&&child.getWidth()>0)cells.add(child);}
        int count=cells.size();if(count<3||count>5)return null;
        int mask=0,unnamed=0;float last=Float.NEGATIVE_INFINITY,widest=rail.getWidth()*.29f*5f/count;
        for(View cell:cells){if(cell.getWidth()>widest||cell.getHeight()<36*rail.getResources().getDisplayMetrics().density||cell.getX()<last)return null;last=cell.getX();int role=role(cell,0);
            if(role<0)unnamed++;else if((mask&1<<role)!=0)return null;else mask|=1<<role;}
        return unnamed<=1&&(mask&17)==17&&Integer.bitCount(mask)+unnamed==count?cells:null;
    }
    private static ViewGroup find(View view,int[] top,int height,int width,int depth,int[] budget) {
        if(depth>22||budget[0]--<=0||!view.isShown()||!(view instanceof ViewGroup))return null;
        ViewGroup group=(ViewGroup)view;int[] at=new int[2];view.getLocationOnScreen(at);
        if(at[1]+view.getHeight()<top[1]+height*.70f)return null;
        float unit=view.getResources().getDisplayMetrics().density;
        if(view.getWidth()>width*.75f&&view.getHeight()>=48*unit&&view.getHeight()<=120*unit
           &&at[1]+view.getHeight()/2f>top[1]+height*.80f&&cells(group)!=null)return group;
        for(int i=0;i<group.getChildCount();i++){ViewGroup found=find(group.getChildAt(i),top,height,width,depth+1,budget);if(found!=null)return found;}
        return null;
    }
    /** A tab's name as TikTok shows it (its language), or null. */
    private static String name(View view, int depth) {
        if (view instanceof TextView) { CharSequence t = ((TextView) view).getText(); return t != null && role(t.toString()) >= 0 ? t.toString().trim() : null; }
        if (depth < 5 && view instanceof ViewGroup) { ViewGroup g = (ViewGroup) view; for (int i = 0; i < g.getChildCount(); i++) { String n = name(g.getChildAt(i), depth + 1); if (n != null) return n; } }
        return null;
    }
    /** TikTok's unread mark on a tab: its count ("5", "99+"), "" for a bare dot, null for none. */
    private static String badge(View view, int depth, float unit) {
        if (!view.isShown()) return null;
        if (view instanceof TextView) {
            CharSequence t = ((TextView) view).getText();
            return t != null && t.length() > 0 && t.toString().trim().matches("[0-9]+\\+?") ? t.toString().trim() : null;
        }
        if (depth > 0 && !(view instanceof ViewGroup) && !(view instanceof android.widget.ImageView) && view.getBackground() != null
                && view.getWidth() >= 4 * unit && view.getWidth() <= 14 * unit && Math.abs(view.getWidth() - view.getHeight()) <= 2 * unit) return "";
        if (depth < 5 && view instanceof ViewGroup) { ViewGroup g = (ViewGroup) view; for (int i = 0; i < g.getChildCount(); i++) { String b = badge(g.getChildAt(i), depth + 1, unit); if (b != null) return b; } }
        return null;
    }

    // ------------------------------------------------------------------ one main screen

    private static final class State implements ViewTreeObserver.OnPreDrawListener {
        final WeakReference<View> root;
        WeakReference<ViewGroup> rail = new WeakReference<>(null);
        WeakReference<ViewTreeObserver> observer = new WeakReference<>(null);
        ArrayList<View> tabs;
        View island;
        int islandStyle = -1;
        boolean attached, dirty;
        /** The IslandTabs version the row was last arranged for. */
        int arranged = -1;
        /** TikTok's own alpha for its row: the island shows while it is above 0. */
        float nativeAlpha = 1f;
        long nextSearch, nextRead, nextCells;
        /** A tab the island opened that TikTok has not marked chosen yet: its old choice is not followed until then. */
        int asked = -1; long askedUntil;
        /** The gesture in progress: the island's own (it started on the bar), and where it was. */
        boolean owned; int createTab = -1; float ox, oy;
        final int[] at = new int[2];
        /** What the island was last told: the tabs as a whole, and their unread marks. */
        String saidShape; String[] saidBadges;

        State(View root) { this.root = new WeakReference<>(root); }

        void attach() { View view = root.get(); if (view == null) return; ViewTreeObserver next = view.getViewTreeObserver(), old = observer.get(); if (attached && next == old) return; if (old != null && old.isAlive()) old.removeOnPreDrawListener(this); attached = next.isAlive(); if (attached) { next.removeOnPreDrawListener(this); next.addOnPreDrawListener(this); observer = new WeakReference<>(next); } }
        void detach() { ViewTreeObserver old = observer.get(); if (old != null && old.isAlive()) old.removeOnPreDrawListener(this); attached = false; }
        void cancel() { if (owned && island != null) ((Bar) island).cancel(); if (island != null) island.removeCallbacks(longPress); owned = false; held = false; createTab = -1; }

        /** TikTok's row back as it was, the island gone. */
        void restore() {
            cancel();
            ViewGroup row = rail.get();
            if (row != null && row.getAlpha() == 0f) row.setAlpha(nativeAlpha);
            if (row != null) IslandTabs.restore(row);
            arranged = -1;
            if (island != null && island.getParent() instanceof ViewGroup) ((ViewGroup) island.getParent()).removeView(island);
            island = null; islandStyle = -1; tabs = null; saidShape = null;
            if (active.get() == row) active.clear();
            rail = new WeakReference<>(null);
        }

        private Bar bar() { return (Bar) island; }

        public boolean onPreDraw() { android.os.Trace.beginSection("ttcuz island frame"); try { return frame(); } finally { android.os.Trace.endSection(); } }

        private boolean frame() {
            View view = root.get(); if (view == null) return true;
            try {
                if (!isEnabled()) { if (island != null || rail.get() != null) restore(); return true; }
                ViewGroup row = rail.get();
                if (row == null || !row.isAttachedToWindow()) {
                    if (row != null) restore();
                    long now = SystemClock.uptimeMillis(); if (now < nextSearch) return true; nextSearch = now + 1000;
                    int[] top = new int[2]; view.getLocationOnScreen(top);
                    row = find(view, top, view.getHeight(), view.getWidth(), 0, new int[]{3000}); if (row == null) return true;
                    if (cells(row) == null) return true;
                    rail = new WeakReference<>(row); active = new WeakReference<>(row); tabs = null; arranged = -1; nativeAlpha = row.getAlpha() == 0f ? 1f : row.getAlpha();
                    FeedUnderBar.apply(row);
                    dirty = true; Diary.note("navigation: island attached");
                }
                if (island == null || islandStyle != style() || island.getParent() != host(view)) build(view);
                // the buttons in the order and with the ones hidden the settings chose (IslandTabs); TikTok lays the
                // moved cells out by the next frame, and they are read then
                if (IslandTabs.version != arranged) { arranged = IslandTabs.version; if (IslandTabs.apply(row)) tabs = null; }
                long now = SystemClock.uptimeMillis();
                // TikTok rebuilding its buttons (quick taps between tabs) is read again at once and the island stays as it
                // was meanwhile: it used to drop its tabs, hide for a quarter second and let TikTok's own row show
                if (tabs != null && !intact(row)) {
                    ArrayList<View> again = cells(row);
                    if (again != null) { tabs = again; saidShape = null; nextRead = 0; lostAt = 0; }
                    else if (lostAt == 0) lostAt = now;
                    else if (now - lostAt > 300) { tabs = null; lostAt = 0; nextCells = now; }
                } else lostAt = 0;
                if (tabs == null && now >= nextCells) { tabs = cells(row); saidShape = null; nextRead = 0; nextCells = now + 250; }
                // TikTok's row is hidden only while the island stands in for it (what TikTok sets is remembered)
                float a = row.getAlpha();
                if (tabs != null) { if (a != 0f) { nativeAlpha = a; row.setAlpha(0f); } }
                else if (a == 0f) row.setAlpha(nativeAlpha);
                boolean show = tabs != null && row.isShown() && nativeAlpha > .01f;
                int visibility = show ? View.VISIBLE : View.INVISIBLE;
                if (island.getVisibility() != visibility) { island.setVisibility(visibility); if (!show) cancel(); }
                if (!show) return true;
                if (dirty) { dirty = false; bar().settings(dark(view.getContext()), labels(), glass() / 100f); }
                if (now >= nextRead) { nextRead = now + 500; read(view); }
                else follow();
                // as the island before (it was drawn inside TikTok's row): it goes where the row goes on every frame --
                // TikTok sliding its bar away or back, a page lifting it -- and fades as the row's parents fade
                place(view, view.getResources().getDisplayMetrics().density);
                float fade = nativeAlpha;
                for (android.view.ViewParent up = row.getParent(); up instanceof View && up != view; up = up.getParent()) fade *= ((View) up).getAlpha();
                // as the old island inside TikTok's bar: a sheet that opens (the comments, a menu) slides over it and
                // covers it, because it lies in TikTok's content under them. It is not faded away by a guess at what is
                // open; that guess only keeps its touches and its glass off while the comments are up.
                shown = CommentBackground.showing() ? 0f : 1f;
                // the island shown yet see-through for long: say once which factor holds it down
                if (fade >= .05f) fadedAt = 0;
                else if (fadedAt == 0) fadedAt = now;
                else if (fadedAt > 0 && now - fadedAt > 1500) {
                    fadedAt = -1;
                    StringBuilder ups = new StringBuilder();
                    for (android.view.ViewParent up = row.getParent(); up instanceof View && up != view; up = up.getParent())
                        if (((View) up).getAlpha() < .99f) ups.append(' ').append(up.getClass().getSimpleName()).append('=').append(((View) up).getAlpha());
                    Diary.note("navigation: island faded -- row " + nativeAlpha + ", parents" + (ups.length() == 0 ? " 1" : ups));
                }
                if (island.getAlpha() != fade) island.setAlpha(fade);
                // fully faded (TikTok's bar gone): not drawn at all -- its glass was still rendered on every frame
                int seen = fade > .005f ? View.VISIBLE : View.INVISIBLE;
                if (island.getVisibility() != seen) island.setVisibility(seen);
                // anti-burn, as on the island before: the icons dim only where the screen is dimmed (the feed, a post)
                bar().dim(Dim.dimming() ? Math.max(.1f, 1f - Dim.strength() / 100f) : 1f);
                // nothing of it seen (comments over it, TikTok's bar faded out): what is behind is not recorded
                if (fade > .01f && shown > 0f) bar().capture();
            } catch (RuntimeException error) { Diary.note("navigation: " + error); }
            return true;
        }

        /** The island for the chosen style, as a layer over the bottom of the window (it never takes a touch itself). */
        private void build(View decor) {
            if (island != null && island.getParent() instanceof ViewGroup) ((ViewGroup) island.getParent()).removeView(island);
            islandStyle = style();
            island = islandStyle == MATERIAL ? new M3Bar(decor.getContext()) : new LensBar(decor.getContext());
            island.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            float unit = decor.getResources().getDisplayMetrics().density;
            host(decor).addView(island, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.LEFT));
            saidShape = null; nextRead = 0; dirty = true;
        }

        /** Cheap per-frame check of the cached cells: no allocation, no text matching. */
        private boolean intact(ViewGroup row) {
            if (tabs == null) return false;
            for (int i = 0; i < tabs.size(); i++) { View cell = tabs.get(i); if (cell.getParent() != row || cell.getVisibility() != View.VISIBLE || cell.getWidth() <= 0) return false; }
            return true;
        }

        /** Every frame: the tab TikTok has chosen (back, a link), so the island goes there at once. No allocation. */
        private void follow() {
            for (int i = 0; i < tabs.size(); i++) if (selected(tabs.get(i), 0)) { chosen(i); return; }
        }

        /** TikTok shows tab i chosen: the island follows, unless it has just asked TikTok for another one. */
        private void chosen(int i) {
            if (i == asked || SystemClock.uptimeMillis() > askedUntil) asked = -1;
            if (asked < 0) bar().select(i);
        }

        /** Twice a second: the tabs' names, their unread marks, the tab TikTok has chosen, and the room under the bar. */
        private void read(View decor) {
            Bar bar = bar();
            int n = tabs.size(), create = -1, chosen = -1;
            int[] roles = new int[n]; String[] names = new String[n], badges = new String[n];
            float unit = decor.getResources().getDisplayMetrics().density;
            for (int i = 0; i < n; i++) {
                View cell = tabs.get(i);
                roles[i] = role(cell, 0);
                if (roles[i] < 0) { roles[i] = 2; create = i; continue; }
                names[i] = name(cell, 0);
                if (names[i] == null) names[i] = Text.t(new String[]{"Главная", "Друзья", "", "Входящие", "Профиль"}[roles[i]]);
                badges[i] = roles[i] == 3 && inboxCountHidden() ? null : badge(cell, 0, unit);
                if (selected(cell, 0)) chosen = i;
            }
            String shape = java.util.Arrays.toString(roles) + java.util.Arrays.toString(names) + create;
            if (!shape.equals(saidShape)) { saidShape = shape; saidBadges = null; bar.tabs(roles, names, create, chosen); }
            if (!java.util.Arrays.equals(badges, saidBadges)) { saidBadges = badges; bar.badges(badges); }
            if (chosen >= 0) chosen(chosen);
            // as wide as TikTok's seek bar over the video (LineProgressBar, ngb): the island before reached its ends.
            // Looked up here, twice a second, not on every frame
            View seek = decor.findViewById(SEEK_BAR);
            ViewGroup row = rail.get();
            if (seek != null && row != null && seek.getWidth() > row.getWidth() / 2) side = Math.max(0f, (row.getWidth() - seek.getWidth()) / 2f);
            else if (side < 0f) side = 9 * unit;
        }

        /** px from each side of TikTok's row to the island: where TikTok's seek bar ends (-1: not known yet). */
        float side = -1f;

        /**
         * The island's view lies over TikTok's row and 16 dp above it (room for the lens to swell): nothing else of
         * TikTok's screen is covered, and the shader has only that strip to work on.
         */
        /** Something TikTok laid over the island (a sheet, a menu) is under the finger: the touch is that one's. */
        private boolean covered(View island, float x, float y) {
            android.graphics.Rect r = new android.graphics.Rect();
            for (View child = island; child.getParent() instanceof ViewGroup; child = (View) child.getParent()) {
                ViewGroup parent = (ViewGroup) child.getParent();
                for (int i = parent.indexOfChild(child) + 1; i < parent.getChildCount(); i++) {
                    View over = parent.getChildAt(i);
                    if (solid(over, x, y, r, 0)) return true;
                }
            }
            return false;
        }

        /** A view at that point that would be seen or would take the touch (an empty transparent frame is neither). */
        private boolean solid(View v, float x, float y, android.graphics.Rect r, int depth) {
            if (!v.isShown() || v.getAlpha() <= .05f || !v.getGlobalVisibleRect(r) || !r.contains((int) x, (int) y)) return false;
            if (v.isClickable() || v.getBackground() != null || !(v instanceof ViewGroup)) return true;
            if (depth > 12) return false;
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) if (solid(g.getChildAt(i), x, y, r, depth + 1)) return true;
            return false;
        }

        /**
         * Where the island lies: the nearest full-screen frame around TikTok's bar, as near the bar as the old island
         * drawn inside it -- whatever TikTok lays over its bar (the comments, a menu, a sheet) lies over the island too.
         */
        private FrameLayout host(View decor) {
            ViewGroup row = rail.get();
            for (android.view.ViewParent up = row == null ? null : row.getParent(); up instanceof View && up != decor; up = up.getParent())
                if (up instanceof FrameLayout && ((View) up).getHeight() >= decor.getHeight() * .8f) return (FrameLayout) up;
            View content = decor.findViewById(android.R.id.content);
            return content instanceof FrameLayout ? (FrameLayout) content : (FrameLayout) decor;
        }

        private void place(View decor, float unit) {
            ViewGroup row = rail.get(); if (row == null) return;
            View parent = (View) island.getParent();
            if (side < 0f) side = 9 * unit;
            // room above for the lens to swell into, and below: the swollen lens was cut off at the bar's foot
            int over = Math.round(16 * unit), under = Math.round(14 * unit), nav = 0;
            android.view.WindowInsets insets = decor.getRootWindowInsets();
            if (insets != null) nav = Build.VERSION.SDK_INT >= 30 ? insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom : insets.getSystemWindowInsetBottom();
            row.getLocationInWindow(at); int left = at[0], top = at[1];
            parent.getLocationInWindow(at); left -= at[0]; top -= at[1];
            // a row drawn under the system's navigation keeps the island above it
            int bottom = Math.min(top + row.getHeight(), decor.getHeight() - nav - at[1]);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) island.getLayoutParams();
            int w = row.getWidth(), bar = bottom - top, below = Math.max(0, Math.min(under, parent.getHeight() - bottom)), h = over + bar + below;
            if (lp.width != w || lp.height != h || lp.leftMargin != left || lp.topMargin != top - over) {
                lp.width = w; lp.height = h; lp.leftMargin = left; lp.topMargin = top - over; island.setLayoutParams(lp);
            }
            bar().place(side, over, w - side, over + bar);
        }

        boolean touch(MotionEvent e, Window.Callback base) {
            View v = island;
            if (v == null || v.getVisibility() != View.VISIBLE || !v.isAttachedToWindow() || tabs == null || shown < .5f) { owned = false; return false; }
            Bar bar = bar();
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.getLocationOnScreen(at); ox = at[0]; oy = at[1];
                owned = bar.hit(e.getRawX() - ox, e.getRawY() - oy) && !covered(v, e.getRawX(), e.getRawY());
                held = false;
                v.removeCallbacks(longPress);
                if (owned) {
                    createTab = bar.down(e.getRawX() - ox);
                    downX = e.getRawX(); downY = e.getRawY();
                    int tab = bar.tabAt(e.getRawX() - ox);
                    // held on Inbox: whether its unread count shows is asked
                    if (tabs != null && tab >= 0 && tab < tabs.size() && role(tabs.get(tab), 0) == 3)
                        v.postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout());
                }
                return owned;
            }
            if (held) {
                // the hold became the question: the rest of this touch is the island's, and nothing opens
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) { held = false; owned = false; }
                return true;
            }
            if (owned) {
                if (action == MotionEvent.ACTION_MOVE) {
                    float slop = android.view.ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
                    if (Math.abs(e.getRawX() - downX) > slop || Math.abs(e.getRawY() - downY) > slop) v.removeCallbacks(longPress);
                    bar.move(e.getRawX() - ox);
                }
                else if (action == MotionEvent.ACTION_UP) {
                    v.removeCallbacks(longPress);
                    owned = false; boolean plus = createTab >= 0; int tab = plus ? createTab : bar.up(); createTab = -1;
                    // the island shows the new tab from now on: TikTok's old choice is not followed until TikTok shows it
                    if (tab >= 0 && !plus) { asked = tab; askedUntil = SystemClock.uptimeMillis() + 700; Dim.expect(root.get(), role(tabs.get(tab), 0)); }
                    // TikTok opens the tab a frame later: the lens has started on its way before TikTok's page work holds the main thread
                    if (tab >= 0) { final int chosen = tab; final Window.Callback to = base; v.postOnAnimation(() -> open(chosen, to)); }
                }
                else if (action == MotionEvent.ACTION_CANCEL) cancel();
                return true;
            }
            return false;
        }

        boolean held; float downX, downY;
        /** When TikTok's buttons went missing (0: they are there): the island waits a moment before giving up on them. */
        long lostAt, fadedAt;
        /** How much of the island shows while comments come and go (1 all of it). */
        float shown = 1f;
        /** TikTok's screen: the island's own context is the window's, which cannot show a dialog. */
        WeakReference<Activity> screen = new WeakReference<>(null);

        /** Inbox held: "Скрыть индикатор сообщений?" (or show it again), yes or no. */
        private final Runnable longPress = () -> {
            View v = island;
            Activity activity = screen.get();
            if (v == null || !owned || activity == null || activity.isFinishing()) return;
            held = true; createTab = -1;
            bar().cancel();
            if (Motion.haptics()) v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            final boolean hidden = inboxCountHidden();
            new ModDialog.Builder(activity)
                    .setTitle(hidden ? Text.t("Показать индикатор сообщений?") : Text.t("Скрыть индикатор сообщений?"))
                    .setMessage(hidden ? Text.t("Число непрочитанных снова будет на «Входящих».") : Text.t("Число непрочитанных пропадёт с «Входящих» на островке. Вернуть — так же, зажать «Входящие»."))
                    .setPositiveButton(Text.t("Да"), (d, w) -> { put(KEY_NO_INBOX_COUNT, !hidden); saidBadges = null; nextRead = 0; if (island != null) island.invalidate(); })
                    .setNegativeButton(Text.t("Нет"), null)
                    .show();
        };

        /** TikTok opens the tab: a tap replayed on its own cell, straight to the window (not back through the island). */
        private void open(int tab, Window.Callback base) {
            if (tabs == null || tab >= tabs.size()) return;
            View cell = tabs.get(tab);
            cell.getLocationInWindow(at);
            float x = at[0] + cell.getWidth() / 2f, y = at[1] + cell.getHeight() / 2f;
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0), up = MotionEvent.obtain(now, now + 30, MotionEvent.ACTION_UP, x, y, 0);
            try { base.dispatchTouchEvent(down); base.dispatchTouchEvent(up); }
            catch (Throwable error) { Diary.note("island tap: " + error); }
            finally { down.recycle(); up.recycle(); }
        }
    }
}
