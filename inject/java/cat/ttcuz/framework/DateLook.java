package cat.ttcuz.framework;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * How the date under a video reads: which of its day, month and year, and after it in brackets the country the video
 * was published from (TikTok's own Aweme.getRegion) -- its flag, its two-letter code, both, or neither:
 * "16.11.2026 (🇰🇿 KZ)". Set in a sheet with a preview at its top.
 */
final class DateLook {
    private DateLook() {}

    static final String DAY = "date_day", MONTH = "date_month", YEAR = "date_year", FLAG = "date_flag", CODE = "date_code";

    private static SharedPreferences prefs() {
        Context c = Ttcuz.context();
        return c == null ? null : c.getSharedPreferences(Ttcuz.PREFS, Context.MODE_PRIVATE);
    }

    // the year is off unless one switches it on: a date under a video is short by default
    static boolean on(String key) { SharedPreferences p = prefs(); boolean byDefault = !YEAR.equals(key); return p == null ? byDefault : p.getBoolean(key, byDefault); }

    static void set(String key, boolean on) { SharedPreferences p = prefs(); if (p != null) p.edit().putBoolean(key, on).apply(); }

    /** A video's country as two capital letters, or null when TikTok gave none. */
    static String region(Object post) {
        try {
            Object value = NativeRead.get(post, "getRegion");
            String code = value instanceof String ? ((String) value).trim().toUpperCase(java.util.Locale.ROOT) : "";
            return code.matches("[A-Z]{2}") ? code : null;
        } catch (Throwable error) { return null; }
    }

    static String flag(String iso) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 2; i++) out.appendCodePoint(0x1F1E6 + iso.charAt(i) - 'A');
        return out.toString();
    }

    /** The caption for a moment (in seconds) and a country; null when nothing of it is left to show. */
    static String text(long seconds, String region) {
        // short, to stay out of the way: a video from today says how long ago ("3 ч назад"), then "вчера" and
        // "позавчера"; an older one has its date, without the year unless the year is switched on
        String recent = on(DAY) ? recent(seconds) : null;
        StringBuilder pattern = new StringBuilder();
        for (String[] part : new String[][]{{DAY, "dd"}, {MONTH, "MM"}, {YEAR, "yyyy"}})
            if (on(part[0])) pattern.append(pattern.length() > 0 ? "." : "").append(part[1]);
        String date = recent != null ? recent : pattern.length() == 0 ? ""
                : new java.text.SimpleDateFormat(pattern.toString(), java.util.Locale.getDefault()).format(new java.util.Date(seconds * 1000L));
        String place = "";
        if (region != null) {
            if (on(FLAG)) place = flag(region);
            if (on(CODE)) place += (place.isEmpty() ? "" : " ") + region;
        }
        String out = date.isEmpty() ? place : place.isEmpty() ? date : date + " (" + place + ")";
        return out.isEmpty() ? null : out;
    }

    /**
     * For a moment in the last three calendar days: today how long ago ("только что", "15 мин назад", "3 ч назад"), then
     * "вчера" and "позавчера"; null for an older one.
     */
    static String recent(long seconds) {
        java.util.Calendar then = java.util.Calendar.getInstance(), day = java.util.Calendar.getInstance();
        then.setTimeInMillis(seconds * 1000L);
        long ago = Math.max(0, System.currentTimeMillis() / 1000L - seconds);
        String today = ago < 60 ? Text.t("только что") : ago < 3600 ? (ago / 60) + " " + Text.t("мин назад") : (ago / 3600) + " " + Text.t("ч назад");
        String[] words = {today, Text.t("вчера"), Text.t("позавчера")};
        for (String word : words) {
            if (then.get(java.util.Calendar.YEAR) == day.get(java.util.Calendar.YEAR)
                    && then.get(java.util.Calendar.DAY_OF_YEAR) == day.get(java.util.Calendar.DAY_OF_YEAR)) return word;
            day.add(java.util.Calendar.DAY_OF_YEAR, -1);
        }
        return null;
    }

    /** "Вид даты": a preview as it stands under a video, then the parts of the date and of the country, each a switch. */
    static void open(final Activity activity, final Runnable changed) {
        final Skin skin = Skin.remembered(activity);
        final float d = activity.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);

        // the preview: the author and the date on dark, the way they sit over a video
        LinearLayout card = new LinearLayout(activity);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Math.round(16 * d), Math.round(14 * d), Math.round(16 * d), Math.round(14 * d));
        GradientDrawable dark = new GradientDrawable();
        dark.setCornerRadius(16 * d);
        dark.setColor(0xFF161616);
        card.setBackground(dark);
        TextView author = new TextView(activity);
        author.setText(Account.profileHandle() != null ? Account.profileHandle() : "ttcuz");
        author.setTextColor(0xFFFFFFFF);
        author.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        author.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        author.setSingleLine(true);
        card.addView(author);
        final TextView date = new TextView(activity);
        date.setTextColor(0x99FFFFFF);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        date.setSingleLine(true);
        LinearLayout.LayoutParams dateAt = new LinearLayout.LayoutParams(0, -2, 1f);
        dateAt.leftMargin = Math.round(8 * d);
        card.addView(date, dateAt);
        box.addView(card, new LinearLayout.LayoutParams(-1, -2));
        final String sample = sampleRegion();
        final Runnable preview = () -> {
            String now = text(System.currentTimeMillis() / 1000L, sample);
            date.setText(now == null ? "" : now);
            date.setVisibility(now == null ? View.GONE : View.VISIBLE);
        };
        preview.run();

        heading(box, skin, d, Text.t("Дата"));
        toggle(box, skin, d, Text.t("День"), DAY, preview, changed);
        toggle(box, skin, d, Text.t("Месяц"), MONTH, preview, changed);
        toggle(box, skin, d, Text.t("Год"), YEAR, preview, changed);
        heading(box, skin, d, Text.t("Страна видео"));
        toggle(box, skin, d, Text.t("Флаг"), FLAG, preview, changed);
        toggle(box, skin, d, Text.t("Код страны"), CODE, preview, changed);
        TextView note = new TextView(activity);
        note.setText(Text.t("Откуда опубликовано видео, по данным TikTok"));
        note.setTextColor(skin.muted());
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setPadding(0, Math.round(6 * d), 0, 0);
        box.addView(note);

        Panel.with(activity, skin, Text.t("Вид даты")).view(box).primary(Text.t("Готово"), null).show();
    }

    /** The country of the phone for the preview, or Kazakhstan when it has none. */
    private static String sampleRegion() {
        String country = java.util.Locale.getDefault().getCountry();
        return country != null && country.matches("[A-Z]{2}") ? country : "KZ";
    }

    private static void heading(LinearLayout box, Skin skin, float d, String words) {
        TextView title = new TextView(box.getContext());
        title.setText(words);
        title.setTextColor(skin.muted());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(0, Math.round(18 * d), 0, Math.round(4 * d));
        box.addView(title);
    }

    private static void toggle(LinearLayout box, Skin skin, float d, String words, final String key,
                               final Runnable preview, final Runnable changed) {
        Context c = box.getContext();
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Math.round(48 * d));
        TextView name = new TextView(c);
        name.setText(words);
        name.setTextColor(skin.text);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        final android.widget.Switch control = new android.widget.Switch(c);
        control.setChecked(on(key));
        int accent = Accent.colour();
        int[][] states = {{android.R.attr.state_checked}, {}};
        control.setThumbTintList(new android.content.res.ColorStateList(states, new int[]{accent, 0xFFBDBDBD}));
        control.setTrackTintList(new android.content.res.ColorStateList(states, new int[]{(accent & 0x00FFFFFF) | 0x66000000, 0x33808080}));
        control.setOnCheckedChangeListener((button, checked) -> {
            set(key, checked);
            preview.run();
            if (changed != null) changed.run();
        });
        row.setOnClickListener(v -> control.toggle());
        row.addView(control);
        box.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }
}
