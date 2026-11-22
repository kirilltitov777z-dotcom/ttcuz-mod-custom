package cat.ttcuz.framework;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

/**
 * The feed's buttons swapped for others ("Свои кнопки"): a hold on like, comments, favourites or share picks what
 * that place does from then on -- another of TikTok's buttons or one of the mod's actions on the video.
 *
 * A swapped slot stays in TikTok's layout, invisible, and the new button is drawn where it was on its parent's
 * overlay, so nothing is added to TikTok's views. Its touches are taken in the window before TikTok sees them
 * (IslandTouch); a hold on a slot that is not swapped sends TikTok a cancel first, so its own hold does not run too.
 */
final class RailSwap {
    private RailSwap() {}

    static final String ON = "rail_swap_on", KEY = "rail_swap_";
    /** What a place can do: "" is TikTok's own button there. */
    static final String[] IDS = {"", "like", "comment", "fav", "share", "download", "playback", "speed", "frame",
            "profile", "link", "hide", "back", "forward", "menu"};
    private static final String[] ICONS = {"", "favorite_fill", "chat_bubble_fill", "star_fill", "share_fill", "download_fill",
            "play_circle_fill", "speed_fill", "image_fill", "account_circle_fill", "link", "block", "fast_rewind_fill", "fast_forward_fill", "tune"};
    private static final int FACE = 0x7e0c0361;

    private static SharedPreferences prefs() {
        Context context = Ttcuz.context();
        return context == null ? null : context.getSharedPreferences(Ttcuz.PREFS, Context.MODE_PRIVATE);
    }

    private static volatile String[] chosen;

    static boolean on() { SharedPreferences p = prefs(); return p != null && p.getBoolean(ON, false); }

