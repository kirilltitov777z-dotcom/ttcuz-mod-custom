package cat.ttcuz.framework;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * "Добавить трек": a search through TikTok's sounds, TikTok's recommended sounds (its own /aweme/v1/hot/music/), one's favourite sounds, or a sound by
 * its link. Every row can be listened to before it goes on the profile: a tap plays it through the profile player
 * (as the queue "preview"), the "+" adds it. Whatever is still playing from here stops when the sheet closes.
 */
final class ProfileMusicPicker {
    private ProfileMusicPicker() {}

    static final String PREVIEW = "preview";
    private static final int SEARCH = 0, RECOMMENDED = 1, FAVOURITES = 2, LINK = 3;
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** Recommended sounds read this run, and where TikTok's list goes on from: one page is not asked twice. */
    private static final List<ProfileMusic.Track> hot = new ArrayList<>();
    private static int hotCursor;
    private static boolean hotMore = true;

    static void open(final Activity activity, final Skin skin, final Runnable added) {
        final Panel panel = Panel.with(activity, skin, Text.t("Добавить трек"));
        final LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);

        final LinearLayout tabs = new LinearLayout(activity);
        tabs.setPadding(0, 0, 0, dp(box, 10));
        android.widget.HorizontalScrollView strip = new android.widget.HorizontalScrollView(activity);
        strip.setHorizontalScrollBarEnabled(false);
        strip.addView(tabs);
        box.addView(strip);
        final LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setMinimumHeight(Math.round(activity.getResources().getDisplayMetrics().heightPixels * .4f));
        box.addView(content);

        final String[] names = {Text.t("Поиск"), Text.t("Рекомендации"), Text.t("Избранное"), Text.t("По ссылке")};
        final TextView[] chips = new TextView[names.length];
        final int[] shown = {-1};
        final List<Runnable> repaint = new ArrayList<>();
        final Runnable onChange = () -> { for (Runnable r : new ArrayList<>(repaint)) r.run(); };

