package cat.ttcuz.framework;

import android.content.SharedPreferences;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * A fake flame for a chat: the number of days TikTok shows, and which of its own flames (lit orange, fading grey).
 *
 * TikTok's streaks come from Gson, which makes them without running a constructor, so nothing is changed in the
 * objects themselves. Instead every place in TikTok that reads a streak's number or the two times its flame's state
 * comes from is patched to ask here (dexpatch: "streak fake read"): a chat with a fake gets the fake, every other
 * chat its own value. TikTok then picks the milestone art and the flame for that by itself. The mod's own streak
 * keeping (Streaks) reads the fields by reflection, so it always sees the real streak and renews it as before.
 * Nothing is sent anywhere; only this phone sees it.
 */
public final class StreakFake {
    private StreakFake() {}

    /** keep the flame as it is, lit orange, fading grey */
    public static final int AS_IS = 0, ORANGE = 1, GREY = 2;
    private static final String KEY = "streak_fake_";
    private static volatile Map<String, int[]> settings;
    /** the day the settings were read on: a fake that grows is one more the next day */
    private static volatile long readOn;

    /** Today, counted in whole days in the phone's own time zone. */
    static long today() {
        long now = System.currentTimeMillis();
        return (now + java.util.TimeZone.getDefault().getOffset(now)) / 86400000L;
    }

    private static SharedPreferences prefs() {
        android.content.Context c = Ttcuz.context();
        return c == null ? null : c.getSharedPreferences(Ttcuz.PREFS, 0);
    }