    static void setOn(boolean value) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putBoolean(ON, value).apply();
        chosen = null; FeedRail.reloadSettings();
    }

    /** What the place of a rail button (0 like, 1 comments, 2 favourites, 3 share) does now: an id from IDS. */
    static String swap(int kind) {
        String[] now = chosen;
        if (now == null) {
            now = new String[4];
            SharedPreferences p = prefs();
            boolean enabled = p != null && p.getBoolean(ON, false);
            for (int i = 0; i < 4; i++) now[i] = enabled ? p.getString(KEY + i, "") : "";
            if (p != null) chosen = now;
        }
        return kind < 0 || kind > 3 ? "" : now[kind];
    }

    /** Swapped for something else than what TikTok has there. */
    static boolean swapped(int kind) { String id = swap(kind); return !id.isEmpty() && !id.equals(IDS[kind + 1]); }

    static boolean any() { for (int i = 0; i < 4; i++) if (swapped(i)) return true; return false; }

    static void set(int kind, String id) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putString(KEY + kind, id).apply();
        chosen = null; FeedRail.reloadSettings();
    }

    static void reset() { for (int i = 0; i < 4; i++) set(i, ""); }

    static String label(String id) {
        switch (id) {
            case "like": return Text.RAIL_LIKE;
            case "comment": return Text.RAIL_COMMENT;
            case "fav": return Text.RAIL_FAV;
            case "share": return Text.RAIL_SHARE;
            case "download": return Text.t("Скачать");
            case "playback": return Text.t("Плеер");
            case "speed": return Text.t("Скорость");
            case "frame": return Text.t("Кадр");
            case "profile": return Text.t("Автор");
            case "link": return Text.t("Ссылка");
            case "hide": return Text.t("Скрыть");
            case "back": return "−" + PlayerTools.step() + Text.t(" с");
            case "forward": return "+" + PlayerTools.step() + Text.t(" с");
            case "menu": return Text.t("Меню");
            default: return Text.t("Как в TikTok");
        }
    }

    private static String icon(String id) { for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return ICONS[i]; return ""; }

    // ------------------------------------------------------------------ the new button, drawn where the old one was

    /** A slot made invisible for a swap gets its face on the parent's overlay, and loses it when it is TikTok's again. */
    static void face(View slot, int kind, boolean show) {
        Object tag = slot.getTag(FACE);
        Face face = tag instanceof Face ? (Face) tag : null;
        if (!show) {
            if (face != null) { face.detach(); slot.setTag(FACE, null); }
            return;
        }
        String id = swap(kind);
        if (face != null && face.id.equals(id)) { face.place(); return; }
        if (face != null) face.detach();
        if (!(slot.getParent() instanceof ViewGroup)) return;
        face = new Face(slot, id);
        slot.setTag(FACE, face);
        face.attach();
    }

    private static final class Face extends Drawable implements View.OnLayoutChangeListener {
        final View slot; final String id;
        private final Drawable glyph, shade;
        private final Paint words = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String label;
        private final float d;
        private ViewGroup parent;

        Face(View slot, String id) {
            this.slot = slot; this.id = id;
            d = slot.getResources().getDisplayMetrics().density;
            glyph = SettingsGlyph.make(icon(id), 0xFFFFFFFF);
            shade = SettingsGlyph.make(icon(id), 0x55000000);
            label = label(id);
            words.setColor(0xFFFFFFFF); words.setTextSize(12 * slot.getResources().getDisplayMetrics().scaledDensity);
            words.setTextAlign(Paint.Align.CENTER); words.setShadowLayer(2 * d, 0, d, 0x80000000);
            words.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        }

        void attach() {
            parent = (ViewGroup) slot.getParent();
            parent.getOverlay().add(this);
            slot.addOnLayoutChangeListener(this);
            place();
        }

        void detach() {
            slot.removeOnLayoutChangeListener(this);
            if (parent != null) parent.getOverlay().remove(this);
        }

        void place() {
            if (slot.getParent() != parent) { detach(); if (slot.getParent() instanceof ViewGroup) attach(); return; }
            setBounds(slot.getLeft(), slot.getTop(), slot.getRight(), slot.getBottom());
            invalidateSelf();
        }

        @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) { place(); }

        @Override public void draw(Canvas canvas) {
            if (slot.getVisibility() == View.GONE) return;
            Rect box = getBounds();
            float size = Math.min(box.width(), 36 * d), text = words.getTextSize(), gap = 4 * d;
            float top = box.top + Math.max(0, (box.height() - size - gap - text) / 2f);
            int left = Math.round(box.exactCenterX() - size / 2f);
            if (glyph != null) {
                int s = Math.round(size), lift = Math.round(d);
                shade.setBounds(left, Math.round(top) + lift, left + s, Math.round(top) + s + lift); shade.draw(canvas);
                glyph.setBounds(left, Math.round(top), left + s, Math.round(top) + s); glyph.draw(canvas);
            }
            float scale = Math.min(1f, (box.width() + 12 * d) / Math.max(1f, words.measureText(label)));
            canvas.save();
            canvas.scale(scale, scale, box.exactCenterX(), top + size + gap + text);
            canvas.drawText(label, box.exactCenterX(), top + size + gap + text, words);
            canvas.restore();
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ------------------------------------------------------------------ touches, before TikTok

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static View down;
    private static boolean taking, fired;
    private static float downX, downY;
    private static Runnable hold;

    static boolean touch(Activity activity, MotionEvent event, java.util.function.Consumer<MotionEvent> deliver) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            stopHold(); down = null; taking = false; fired = false;
            if (!on()) return false;
            View slot = FeedRail.slotAt(event.getRawX(), event.getRawY());
            int kind = FeedRail.kindOf(slot);
            if (slot == null || kind < 0) return false;
            down = slot; downX = event.getRawX(); downY = event.getRawY();
            taking = swapped(kind) && slot.getVisibility() == View.INVISIBLE;
            final long downTime = event.getDownTime();
            final float x = event.getX(), y = event.getY();
            hold = () -> {
                hold = null; fired = true;
                if (!taking) {
                    // TikTok has seen the press: it gets a cancel, so its own hold (repost, emoji, collections) does not run
                    MotionEvent cancel = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, x, y, 0);
                    deliver.accept(cancel); cancel.recycle();
                    taking = true;
                }
                slot.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                choose(activity, slot, kind);
            };
            // a little before TikTok's own long press, which was posted after this one
            main.postDelayed(hold, Math.max(200, ViewConfiguration.getLongPressTimeout() - 50));
            return taking;
        }
        if (down == null) return false;
        float moved = (float) Math.hypot(event.getRawX() - downX, event.getRawY() - downY);
        int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        if (action == MotionEvent.ACTION_MOVE && moved > slop) stopHold();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            stopHold();
            View slot = down; boolean was = taking;
            down = null; taking = false;
            if (was && !fired && action == MotionEvent.ACTION_UP && moved < slop * 2) {
                try { run(activity, slot); } catch (Throwable error) { Diary.note("rail swap: " + error); }
            }
            return was;
        }
        return taking;
    }

    private static void stopHold() { if (hold != null) main.removeCallbacks(hold); hold = null; }

    /** What to put in this place, as a sheet: the current choice ticked. */
    private static void choose(Activity activity, View slot, int kind) {
        Panel panel = Panel.with(activity, Skin.remembered(activity), Text.t("Вместо кнопки") + " «" + label(IDS[kind + 1]) + "»").side();
        String now = swap(kind);
        if (now.equals(IDS[kind + 1])) now = "";
        for (String id : IDS) {
            if (id.equals(IDS[kind + 1])) continue;
            String icon = id.isEmpty() ? ICONS[kind + 1] : icon(id);
            panel.item(icon, label(id), null, id.equals(now) ? "✓" : null, () -> set(kind, id));
        }
        panel.quiet(Text.CLOSE, null).show();
    }

    /** A tap on a swapped place: what it was swapped for, on the video it sits beside. */
    private static void run(Activity activity, View slot) {
        int kind = FeedRail.kindOf(slot);
        String id = swap(kind);
        Object component = FeedRail.owner(slot);
        Object post = NativeRead.get(NativeRead.item(component), "getAweme");
        int other = id.equals("like") ? 0 : id.equals("comment") ? 1 : id.equals("fav") ? 2 : id.equals("share") ? 3 : -1;
        if (other >= 0) { tapNative(slot, other); return; }
        if (post == null && !id.equals("speed") && !id.equals("playback") && !id.equals("back") && !id.equals("forward")) { Screen.say(Text.VIDEO_NOT_READY); return; }
        switch (id) {
            case "download": VideoActions.chooseDownload(activity, post); break;
            case "playback": VideoActions.playback(activity); break;
            case "speed": PlayerTools.choose(activity); break;
            case "frame": FrameCapture.capture(slot, NativeRead.aid(post)); break;
            case "profile": FeedButtons.openProfile(activity, component); break;
            case "link": copyLink(activity, post); break;
            case "hide": FeedBlacklist.addPost(post); break;
            case "back": PlayerTools.seek(false, false); break;
            case "forward": PlayerTools.seek(true, false); break;
            case "menu": VideoActions.show(slot, post); break;
            default: break;
        }
    }

    /** TikTok's own button of another kind beside this one, pressed as a finger would (it may be swapped away itself). */
    private static void tapNative(View slot, int kind) {
        View target = FeedRail.sibling(slot, kind);
        if (target == null || target.getVisibility() == View.GONE || target.getWidth() == 0) {
            Screen.say(Text.t("Эта кнопка скрыта в настройках ленты"));
            return;
        }
        long now = SystemClock.uptimeMillis();
        float x = target.getWidth() / 2f, y = target.getHeight() / 3f;
        MotionEvent press = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent lift = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0);
        target.dispatchTouchEvent(press);
        target.dispatchTouchEvent(lift);
        press.recycle(); lift.recycle();
    }

    private static void copyLink(Context context, Object post) {
        String handle = NativeRead.string(NativeRead.get(NativeRead.get(post, "getAuthor"), "getUniqueId"));
        String link = "https://www.tiktok.com/@" + (handle.isEmpty() ? "_" : handle) + "/video/" + NativeRead.aid(post);
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TikTok", link));
        Screen.say(Text.t("Ссылка скопирована"));
    }
}