        final Runnable[] select = new Runnable[1];
        for (int i = 0; i < names.length; i++) {
            final int which = i;
            TextView chip = new TextView(activity);
            chip.setText(names[i]);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setPadding(dp(box, 14), 0, dp(box, 14), 0);
            chip.setOnClickListener(v -> {
                if (shown[0] == which) return;
                shown[0] = which;
                paintChips(chips, which, skin);
                repaint.clear();
                content.removeAllViews();
                if (which == SEARCH) search(activity, skin, content, repaint, added);
                else if (which == RECOMMENDED) recommended(activity, skin, content, repaint, added);
                else if (which == FAVOURITES) favourites(activity, skin, content, repaint, added);
                else byLink(activity, skin, content, repaint, added);
            });
            Motion.press(chip);
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(box, 36));
            if (i > 0) at.leftMargin = dp(box, 8);
            tabs.addView(chip, at);
            chips[i] = chip;
        }
        chips[RECOMMENDED].performClick();

        ProfileMusic.listen(onChange);
        box.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { }
            @Override public void onViewDetachedFromWindow(View v) {
                ProfileMusic.unlisten(onChange);
                // a sound listened to here does not go on playing once the sheet is closed
                if (ProfileMusic.isFrom(PREVIEW)) ProfileMusic.stop();
            }
        });
        panel.view(box).show();
    }

    private static void paintChips(TextView[] chips, int chosen, Skin skin) {
        int accent = Accent.colour();
        for (int i = 0; i < chips.length; i++) {
            boolean on = i == chosen;
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(chips[i], 18));
            pill.setColor(on ? (accent & 0xFFFFFF) | 0x33000000 : Expressive.mix(skin.card, skin.text, .06f));
            chips[i].setBackground(pill);
            chips[i].setTextColor(on ? Expressive.mix(accent, skin.text, .25f) : skin.muted());
        }
    }

    // ------------------------------------------------- the three sources

    private static void recommended(final Activity activity, final Skin skin, final LinearLayout content,
                                    final List<Runnable> repaint, final Runnable added) {
        final LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        content.addView(rows);
        final TextView more = note(activity, skin, "");
        content.addView(more);
        final List<ProfileMusic.Track> list = new ArrayList<>();
        final boolean[] busy = {false};
        final Runnable[] load = new Runnable[1];
        load[0] = () -> {
            if (busy[0]) return;
            busy[0] = true;
            more.setText(Text.t("Загружаем…"));
            more.setOnClickListener(null);
            final int from = list.size();
            Net.away("profile music hot", () -> {
                String failed = null;
                synchronized (hot) {
                    if (hot.size() <= from && hotMore) {
                        try { readHot(); } catch (Throwable error) { failed = String.valueOf(error); }
                    }
                }
                final String error = failed;
                final List<ProfileMusic.Track> page;
                synchronized (hot) { page = new ArrayList<>(hot.subList(Math.min(from, hot.size()), hot.size())); }
                main.post(() -> {
                    busy[0] = false;
                    if (!rows.isAttachedToWindow() && from > 0) return;
                    if (error != null) Diary.note("profile music hot: " + error);
                    list.addAll(page);
                    for (ProfileMusic.Track t : page) rows.addView(row(activity, skin, t, list, repaint, added));
                    if (list.isEmpty()) more.setText(error != null ? Text.t("TikTok не отдал рекомендации. Нажмите, чтобы повторить") : Text.t("Рекомендаций пока нет"));
                    else if (hotMore || error != null) more.setText(Text.t("Показать ещё"));
                    else more.setText("");
                    if (error != null || hotMore) more.setOnClickListener(v -> load[0].run());
                });
            });
        };
        load[0].run();
    }

    /** One page of TikTok's recommended sounds (count 30, from the last cursor), added to `hot`. Off the main thread. */
    private static void readHot() throws Exception {
        Object api = ProfileMusic.chooseApi();
        java.lang.reflect.Method page = api.getClass().getMethod("LJIILJJIL", int.class, int.class, boolean.class, int.class,
                int.class, int.class, String.class, String.class, String.class, String.class, String.class, String.class,
                String.class, String.class, String.class, Long.class, Boolean.class, String.class);
        // cursor, count, not_duplicate, sound_page_scene, is_commercial_sound_page, music_recommend_refer; the rest
        // (shoot_way, the effect and draft ids, video_duration, unlimited_only, pinned_music_id) are a video's, unset
        Object answer = ProfileMusic.await(page.invoke(api, hotCursor, 30, Boolean.TRUE, 0, 0, 0,
                null, null, null, null, null, null, null, null, null, null, Boolean.FALSE, null));
        Object items = NativeRead.get(answer, "getItems");
        int added = 0;
        if (items instanceof List) for (Object music : (List<?>) items) {
            ProfileMusic.Track t = ProfileMusic.trackOf(music);
            if (t == null) continue;
            boolean known = false;
            for (ProfileMusic.Track h : hot) if (h.id.equals(t.id)) { known = true; break; }
            if (!known) { hot.add(t); added++; }
        }
        Object cursor = NativeRead.get(answer, "getCursor");
        Object hasMore = NativeRead.get(answer, "isHasMore");
        hotCursor = cursor instanceof Number ? ((Number) cursor).intValue() : hotCursor + 30;
        hotMore = Boolean.TRUE.equals(hasMore) && added > 0;
    }

    private static void favourites(final Activity activity, final Skin skin, final LinearLayout content,
                                   final List<Runnable> repaint, final Runnable added) {
        final TextView wait = note(activity, skin, Text.t("Загружаем…"));
        content.addView(wait);
        Net.away("profile music favourites", () -> {
            List<ProfileMusic.Track> got;
            try { got = ProfileMusic.favourites(); } catch (Throwable error) { Diary.note("favourite sounds: " + error); got = null; }
            final List<ProfileMusic.Track> list = got;
            main.post(() -> {
                if (list == null || list.isEmpty()) {
                    wait.setText(list == null ? Text.t("Не удалось загрузить избранные звуки") : Text.t("В избранных звуках пусто"));
                    return;
                }
                content.removeView(wait);
                for (ProfileMusic.Track t : list) content.addView(row(activity, skin, t, list, repaint, added));
            });
        });
    }

    /** Words typed, TikTok's sound search asked: 20 at a time, "Показать ещё" for the next ones. */
    private static void search(final Activity activity, final Skin skin, final LinearLayout content,
                               final List<Runnable> repaint, final Runnable added) {
        final EditText field = new EditText(activity);
        field.setHint(Text.t("Название трека или исполнитель"));
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        final ImageView find = searchLine(activity, skin, content, field);
        final LinearLayout found = new LinearLayout(activity);
        found.setOrientation(LinearLayout.VERTICAL);
        found.setPadding(0, dp(content, 8), 0, 0);
        content.addView(found);
        final TextView more = note(activity, skin, "");
        content.addView(more);
        final List<ProfileMusic.Track> list = new ArrayList<>();
        final String[] words = {""};
        final long[] cursor = {0};
        final int[] asked = {0};
        final Runnable[] load = new Runnable[1];
        load[0] = () -> {
            final int ask = asked[0];
            final String what = words[0];
            final long from = cursor[0];
            more.setText(Text.t("Ищем…"));
            more.setOnClickListener(null);
            Net.away("profile music search", () -> {
                List<ProfileMusic.Track> got;
                try { got = ProfileMusic.search(what, from); } catch (Throwable error) { Diary.note("profile music search: " + error); got = null; }
                final List<ProfileMusic.Track> page = got;
                main.post(() -> {
                    if (ask != asked[0] || !found.isAttachedToWindow()) return;   // a newer search, or the sheet closed
                    if (page == null) {
                        more.setText(Text.t("TikTok не ответил. Нажмите, чтобы повторить"));
                        more.setOnClickListener(v -> load[0].run());
                        return;
                    }
                    int before = list.size();
                    for (ProfileMusic.Track t : page) {
                        boolean known = false;
                        for (ProfileMusic.Track k : list) if (k.id.equals(t.id)) { known = true; break; }
                        if (known) continue;
                        list.add(t);
                        found.addView(row(activity, skin, t, list, repaint, added));
                    }
                    cursor[0] = from + 20;
                    boolean any = list.size() > before;
                    if (list.isEmpty()) more.setText(Text.t("Ничего не нашлось"));
                    else more.setText(any ? Text.t("Показать ещё") : "");
                    if (any) more.setOnClickListener(v -> load[0].run());
                });
            });
        };
        final Runnable go = () -> {
            String text = field.getText().toString().trim();
            if (text.isEmpty()) return;
            asked[0]++;
            words[0] = text;
            cursor[0] = 0;
            list.clear();
            repaint.clear();
            found.removeAllViews();
            android.view.inputmethod.InputMethodManager keys = (android.view.inputmethod.InputMethodManager) activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (keys != null) keys.hideSoftInputFromWindow(field.getWindowToken(), 0);
            load[0].run();
        };
        find.setOnClickListener(v -> go.run());
        field.setOnEditorActionListener((v, action, event) -> { go.run(); return true; });
    }

    /** A rounded field with a search button at its end, added to `content`; the button is returned. */
    private static ImageView searchLine(Activity activity, Skin skin, LinearLayout content, EditText field) {
        LinearLayout line = new LinearLayout(activity);
        line.setGravity(Gravity.CENTER_VERTICAL);
        field.setSingleLine(true);
        field.setTextColor(skin.text);
        field.setHintTextColor(skin.muted());
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        field.setBackground(null);
        field.setPadding(dp(content, 14), 0, dp(content, 8), 0);
        GradientDrawable well = new GradientDrawable();
        well.setCornerRadius(dp(content, 14));
        well.setColor(Expressive.mix(skin.card, skin.text, .06f));
        line.setBackground(well);
        line.addView(field, new LinearLayout.LayoutParams(0, dp(content, 48), 1f));
        final ImageView find = new ImageView(activity);
        find.setImageDrawable(Symbols.drawable("search", Accent.colour()));
        find.setPadding(dp(content, 12), dp(content, 12), dp(content, 12), dp(content, 12));
        find.setContentDescription(Text.t("Найти"));
        Motion.press(find);
        line.addView(find, new LinearLayout.LayoutParams(dp(content, 48), dp(content, 48)));
        content.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(content, 48)));
        return find;
    }

    private static void byLink(final Activity activity, final Skin skin, final LinearLayout content,
                               final List<Runnable> repaint, final Runnable added) {
        final EditText field = new EditText(activity);
        field.setHint(Text.t("tiktok.com/music/… или номер звука"));
        final ImageView find = searchLine(activity, skin, content, field);
        final LinearLayout found = new LinearLayout(activity);
        found.setOrientation(LinearLayout.VERTICAL);
        found.setPadding(0, dp(content, 8), 0, 0);
        content.addView(found);
        found.addView(note(activity, skin, Text.t("Вставьте ссылку на звук из TikTok: «Поделиться» → «Копировать ссылку»")));
        find.setOnClickListener(v -> {
            final String id = ProfileMusic.idIn(field.getText().toString());
            found.removeAllViews();
            repaint.clear();
            if (id == null) { found.addView(note(activity, skin, Text.t("В ссылке нет номера звука"))); return; }
            final TextView wait = note(activity, skin, Text.t("Ищем звук…"));
            found.addView(wait);
            Net.away("profile music link", () -> {
                final String[] sound = ProfileMusic.known(id);
                main.post(() -> {
                    found.removeAllViews();
                    if (sound == null) { found.addView(note(activity, skin, Text.t("TikTok не нашёл такой звук"))); return; }
                    List<ProfileMusic.Track> one = new ArrayList<>();
                    one.add(new ProfileMusic.Track(id, sound[2], sound[3]));
                    found.addView(row(activity, skin, one.get(0), one, repaint, added));
                });
            });
        });
    }

    // ------------------------------------------------- one sound

    /**
     * Cover (the playing bars over it while it plays here), title and artist; a tap listens to it, the round "+"
     * puts it on the profile and turns into a tick.
     */
    private static View row(final Activity activity, final Skin skin, final ProfileMusic.Track t,
                            final List<ProfileMusic.Track> list, final List<Runnable> repaint, final Runnable added) {
        final LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(row, 4), dp(row, 7), dp(row, 2), dp(row, 7));

        FrameLayout art = new FrameLayout(activity);
        final ImageView picture = new ImageView(activity);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ProfileMusic.round(picture, 10);
        ProfileMusic.cover(picture, t.id);
        art.addView(picture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final View shade = new View(activity);
        GradientDrawable dark = new GradientDrawable();
        dark.setCornerRadius(dp(row, 10));
        dark.setColor(0x73000000);
        shade.setBackground(dark);
        art.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final ProfileMusicViews.PlayButton state = new ProfileMusicViews.PlayButton(activity, 0, 0xFFFFFFFF);
        art.addView(state, new FrameLayout.LayoutParams(dp(row, 26), dp(row, 26), Gravity.CENTER));
        row.addView(art, new LinearLayout.LayoutParams(dp(row, 50), dp(row, 50)));

        LinearLayout words = new LinearLayout(activity);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(row, 14), 0, dp(row, 8), 0);
        final TextView title = new TextView(activity), artist = new TextView(activity);
        title.setText(TextUtils.isEmpty(t.title) ? Text.t("Трек") : t.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        artist.setText(t.artist);
        artist.setTextColor(skin.muted());
        artist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(title);
        words.addView(artist);
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final ImageView plus = new ImageView(activity);
        plus.setPadding(dp(row, 9), dp(row, 9), dp(row, 9), dp(row, 9));
        Motion.press(plus);
        row.addView(plus, new LinearLayout.LayoutParams(dp(row, 42), dp(row, 42)));

        final Runnable paint = () -> {
            boolean mine = false;
            for (ProfileMusic.Track m : ProfileMusic.mine()) if (m.id.equals(t.id)) { mine = true; break; }
            plus.setImageDrawable(Symbols.drawable(mine ? "check_circle_fill1" : "add", mine ? Accent.colour() : skin.text));
            plus.setContentDescription(mine ? Text.t("Уже в профиле") : Text.t("Добавить в профиль"));
            GradientDrawable round = new GradientDrawable();
            round.setShape(GradientDrawable.OVAL);
            round.setColor(mine ? 0 : Expressive.mix(skin.card, skin.text, .08f));
            plus.setBackground(round);
            ProfileMusic.Track now = ProfileMusic.isFrom(PREVIEW) ? ProfileMusic.now() : null;
            boolean current = now != null && now.id.equals(t.id);
            boolean on = current && ProfileMusic.playing();
            state.set(on, current && ProfileMusic.preparing(), true);
            shade.setAlpha(current ? 1f : .55f);
            title.setTextColor(current ? Expressive.mix(Accent.colour(), skin.text, .2f) : skin.text);
        };
        paint.run();
        repaint.add(paint);

        row.setOnClickListener(v -> {
            ProfileMusic.Track now = ProfileMusic.isFrom(PREVIEW) ? ProfileMusic.now() : null;
            if (now != null && now.id.equals(t.id)) ProfileMusic.toggle(PREVIEW, list);
            else ProfileMusic.play(PREVIEW, list, Math.max(0, list.indexOf(t)));
        });
        plus.setOnClickListener(v -> {
            for (ProfileMusic.Track m : ProfileMusic.mine()) if (m.id.equals(t.id)) return;
            if (!ProfileMusic.add(t)) return;
            paint.run();
            if (Motion.enabled(activity)) {
                plus.setScaleX(.6f);
                plus.setScaleY(.6f);
                plus.animate().scaleX(1f).scaleY(1f).setDuration(Motion.time(320L))
                        .setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start();
            }
            if (added != null) added.run();
        });
        Motion.press(row);
        return row;
    }

    private static TextView note(Activity activity, Skin skin, String words) {
        TextView view = new TextView(activity);
        view.setText(words);
        view.setTextColor(skin.muted());
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(view, 16), dp(view, 18), dp(view, 16), dp(view, 18));
        return view;
    }

    private static int dp(View v, int value) { return Math.round(value * v.getResources().getDisplayMetrics().density); }
}