    /**
     * {days (-1: as is), colour, grows (1) or not (0), the day it was set} for a chat, or null when it has no fake. A fake
     * that grows reads one day more for every day since it was set, as a real streak does.
     */
    public static int[] of(String conversation) {
        if (conversation == null) return null;
        Map<String, int[]> all = settings;
        long day = today();
        if (all == null || readOn != day) {
            all = new HashMap<>();
            SharedPreferences p = prefs();
            if (p != null) for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
                if (!e.getKey().startsWith(KEY) || !(e.getValue() instanceof String)) continue;
                String[] parts = ((String) e.getValue()).split("\\|");
                try {
                    int days = Integer.parseInt(parts[0]), grows = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
                    long since = parts.length > 3 ? Long.parseLong(parts[3]) : day;
                    if (grows == 1 && days >= 0) days = (int) Math.min(99999L, days + Math.max(0L, day - since));
                    all.put(e.getKey().substring(KEY.length()), new int[]{days, parts.length > 1 ? Integer.parseInt(parts[1]) : AS_IS, grows, (int) since});
                } catch (NumberFormatException ignored) { }
            }
            settings = all;
            readOn = day;
        }
        return all.get(conversation);
    }

    /**
     * Sets a chat's fake ({days, colour} or {days, colour, grows}; days -1 keeps the real number); null removes it. A
     * growing fake counts from today. Applies from the next time TikTok reads the chat.
     */
    static void set(String conversation, int[] fake) {
        SharedPreferences p = prefs();
        if (p == null || conversation == null) return;
        if (fake == null || (fake[0] < 0 && fake[1] == AS_IS)) p.edit().remove(KEY + conversation).apply();
        else p.edit().putString(KEY + conversation, Math.max(-1, Math.min(99999, fake[0])) + "|" + fake[1]
                + "|" + (fake.length > 2 && fake[2] == 1 ? 1 : 0) + "|" + today()).apply();
        settings = null;
        Diary.note("streak fake: " + (fake == null ? "off" : fake[0] + " days, colour " + fake[1] + (fake.length > 2 && fake[2] == 1 ? ", grows" : "")));
    }

    /** The streak as the server sent it: the objects are never changed, so it is the object itself. */
    public static Object real(Object data) { return data; }

    /** The real number of days of a chat, or -1 when it is not known. */
    static int realDays(String conversation) {
        Object kept;
        synchronized (seen) { kept = seen.get(conversation); }
        return kept == null ? -1 : realCount(kept);
    }

    /** A streak's days as TikTok sent them: an inbox cell showing a fake has the fake written in (shown()). */
    static int realCount(Object data) {
        Object[] original;
        synchronized (originals) { original = originals.get(data); }
        return original != null ? (Integer) original[0] : (int) raw(data, STREAK);
    }

    private static String conversation(Object data) {
        Object c = data == null ? null : NativeRead.field(data, "convId");
        return c instanceof String ? (String) c : null;
    }

    /** No longer used: kept so an older patched constructor still finds it. */
    public static void adjust(Object data) { }

    // ------------------------------------------------------------------ the patched reads

    private static final int STREAK = 0, ACTIVE_BEFORE = 1, END_AT = 2;
    private static final String[] NAMES = {"streak", "activeBefore", "endAt"};
    private static volatile Field[] fields;
    /** conversation → the last streak TikTok read for it (for the settings sheet's "real" number) */
    private static final Map<String, Object> seen = new HashMap<>();

    private static long raw(Object data, int which) {
        try {
            Field[] f = fields;
            if (f == null || f[0].getDeclaringClass() != data.getClass()) {
                f = new Field[3];
                for (int i = 0; i < 3; i++) { f[i] = data.getClass().getDeclaredField(NAMES[i]); f[i].setAccessible(true); }
                fields = f;
            }
            return which == STREAK ? f[which].getInt(data) : f[which].getLong(data);
        } catch (Throwable error) {
            return 0;
        }
    }

    /** TikTok reading a streak's number of days. */
    public static int streak(Object data) {
        int real = (int) raw(data, STREAK);
        int[] fake = fake(data);
        return fake != null && fake[0] >= 0 ? fake[0] : real;
    }

    /** TikTok reading until when the flame is lit. */
    public static long activeBefore(Object data) {
        long real = raw(data, ACTIVE_BEFORE);
        int[] fake = fake(data);
        if (fake == null || fake[1] == AS_IS) return real;
        long unit = raw(data, END_AT) > 100000000000L ? 1000L : 1L, now = System.currentTimeMillis() / 1000L;
        return (fake[1] == ORANGE ? now + 2 * 86400L : now - 60L) * unit;
    }

    /** TikTok reading until when the flame can still be saved. */
    public static long endAt(Object data) {
        long real = raw(data, END_AT);
        int[] fake = fake(data);
        if (fake == null || fake[1] == AS_IS) return real;
        long unit = real > 100000000000L ? 1000L : 1L, now = System.currentTimeMillis() / 1000L;
        return (fake[1] == ORANGE ? now + 3 * 86400L : now + 86400L) * unit;
    }

    /** set while the mod asks TikTok about a streak (Streaks.statusOf): its patched reads then see the real one */
    static final ThreadLocal<Boolean> REAL = new ThreadLocal<>();

    private static int[] fake(Object data) {
        if (data == null || REAL.get() != null) return null;
        String conversation = conversation(data);
        if (conversation == null) return null;
        int[] fake = of(conversation);
        if (fake != null) synchronized (seen) { if (seen.size() > 256) seen.clear(); seen.put(conversation, data); }
        return fake;
    }

    // ------------------------------------------------- "Фейк огонёк" in the inbox's long-press menu

    static final String MENU = "streak_fake_menu";

    /** Whether a chat's long-press menu in the inbox offers "Фейк огонёк" (on unless switched off). */
    static boolean menuOn() { SharedPreferences p = prefs(); return p == null || p.getBoolean(MENU, true); }

    static void setMenu(boolean on) { SharedPreferences p = prefs(); if (p != null) p.edit().putBoolean(MENU, on).apply(); }

    /** The name and the conversation of the chat whose row was pressed last (its inbox cell, just before its menu opens). */
    private static volatile String pressed = "", pressedChat = null;
    /** the session's key prints itself as "ChatListConversationWrapperKey(bizId=..., conversationId=0:1:...)" */
    private static final java.util.regex.Pattern CHAT_ID = java.util.regex.Pattern.compile("conversationId=([^,)\\s]+)");

    /** The inbox cell's long press (X/1NKU subclasses, LIZIZ): which chat the menu about to open is for. */
    public static void pressed(Object session) {
        String name = "", chat = null;
        try { Object n = NativeRead.get(session, "getName"); if (n != null) name = n.toString(); } catch (Throwable ignored) { }
        try {
            java.util.regex.Matcher m = CHAT_ID.matcher(String.valueOf(NativeRead.get(session, "getKey")));
            if (m.find()) chat = m.group(1);
        } catch (Throwable ignored) { }
        pressed = name;
        pressedChat = chat;
    }

    /**
     * The inbox's long-press menu (IInboxLongPressMenuPopoverHelperImpl.LIZ: groups of X/0wjN items) gets one more
     * item first, "Фейк огонёк" with a flame: made like its first item (same look), with a label, a flame drawn
     * by the mod (icon resource 0: the menu then asks the item's own factory) and a click that opens the sheet.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static java.util.List menu(Object activity, java.util.List groups, android.view.View row) {
        if (groups == null || groups.isEmpty() || !menuOn() || !(activity instanceof android.app.Activity)) return groups;
        final String name = pressed, chat = pressedChat;
        if (name.isEmpty() && chat == null) return groups;
        try {
            Object sample = null;
            int first = -1;
            for (int i = 0; i < groups.size() && sample == null; i++) {
                Object group = groups.get(i);
                if (group instanceof java.util.List && !((java.util.List) group).isEmpty()) { sample = ((java.util.List) group).get(0); first = i; }
            }
            if (sample == null) return groups;
            Class<?> type = sample.getClass();
            final android.app.Activity screen = (android.app.Activity) activity;
            kotlin.jvm.functions.Function0<Object> click = () -> { open(screen, name, chat); return Boolean.TRUE; };
            kotlin.jvm.functions.Function1<Object, Object> icon = layout -> {
                android.widget.ImageView flame = new android.widget.ImageView(screen);
                flame.setImageDrawable(Symbols.drawable("local_fire_department", Skin.remembered(screen).text));
                int size = Math.round(20 * screen.getResources().getDisplayMetrics().density);
                flame.setLayoutParams(new android.widget.LinearLayout.LayoutParams(size, size));
                return flame;
            };
            Object item = type.getConstructor(CharSequence.class, kotlin.jvm.functions.Function0.class, int.class,
                    kotlin.jvm.functions.Function1.class, int.class, kotlin.jvm.functions.Function1.class, int.class,
                    boolean.class, boolean.class, kotlin.jvm.functions.Function0.class)
                    .newInstance(Text.t("Фейк огонёк"), click, 0, icon, type.getField("LJ").get(sample),
                            type.getField("LJFF").get(sample), type.getField("LJI").get(sample),
                            type.getField("LJII").get(sample), type.getField("LJIIIIZZ").get(sample),
                            type.getField("LJIIIZ").get(sample));
            java.util.List out = new java.util.ArrayList(groups);
            java.util.List head = new java.util.ArrayList((java.util.List) out.get(first));
            head.add(0, item);
            out.set(first, head);
            return out;
        } catch (Throwable error) { Diary.note("fake flame menu: " + error); return groups; }
    }

    /**
     * Opens the sheet for the pressed chat (off the main thread): by its conversation when the cell gave one -- the name
     * in the inbox is the nickname, the streak list's is often only the id -- else by its name among the chats with a streak.
     */
    private static void open(final android.app.Activity screen, final String name, final String chat) {
        Net.away("fake flame", () -> {
            Streaks.Friend found = null;
            try {
                if (chat != null) found = new Streaks.Friend(chat, Streaks.peerOf(chat, Account.liveId()), name, null, Math.max(0, Streaks.daysOf(chat)));
                else for (Streaks.Friend f : Streaks.friends()) if (name.equals(f.name)) { found = f; break; }
            } catch (Throwable error) { Diary.note("fake flame friends: " + error); }
            final Streaks.Friend friend = found;
            screen.runOnUiThread(() -> {
                if (screen.isFinishing()) return;
                if (friend == null) { Screen.say(Text.t("С этим чатом нет серии")); return; }
                sheet(screen, friend);
            });
        });
    }

    // ------------------------------------------------- TikTok's own flames

    /** The days at which TikTok's streak goes up a step (its "streak_level"): 3, 10, 30, 100 and 200. */
    private static final int[] STEPS = {3, 10, 30, 100, 200};

    /**
     * Which of TikTok's five flames a number of days gets (raw/icon_bitmap_streak_light_level_1..5: yellow, orange, red,
     * pink, purple): one more at each of its steps.
     */
    static int level(int days) {
        int level = 1;
        for (int i = 1; i < STEPS.length; i++) if (days >= STEPS[i]) level = i + 1;
        return level;
    }

    /** The step TikTok keeps in a streak's "level" for a number of days: the last one reached, 0 before the first. */
    static int step(int days) {
        int step = 0;
        for (int s : STEPS) if (days >= s) step = s;
        return step;
    }

    /** The number is written in its flame's colour (as TikTok writes it beside each of its five flames). */
    private static final int[] LEVEL_COLOURS = {0xFFF9B04A, 0xFFE9795A, 0xFFF2705F, 0xFFEB65E1, 0xFFAD86E9};

    static int colour(int days) { return LEVEL_COLOURS[level(days) - 1]; }

    /** TikTok's flames decoded once: the five lit ones, then the five grey ones */
    private static final android.graphics.drawable.Drawable.ConstantState[] flames = new android.graphics.drawable.Drawable.ConstantState[10];

    /** TikTok's lit flame of a level, from its own resources; null when this TikTok has none. */
    static android.graphics.drawable.Drawable flame(android.content.Context c, int level) {
        return picture(c, "icon_bitmap_streak_light_level_" + level, level - 1);
    }

    private static android.graphics.drawable.Drawable picture(android.content.Context c, String name, int slot) {
        android.content.res.Resources res = c.getResources();
        android.graphics.drawable.Drawable.ConstantState known = flames[slot];
        if (known != null) return known.newDrawable(res);
        try {
            int id = res.getIdentifier(name, "raw", c.getPackageName());
            if (id == 0) return null;
            android.graphics.Bitmap picture;
            try (java.io.InputStream in = res.openRawResource(id)) { picture = android.graphics.BitmapFactory.decodeStream(in); }
            if (picture == null) return null;
            android.graphics.drawable.BitmapDrawable drawn = new android.graphics.drawable.BitmapDrawable(res, picture);
            flames[slot] = drawn.getConstantState();
            return drawn;
        } catch (Throwable error) { Diary.note("fake flame picture: " + error); return null; }
    }

    /**
     * "Сколько дней выставить?": the flame and the number in its colour, "Сейчас N · добавим +M", a slider from 0 to 1000,
     * "Готово" and "Сбросить". The flame's colour follows the number smoothly and it jumps a little as it turns.
     */
    static void sheet(final android.app.Activity c, final Streaks.Friend f) {
        Skin skin = Skin.remembered(c);
        float d = c.getResources().getDisplayMetrics().density;
        final int real = Math.max(0, realDays(f.conversation) >= 0 ? realDays(f.conversation) : f.days);
        int[] had = of(f.conversation);
        final int[] value = {had != null && had[0] >= 0 ? had[0] : real};

        android.widget.LinearLayout box = new android.widget.LinearLayout(c);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        android.widget.LinearLayout top = new android.widget.LinearLayout(c);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final android.widget.ImageView flame = new android.widget.ImageView(c);
        // TikTok's own flame for the number; the mod's flame in the number's colour only when TikTok has none
        final Symbols.Glyph glyph = (Symbols.Glyph) Symbols.drawable("local_fire_department_fill1", colour(value[0]));
        android.graphics.drawable.Drawable first = flame(c, level(value[0]));
        flame.setImageDrawable(first != null ? first : glyph);
        int size = Math.round(40 * d);
        top.addView(flame, new android.widget.LinearLayout.LayoutParams(size, size));
        // the number is a field: a tap brings the digits up, for any number of one's own (up to 99 999)
        final android.widget.EditText number = new android.widget.EditText(c);
        number.setBackground(null);
        number.setPadding(0, 0, 0, 0);
        number.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        number.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(5)});
        number.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        number.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 32);
        number.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        android.widget.LinearLayout.LayoutParams numberAt = new android.widget.LinearLayout.LayoutParams(-2, -2);
        numberAt.leftMargin = Math.round(6 * d);
        top.addView(number, numberAt);
        box.addView(top, new android.widget.LinearLayout.LayoutParams(-2, -2));
        final android.widget.TextView note = new android.widget.TextView(c);
        note.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        note.setTextColor(skin.muted());
        note.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams noteAt = new android.widget.LinearLayout.LayoutParams(-2, -2);
        noteAt.topMargin = Math.round(4 * d);
        box.addView(note, noteAt);

        final android.widget.SeekBar slider = new android.widget.SeekBar(c);
        slider.setMax(1000);
        slider.setProgress(Math.min(1000, value[0]));
        android.content.res.ColorStateList accent = android.content.res.ColorStateList.valueOf(Accent.colour());
        slider.setProgressTintList(accent);
        slider.setThumbTintList(accent);
        android.widget.LinearLayout.LayoutParams sliderAt = new android.widget.LinearLayout.LayoutParams(-1, -2);
        sliderAt.topMargin = Math.round(28 * d);
        box.addView(slider, sliderAt);
        android.widget.LinearLayout ends = new android.widget.LinearLayout(c);
        android.widget.TextView min = new android.widget.TextView(c), max = new android.widget.TextView(c);
        min.setText("0"); max.setText("1000");
        min.setTextColor(skin.muted()); max.setTextColor(skin.muted());
        min.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13); max.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        ends.addView(min, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
        ends.addView(max);
        ends.setPadding(Math.round(16 * d), 0, Math.round(16 * d), 0);
        box.addView(ends, new android.widget.LinearLayout.LayoutParams(-1, -2));
        // "Расти каждый день": the number goes up by one every day by itself, as a real streak would
        final android.widget.CheckBox grows = new android.widget.CheckBox(c);
        grows.setText(Text.t("Расти каждый день"));
        grows.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        grows.setTextColor(skin.text);
        grows.setButtonTintList(accent);
        grows.setChecked(had != null && had.length > 2 && had[2] == 1);
        android.widget.LinearLayout.LayoutParams growsAt = new android.widget.LinearLayout.LayoutParams(-2, -2);
        growsAt.topMargin = Math.round(14 * d);
        box.addView(grows, growsAt);
        android.widget.TextView growsNote = new android.widget.TextView(c);
        growsNote.setText(Text.t("+1 день каждые сутки, как у настоящей серии"));
        growsNote.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        growsNote.setTextColor(skin.muted());
        box.addView(growsNote, new android.widget.LinearLayout.LayoutParams(-2, -2));

        final int[] shown = {colour(value[0])}, levelNow = {level(value[0])};
        final boolean[] typing = {false};
        final android.animation.ValueAnimator[] fade = {null};
        final Runnable paint = () -> {
            int v = value[0], to = colour(v);
            if (!typing[0]) { number.setText(String.valueOf(v)); number.setSelection(number.length()); }
            int diff = v - real;
            note.setText(Text.t("Сейчас") + " " + real + " · " + Text.t("добавим") + " " + (diff >= 0 ? "+" + diff : "−" + (-diff)));
            // the colour flows to the new one instead of jumping, and a new look (pink, purple) makes the flame jump
            if (fade[0] != null) fade[0].cancel();
            fade[0] = android.animation.ValueAnimator.ofObject(new android.animation.ArgbEvaluator(), shown[0], to);
            fade[0].setDuration(Motion.time(220L));
            fade[0].addUpdateListener(a -> { shown[0] = (Integer) a.getAnimatedValue(); glyph.colour(shown[0]); number.setTextColor(shown[0]); });
            fade[0].start();
            // a new level: the flame becomes TikTok's next one, in place, with a little jump
            int l = level(v);
            if (l != levelNow[0]) {
                android.graphics.drawable.Drawable next = flame(c, l);
                if (next != null) flame.setImageDrawable(next);
                if (Motion.enabled(c)) {
                    flame.animate().cancel();
                    flame.setScaleX(.6f); flame.setScaleY(.6f); flame.setRotation(l > levelNow[0] ? -10f : 10f);
                    flame.animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(Motion.time(380L))
                            .setInterpolator(new android.view.animation.OvershootInterpolator(2.6f)).start();
                }
                if (Motion.haptics()) flame.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            }
            levelNow[0] = l;
        };
        number.setTextColor(shown[0]);
        paint.run();
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean byUser) { if (byUser) { value[0] = progress; paint.run(); } }
            @Override public void onStartTrackingTouch(android.widget.SeekBar bar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {}
        });

        number.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence t, int a, int b, int n) {}
            @Override public void onTextChanged(CharSequence t, int a, int b, int n) {}
            @Override public void afterTextChanged(android.text.Editable t) {
                if (!number.hasFocus()) return;
                int typed = 0;
                try { if (t.length() > 0) typed = Integer.parseInt(t.toString()); } catch (NumberFormatException ignored) {}
                value[0] = typed;
                typing[0] = true;
                slider.setProgress(Math.min(1000, typed));
                paint.run();
                typing[0] = false;
            }
        });

        Panel.with(c, skin, Text.t("Сколько дней выставить?"))
                .view(box)
                .primary(Text.t("Готово"), () -> { set(f.conversation, new int[]{value[0], ORANGE, grows.isChecked() ? 1 : 0}); redraw(f.conversation); })
                .quiet(Text.t("Сбросить"), () -> { set(f.conversation, null); redraw(f.conversation); })
                .show();
    }

    /** inbox cells' flames (X/0IDk) → {the streak they show, their flag}: which cell shows which chat */
    private static final Map<Object, Object[]> views = new java.util.WeakHashMap<>();

    /** a chat's streak as TikTok sent it, before a fake was written into it: {streak, level} */
    private static final Map<Object, Object[]> originals = new java.util.WeakHashMap<>();

    /**
     * An inbox cell's flame shown for a streak (its "updateStreakView", X/0IDk.LJIIIZ); null when it is cleared. Before
     * TikTok reads it, a chat with a fake gets the fake's days and step written into the streak itself: TikTok picks its
     * flame and the number's colour by the step ("level", 3/10/30/100/200), not by the days, so with the days alone the
     * number changed and the flame stayed. Without a fake (or once it is reset) the streak gets back what TikTok sent.
     */
    public static void shown(Object view, Object data, boolean flag) {
        if (view == null) return;
        synchronized (views) { if (data == null) views.remove(view); else views.put(view, new Object[]{data, flag}); }
        if (data == null) return;
        try {
            java.lang.reflect.Field streak = data.getClass().getDeclaredField("streak"), level = data.getClass().getDeclaredField("level");
            streak.setAccessible(true);
            level.setAccessible(true);
            int[] fake = of(conversation(data));
            Object[] original;
            synchronized (originals) { original = originals.get(data); }
            if (fake != null && fake[0] >= 0) {
                if (original == null) synchronized (originals) { originals.put(data, new Object[]{streak.getInt(data), level.get(data)}); }
                streak.setInt(data, fake[0]);
                level.set(data, Integer.valueOf(step(fake[0])));
            } else if (original != null) {
                streak.setInt(data, (Integer) original[0]);
                level.set(data, original[1]);
                synchronized (originals) { originals.remove(data); }
            }
        } catch (Throwable error) { Diary.note("fake flame level: " + error); }
    }

    /** The chat's flame drawn again by TikTok itself, now: it reads the new number (and picks its flame) at once. */
    static void redraw(String conversation) {
        java.util.List<Map.Entry<Object, Object[]>> cells;
        synchronized (views) { cells = new java.util.ArrayList<>(views.entrySet()); }
        int drawn = 0;
        for (Map.Entry<Object, Object[]> cell : cells) {
            Object view = cell.getKey(), data = cell.getValue()[0];
            if (view == null || !conversation.equals(conversation(data))) continue;
            try {
                view.getClass().getMethod("LJIIIZ", data.getClass(), boolean.class).invoke(view, data, cell.getValue()[1]);
                drawn++;
            } catch (Throwable error) { Diary.note("fake flame redraw: " + error); }
        }
        if (drawn == 0) Diary.note("fake flame redraw: no cell shows " + conversation);
    }
}
