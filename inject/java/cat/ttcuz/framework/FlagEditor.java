package cat.ttcuz.framework;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Флаги A/B": every server flag (experiment) TikTok read in this run, found by words, and any of them set to a value
 * of one's own -- on, off, a number, a text -- or given back to TikTok. One's own values win over TikTok's and over the
 * mod's (Flags). Most flags are read when TikTok starts, so a change usually needs a restart.
 *
 * A flag only shows up once TikTok has read it: a screen not opened yet has not asked for its flags.
 */
final class FlagEditor {
    private FlagEditor() {}

    /** Words that find the flags of features TikTok keeps for some countries. */
    private static final String[] QUICK = {"repost", "group", "share", "chat", "story", "live", "now", "shop"};
    private static final int SHOWN = 150;

    static void show(final Activity activity, final Runnable changed) {
        final Skin skin = Skin.remembered(activity);
        Panel panel = Panel.with(activity, skin, Text.t("Флаги A/B"))
                .subtitle(Text.t("Флаги, которые TikTok прочитал с запуска") + ": " + Flags.seen.size()
                        + ". " + Text.t("Своё значение важнее TikTok. Чаще всего нужен перезапуск."));
        final EditText search = panel.field(Text.t("Поиск: repost, group, share…"));
        LinearLayout quick = new LinearLayout(activity);
        android.widget.HorizontalScrollView strip = new android.widget.HorizontalScrollView(activity);
        strip.setHorizontalScrollBarEnabled(false);
        strip.addView(quick);
        for (final String word : QUICK) {
            TextView chip = new TextView(activity);
            chip.setText(word);
            chip.setTextColor(skin.text);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            chip.setPadding(dp(activity, 12), dp(activity, 6), dp(activity, 12), dp(activity, 6));
            android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
            pill.setCornerRadius(dp(activity, 16));
            pill.setColor(Expressive.mix(skin.card, skin.text, .08f));
            chip.setBackground(pill);
            chip.setOnClickListener(v -> search.setText(word));
            Motion.press(chip);
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            at.rightMargin = dp(activity, 6);
            quick.addView(chip, at);
        }
        panel.view(strip);
        final LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        panel.view(list);
        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            list.removeAllViews();
            String words = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            Map<String, Object> own = Flags.allMine();
            List<String> keys = new ArrayList<>();
            // one's own values first, then what TikTok read, by name
            for (String key : own.keySet()) if (words.isEmpty() || key.toLowerCase(Locale.ROOT).contains(words)) keys.add(key);
            List<String> rest = new ArrayList<>();
            for (String key : Flags.seen.keySet()) if (!own.containsKey(key) && (words.isEmpty() || key.toLowerCase(Locale.ROOT).contains(words))) rest.add(key);
            java.util.Collections.sort(rest);
            keys.addAll(rest);
            if (keys.isEmpty()) list.addView(note(activity, skin, words.isEmpty() ? Text.t("TikTok ещё не прочитал ни одного флага") : Text.t("Нет такого флага среди прочитанных")));
            for (int i = 0; i < keys.size() && i < SHOWN; i++) list.addView(row(activity, skin, keys.get(i), own.get(keys.get(i)), () -> { fill[0].run(); if (changed != null) changed.run(); }));
            if (keys.size() > SHOWN) list.addView(note(activity, skin, Text.t("И ещё") + " " + (keys.size() - SHOWN) + ". " + Text.t("Уточните поиск.")));
        };
        final Runnable later = () -> fill[0].run();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { list.removeCallbacks(later); list.postDelayed(later, 200); }
        });
        fill[0].run();
        if (!Flags.allMine().isEmpty()) panel.danger("restore", Text.t("Вернуть все флаги TikTok"), null, () -> {
            Flags.clearMine();
            if (changed != null) changed.run();
            Screen.say(Text.t("Все флаги как в TikTok"));
        });
        panel.quiet(Text.CLOSE, null).show();
    }

    private static View row(final Activity activity, final Skin skin, final String key, final Object own, final Runnable changed) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8));
        TextView name = new TextView(activity);
        name.setText((own != null ? "★ " : "") + key);
        name.setTextColor(own != null ? Expressive.mix(Accent.colour(), skin.text, .2f) : skin.text);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setTypeface(Typeface.MONOSPACE);
        TextView value = new TextView(activity);
        Object real = Flags.seen.get(key);
        value.setText((own != null ? Text.t("своё") + ": " + own + "   " : "") + "TikTok: " + (real == null ? "—" : String.valueOf(real)));
        value.setTextColor(skin.muted());
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        row.addView(name);
        row.addView(value);
        row.setOnClickListener(v -> edit(activity, skin, key, changed));
        Motion.press(row);
        return row;
    }

    /** One flag: on / off for a switch, a field for a number or a text; and back to TikTok's. */
    private static void edit(final Activity activity, Skin skin, final String key, final Runnable changed) {
        final Object real = Flags.seen.get(key), own = Flags.mine(key);
        final Object kind = own != null ? own : real;
        Panel panel = Panel.with(activity, skin, key)
                .subtitle("TikTok: " + (real == null ? "—" : real) + (own != null ? "   " + Text.t("своё") + ": " + own : ""));
        if (kind instanceof Boolean || kind == null) {
            panel.option(Text.t("Включено"), Boolean.TRUE.equals(own), false, () -> { Flags.setMine(key, Boolean.TRUE); changed.run(); });
            panel.option(Text.t("Выключено"), Boolean.FALSE.equals(own), false, () -> { Flags.setMine(key, Boolean.FALSE); changed.run(); });
        }
        if (!(kind instanceof Boolean)) {
            final EditText field = panel.field(kind instanceof Number ? Text.t("Число") : Text.t("Значение"));
            field.setText(String.valueOf(own != null ? own : real == null ? "" : real));
            if (kind instanceof Integer || kind instanceof Long) field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
            else if (kind instanceof Float || kind instanceof Double) field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            panel.primary(Text.t("Сохранить"), () -> {
                Object value = typed(kind, field.getText().toString().trim());
                if (value == null) { Screen.say(Text.t("Не подходит для этого флага")); return; }
                Flags.setMine(key, value);
                changed.run();
            });
        }
        if (own != null) panel.quiet(Text.t("Как в TikTok"), () -> { Flags.setMine(key, null); changed.run(); });
        panel.quiet(Text.CLOSE, null).show();
    }

    /** Text typed for a flag, as the type TikTok reads it as (a number for a number); null when it is not one. */
    static Object typed(Object kind, String text) {
        String stored = (kind instanceof Integer ? "i:" : kind instanceof Long ? "l:" : kind instanceof Float ? "f:"
                : kind instanceof Double ? "d:" : "s:") + text;
        return Flags.parse(stored);
    }

    private static TextView note(Activity activity, Skin skin, String words) {
        TextView note = new TextView(activity);
        note.setText(words);
        note.setTextColor(skin.muted());
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        note.setPadding(dp(activity, 8), dp(activity, 12), dp(activity, 8), dp(activity, 12));
        return note;
    }

    private static int dp(Activity activity, int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
