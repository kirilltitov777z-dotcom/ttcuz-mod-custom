package cat.ttcuz.framework;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.ref.WeakReference;

/**
 * A chat's own button, first in TikTok's row of quick chips over the message field (the row is a RecyclerView, so the
 * chip stands beside it in the row's frame and the list is padded to make room). It opens the chat's tools: the whole
 * conversation saved as a text file in Downloads/ttcuz.
 *
 * The messages come from TikTok's own IM database (<uid>_im.db, table msg), read-only, so the file holds the whole
 * history and not only what was scrolled through; when that file cannot be opened, the messages this chat has shown.
 * IM message (X.0CmB): getConversationId, getCreatedAt, getSender, getMsgType, getContent (JSON), getMsgId.
 */
final class ChatTools {
    private ChatTools() {}

    /** TikTok 47.7.3: the frame of the chip row (a3m), the row itself (a42), one chip (a3k) and its label (a4c). */
    private static final int ROW_FRAME = 0x7f0a00d2, ROW = 0x7f0a00e2, CHIP = 0x7f0a00d0, CHIP_LABEL = 0x7f0a00ed;
    private static final int TAG = 0x7e0c0340;

    private static volatile String conversation;
    /** The other person in the open chat (a sender that is not this account), for their online times. */
    private static volatile String peer;
    private static WeakReference<View> chatRoot = new WeakReference<>(null);
    private static long nextLook;
    /** Messages shown in the chat, by id: what is exported when the database cannot be read. */
    private static final java.util.LinkedHashMap<Long, Object> shown = new java.util.LinkedHashMap<Long, Object>() {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<Long, Object> eldest) { return size() > 5000; }
    };

    /** A message row was bound (MessageTimes): remembers the chat and puts its button in the chip row. */
    static void saw(View row, Object message) {
        String id = NativeRead.string(NativeRead.get(message, "getConversationId"));
        if (id.isEmpty()) return;
        if (!id.equals(conversation)) { synchronized (shown) { shown.clear(); } peer = null; MessageArchive.copy(row.getContext()); }
        conversation = id;
        String sender = String.valueOf(NativeRead.number(NativeRead.get(message, "getSender")));
        if (!sender.equals("0") && !sender.equals(Account.liveId())) peer = sender;
        long msg = NativeRead.number(NativeRead.get(message, "getMsgId"));
        synchronized (shown) { shown.put(msg, message); }
        long now = android.os.SystemClock.uptimeMillis();
        if (now < nextLook) return;
        nextLook = now + 700;
        View root = row.getRootView();
        chatRoot = new WeakReference<>(root);
        View frame = root.findViewById(ROW_FRAME);
        if (frame instanceof FrameLayout && frame.getTag(TAG) == null) attach((FrameLayout) frame);
        refresh();
    }

    /** TikTok received a new status for someone (OnlineTimes): the chip follows it if that is this chat's person. */
    static void statusChanged(String uid) {
        if (uid.equals(peer)) new android.os.Handler(android.os.Looper.getMainLooper()).post(ChatTools::refresh);
    }

    /** The person's exact online time, in the chat's title bar under their name (in place of TikTok's "active N min ago"). */
    private static void refresh() {
        String line = OnlineTimes.line(peer);
        String said = peer + " " + line;
        if (!said.equals(lastSaid)) { lastSaid = said; Diary.note("chat status: " + said); }
        // TikTok 47.7.3: the line under the name is a TextSwitcher (vti) of two texts, "Сейчас в сети" in one of them
        View root = chatRoot.get();
        View switcher = root == null ? null : root.findViewById(id(root, "vti"));
        if (switcher instanceof android.widget.TextSwitcher) {
            android.widget.TextSwitcher lines = (android.widget.TextSwitcher) switcher;
            for (int i = 0; i < lines.getChildCount(); i++) if (lines.getChildAt(i) instanceof TextView) keep((TextView) lines.getChildAt(i), lines);
            lines.setTag(TAG, line);
            if (line == null) return;
            shown(lines);
            View shown = lines.getCurrentView();
            if (!(shown instanceof TextView) || !line.contentEquals(((TextView) shown).getText())) lines.setCurrentText(line);
            return;
        }
        TextView name = nameView();
        if (name == null) return;
        TextView status = statusUnder(name);
        if (status == null) return;
        status.setTag(TAG, line);
        if (line == null) return;
        status.setVisibility(View.VISIBLE);
        if (!line.contentEquals(status.getText())) status.setText(line);
    }

    private static String lastSaid;
    private static int switcherId;

    private static int id(View root, String name) {
        if (switcherId == 0) switcherId = root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
        return switcherId == 0 ? View.NO_ID : switcherId;
    }

    /** TikTok switching its line back ("Сейчас в сети") is undone: the switcher keeps the exact time. */
    /**
     * The status line shown, and kept shown: TikTok hides its line (or the row holding it) on its own updates, and the
     * line came back only with the next status. Its row is watched and the line made visible again whenever TikTok
     * lays it out hidden while the mod has something to say.
     */
    private static void shown(final View lines) {
        lines.setVisibility(View.VISIBLE);
        final View row = lines.getParent() instanceof View ? (View) lines.getParent() : null;
        if (row == null) return;
        row.setVisibility(View.VISIBLE);
        if (lines.getTag(TAG + 2) != null) return;
        lines.setTag(TAG + 2, Boolean.TRUE);
        View holder = row.getParent() instanceof View ? (View) row.getParent() : row;
        holder.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (!(lines.getTag(TAG) instanceof String)) return;
            if (lines.getVisibility() != View.VISIBLE || row.getVisibility() != View.VISIBLE)
                lines.post(() -> { if (lines.getTag(TAG) instanceof String) { lines.setVisibility(View.VISIBLE); row.setVisibility(View.VISIBLE); } });
        });
    }

    private static void keep(final TextView text, final android.widget.TextSwitcher lines) {
        if (text.getTag(TAG + 1) != null) return;
        text.setTag(TAG + 1, Boolean.TRUE);
        text.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence t, int a, int b, int n) {}
            @Override public void onTextChanged(CharSequence t, int a, int b, int n) {}
            @Override public void afterTextChanged(android.text.Editable t) {
                final Object mine = lines.getTag(TAG);
                if (mine instanceof String && !((String) mine).contentEquals(t))
                    lines.post(() -> { if (lines.getTag(TAG) == mine) lines.setCurrentText((String) mine); });
            }
        });
    }

    /**
     * TikTok's status line under the name (the name's sibling, or its parent's), or one added after the name when the
     * name sits in a vertical LinearLayout without one. TikTok rewrites its own line on its updates: the line is put back.
     */
    private static TextView statusUnder(TextView name) {
        TextView found = null;
        ViewGroup at = name.getParent() instanceof ViewGroup ? (ViewGroup) name.getParent() : null;
        for (int up = 0; up < 2 && at != null && found == null; up++) {
            java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
            queue.add(at);
            while (!queue.isEmpty() && found == null) {
                View v = queue.poll();
                if (v instanceof TextView && v != name && !(v instanceof android.widget.EditText) && (v.getTag(TAG + 1) != null || below(v, name))) found = (TextView) v;
                else if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) queue.add(((ViewGroup) v).getChildAt(i));
            }
            at = at.getParent() instanceof ViewGroup ? (ViewGroup) at.getParent() : null;
        }
        if (found == null && name.getParent() instanceof android.widget.LinearLayout
                && ((android.widget.LinearLayout) name.getParent()).getOrientation() == android.widget.LinearLayout.VERTICAL) {
            android.widget.LinearLayout column = (android.widget.LinearLayout) name.getParent();
            found = new TextView(name.getContext());
            found.setSingleLine(true);
            found.setTextSize(TypedValue.COMPLEX_UNIT_PX, name.getTextSize() * .75f);
            found.setTextColor(name.getTextColors().withAlpha(160));
            found.setGravity(name.getGravity());
            column.addView(found, column.indexOfChild(name) + 1, new android.widget.LinearLayout.LayoutParams(-2, -2));
        }
        if (found != null && found.getTag(TAG + 1) == null) {
            final TextView status = found;
            status.setTag(TAG + 1, Boolean.TRUE);
            status.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence t, int a, int b, int n) {}
                @Override public void onTextChanged(CharSequence t, int a, int b, int n) {}
                @Override public void afterTextChanged(android.text.Editable t) {
                    Object mine = status.getTag(TAG);
                    if (mine instanceof String && !((String) mine).contentEquals(t)) status.post(() -> { if (status.getTag(TAG) == mine) status.setText((String) mine); });
                }
            });
        }
        return found;
    }

    private static void attach(final FrameLayout frame) {
        View row = frame.findViewById(ROW);
        if (!(row instanceof ViewGroup)) return;
        Context c = frame.getContext();
        float d = c.getResources().getDisplayMetrics().density;
        TextView chip = new TextView(c);
        chip.setText(Text.t("✦ Чат"));
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        chip.setTextColor(0xFFFFFFFF);
        chip.setPadding(Math.round(14 * d), 0, Math.round(14 * d), 0);
        // dressed as TikTok's chips: the same background and the same label colour and size
        View model = row.findViewById(CHIP);
        Drawable look = model == null ? null : model.getBackground();
        if (look != null && look.getConstantState() != null) chip.setBackground(look.getConstantState().newDrawable(c.getResources()).mutate());
        View label = model == null ? null : model.findViewById(CHIP_LABEL);
        if (label instanceof TextView) {
            chip.setTextColor(((TextView) label).getTextColors());
            chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, ((TextView) label).getTextSize());
            chip.setTypeface(((TextView) label).getTypeface());
        }
        int left = model != null && model.getLeft() > 0 ? model.getLeft() : Math.round(16 * d);
        int height = model != null && model.getHeight() > 0 ? model.getHeight() : Math.round(33 * d);
        // in line with the chips: the row fills the frame from its top, so a chip's top in the row is its top here
        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, height,
                model != null ? Gravity.LEFT | Gravity.TOP : Gravity.LEFT | Gravity.CENTER_VERTICAL);
        at.leftMargin = left;
        if (model != null) at.topMargin = model.getTop();
        chip.setOnClickListener(v -> open(v.getContext()));
        frame.addView(chip, at);
        frame.setTag(TAG, chip);
        final ViewGroup list = (ViewGroup) row;
        final int gap = Math.round(8 * d);
        chip.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int room = v.getRight() + gap - left;
            if (list.getPaddingLeft() != room) { list.setClipToPadding(false); list.setPadding(room, list.getPaddingTop(), list.getPaddingRight(), list.getPaddingBottom()); }
        });
    }

    // ------------------------------------------------------------------ the chat's own page ("…" in the chat)

    /**
     * The list of the chat's own page ("…"), TikTok's id/kup: found by its name in TikTok's resources, so a new release
     * that numbers it anew still finds it (the number written here before was another view's, and the page was never found).
     */
    private static int detailsList;
    static int detailsList(Context context) {
        if (detailsList == 0) {
            int id = context.getResources().getIdentifier("kup", "id", context.getPackageName());
            detailsList = id != 0 ? id : 0x7f0a3a59;   // TikTok 47.7.3's
        }
        return detailsList;
    }
    private static final java.util.Map<View, Boolean> watchedPages = new java.util.WeakHashMap<>();

    /** A screen came to the front: TikTok's host activity may show a chat's page, which gets "Экспорт чата". */
    static void watchDetails(android.app.Activity activity) {
        if (!activity.getClass().getName().endsWith(".TikTokHostActivity")) return;
        View decor = activity.getWindow().getDecorView();
        synchronized (watchedPages) { if (watchedPages.containsKey(decor)) return; watchedPages.put(decor, Boolean.TRUE); }
        decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            View list = decor.findViewById(detailsList(decor.getContext()));
            if (list != null && !(list instanceof LinearLayout) && !saidKind) { saidKind = true; Diary.note("chat page: its list is a " + list.getClass().getName()); }
            if (list instanceof LinearLayout && list.findViewWithTag(EXPORT_ROW) == null) decor.post(() -> exportRow((LinearLayout) list));
        });
    }

    private static boolean saidKind;
    private static final String EXPORT_ROW = "ttcuz-export-row";

    /** "Экспорт чата" as the list's last row, drawn like the rows above it (their height, icon place and text). */
    private static void exportRow(LinearLayout list) {
        if (list.findViewWithTag(EXPORT_ROW) != null || list.getChildCount() == 0) return;
        Context c = list.getContext();
        float d = c.getResources().getDisplayMetrics().density;
        View sample = list.getChildAt(0);
        TextView words = sample instanceof ViewGroup ? firstText((ViewGroup) sample) : null;
        int colour = words != null ? words.getCurrentTextColor() : 0xFFFFFFFF;
        LinearLayout row = new LinearLayout(c);
        row.setTag(EXPORT_ROW);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(Math.round(16 * d), 0, Math.round(16 * d), 0);
        row.setClickable(true);
        row.setContentDescription(Text.t("Экспорт чата"));
        android.util.TypedValue ripple = new android.util.TypedValue();
        if (c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) row.setBackgroundResource(ripple.resourceId);
        android.widget.ImageView icon = new android.widget.ImageView(c);
        icon.setImageDrawable(SettingsGlyph.make("download", colour));
        row.addView(icon, new LinearLayout.LayoutParams(Math.round(22 * d), Math.round(22 * d)));
        TextView label = new TextView(c);
        label.setText(Text.t("Экспорт чата"));
        label.setTextColor(colour);
        if (words != null) { label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, words.getTextSize()); label.setTypeface(words.getTypeface()); }
        else label.setTextSize(17);
        label.setPadding(Math.round(12 * d), 0, 0, 0);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(v -> {
            String id = conversation;
            if (id == null) { Screen.say(Text.t("Откройте чат, потом его меню")); return; }
            String name = title();
            export(c.getApplicationContext(), id, name != null ? name : pageName(list));
        });
        int height = sample.getHeight() > 0 ? sample.getHeight() : Math.round(60 * d);
        list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
    }

    /** The person's name on the chat's page: the largest text above the page's list (its header). */
    private static String pageName(View list) {
        int[] at = new int[2];
        list.getLocationOnScreen(at);
        int top = at[1];
        TextView best = null;
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
        queue.add(list.getRootView());
        while (!queue.isEmpty()) {
            View v = queue.poll();
            if (!v.isShown()) continue;
            if (v instanceof TextView && ((TextView) v).getText().length() > 0) {
                v.getLocationOnScreen(at);
                if (at[1] < top && (best == null || ((TextView) v).getTextSize() > best.getTextSize())) best = (TextView) v;
            } else if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) queue.add(((ViewGroup) v).getChildAt(i));
        }
        return best == null ? null : best.getText().toString().trim();
    }

    private static TextView firstText(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView && ((TextView) child).getText().length() > 0) return (TextView) child;
            if (child instanceof ViewGroup) { TextView found = firstText((ViewGroup) child); if (found != null) return found; }
        }
        return null;
    }

    private static void open(Context c) {
        final String id = conversation;
        if (id == null) { Screen.say(Text.t("Чат ещё не загрузился")); return; }
        Panel panel = Panel.with(c, Skin.remembered(c), Text.t("Чат"));
        long[] t = OnlineTimes.of(peer);
        if (t != null) {
            panel.info("schedule", OnlineTimes.when(t[0]), OnlineTimes.online(t) ? Text.t("Зашёл(ла) в сеть, сейчас в сети") : Text.t("Зашёл(ла) в сеть"), null);
            panel.info("schedule", OnlineTimes.when(t[1]), Text.t("Вышел(ла) из сети"), null);
        }
        panel.item("download", Text.t("Экспорт переписки"), Text.t("Вся история в текстовый файл, в Загрузки/ttcuz"), () -> export(c.getApplicationContext(), id, title()));
        panel.quiet(Text.CLOSE, null).show();
    }

    /** A shown view starting under the name, still in the title bar. */
    private static boolean below(View v, View name) {
        if (!v.isShown()) return false;
        int[] a = new int[2], b = new int[2];
        v.getLocationOnScreen(a);
        name.getLocationOnScreen(b);
        return a[1] >= b[1] + name.getHeight() - 2 && a[1] < v.getRootView().getHeight() / 6;
    }

    /** The other person's name, as the chat's title bar shows it. */
    private static String title() {
        TextView name = nameView();
        return name == null ? null : name.getText().toString();
    }

    /** The title bar's name: the first shown text in the top tenth of the chat, past its back button. */
    private static TextView nameView() {
        View root = chatRoot.get();
        if (root == null) return null;
        int limit = root.getHeight() / 10;
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        int[] at = new int[2];
        while (!queue.isEmpty()) {
            View v = queue.poll();
            if (v.getVisibility() != View.VISIBLE) continue;
            if (v instanceof TextView && v.getTag(TAG + 1) == null) {
                CharSequence words = ((TextView) v).getText();
                v.getLocationOnScreen(at);
                if (words != null && words.length() > 0 && at[1] < limit && at[0] > v.getRootView().getWidth() / 10) return (TextView) v;
            }
            if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) queue.add(((ViewGroup) v).getChildAt(i));
        }
        return null;
    }

    private static void export(final Context c, final String id, final String peer) {
        Screen.say(Text.t("Собираю переписку…"));
        // a thread of its own at normal priority: in the media queue (two lowest-priority workers shared with every
        // download) the export waited behind whatever was being fetched
        Thread work = new Thread(() -> {
            try {
                java.util.List<String[]> rows = fromDatabase(c, id);
                String source = "database";
                if (rows == null) { rows = fromScreen(); source = "screen"; }
                int restored = DeletedMessages.enabled() ? withArchive(rows, MessageArchive.of(c, id)) : 0;
                if (restored > 0) source += " + " + restored + " from the archive";
                String me = Account.liveId();
                String other = peer == null ? Text.t("Собеседник") : peer;
                StringBuilder out = new StringBuilder(64 + rows.size() * 80);
                out.append(Text.t("Переписка с ")).append(other).append('\n');
                out.append(Text.t("Сообщений: ")).append(rows.size()).append("\n\n");
                java.text.SimpleDateFormat when = new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault());
                java.util.Date date = new java.util.Date();
                String mine = Text.t("Я"), gone = " [" + Text.t("удалено") + "]";
                for (String[] row : rows) {
                    date.setTime(millis(parse(row[1])));
                    out.append('[').append(when.format(date)).append("] ").append(row[0].equals(me) ? mine : other).append(": ").append(words(row[3], row[2]));
                    if (row.length > 4 && "1".equals(row[4])) out.append(gone);
                    out.append('\n');
                }
                String safe = other.replaceAll("[^\\p{L}\\p{N}_.-]", "_");
                String name = "ttcuz_chat_" + safe + "_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.ROOT).format(new java.util.Date()) + ".txt";
                save(c, name, out.toString());
                Diary.note("chat export: " + rows.size() + " messages from the " + source);
                Screen.say(Text.t("Сохранено в Загрузки/ttcuz: ") + rows.size() + Text.t(" сообщ."));
            } catch (Throwable error) {
                Diary.note("chat export: " + error);
                Screen.say(Text.t("Не удалось сохранить переписку"));
            }
        }, "ttcuz-chat-export");
        work.setDaemon(true);
        work.start();
    }

    /**
     * The mod's own copies merged in: a message TikTok no longer has comes back (marked deleted), one whose words TikTok
     * wiped gets them back. Rows are matched by sender and time. Returns how many were brought back.
     */
    static int withArchive(java.util.List<String[]> rows, java.util.List<String[]> kept) {
        if (kept.isEmpty()) return 0;
        java.util.Map<String, String[]> have = new java.util.HashMap<>();
        for (String[] row : rows) have.put(row[0] + "@" + row[1], row);
        int brought = 0;
        for (String[] copy : kept) {
            String[] row = have.get(copy[0] + "@" + copy[1]);
            if (row == null) { rows.add(new String[]{copy[0], copy[1], copy[2], copy[3], "1"}); brought++; }
            else if (!said(row[3]) && said(copy[3])) { row[3] = copy[3]; if (row.length > 4) row[4] = "1"; brought++; }
        }
        if (brought > 0) java.util.Collections.sort(rows, (a, b) -> Long.compare(parse(a[1]), parse(b[1])));
        return brought;
    }

    /** Whether a message's content still says something (TikTok empties a taken-back one). */
    private static boolean said(String content) {
        if (content == null || content.length() < 3) return false;
        try { org.json.JSONObject json = new org.json.JSONObject(content); return json.length() > 0 && !json.optString("text", "x").isEmpty(); }
        catch (Throwable error) { return true; }
    }

    /**
     * sender, created time, type, content, deleted ("1" or "0") -- oldest first, every message the phone holds, the
     * deleted ones too (marked); null when TikTok's database cannot be read.
     */
    static java.util.List<String[]> fromDatabase(Context c, String id) {
        java.io.File dir = c.getDatabasePath("x").getParentFile();
        String me = Account.liveId();
        java.io.File[] files = dir == null ? null : dir.listFiles((d, n) -> n.endsWith("_im.db") && (me == null || n.contains(me)));
        if (files == null || files.length == 0) return null;
        for (java.io.File file : files) {
            try (android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openDatabase(file.getPath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
                 android.database.Cursor rows = db.rawQuery("SELECT sender, created_time, type, content, deleted FROM msg WHERE conversation_id=? ORDER BY created_time, index_in_conversation", new String[]{id})) {
                java.util.List<String[]> out = new java.util.ArrayList<>();
                while (rows.moveToNext()) out.add(new String[]{String.valueOf(rows.getLong(0)), String.valueOf(rows.getLong(1)), String.valueOf(rows.getInt(2)), rows.getString(3), rows.getInt(4) != 0 ? "1" : "0"});
                if (!out.isEmpty()) return out;
            } catch (Throwable error) {
                Diary.note("chat export: database " + file.getName() + ": " + error.getClass().getSimpleName());
            }
        }
        return null;
    }

    private static java.util.List<String[]> fromScreen() {
        java.util.List<Object> messages;
        synchronized (shown) { messages = new java.util.ArrayList<>(shown.values()); }
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (Object m : messages)
            out.add(new String[]{String.valueOf(NativeRead.number(NativeRead.get(m, "getSender"))), String.valueOf(NativeRead.number(NativeRead.get(m, "getCreatedAt"))),
                    String.valueOf(NativeRead.number(NativeRead.get(m, "getMsgType"))), NativeRead.string(NativeRead.get(m, "getContent"))});
        out.sort((a, b) -> Long.compare(parse(a[1]), parse(b[1])));
        return out;
    }

    private static long parse(String n) { try { return Long.parseLong(n); } catch (NumberFormatException e) { return 0; } }

    /** The IM keeps seconds, milliseconds or microseconds depending on the field; all become milliseconds. */
    static long millis(long t) { return t > 100_000_000_000_000L ? t / 1000 : t > 100_000_000_000L ? t : t * 1000; }

    /** What a message says: its text, or what it carries in brackets. */
    static String words(String content, String type) {
        try {
            org.json.JSONObject json = new org.json.JSONObject(content == null ? "" : content);
            String text = json.optString("text", "");
            if (!text.isEmpty()) return text.replace('\n', ' ');
            String aweme = json.optString("itemId", json.optString("aweme_id", ""));
            if (!aweme.isEmpty()) return "[" + Text.t("видео") + " https://www.tiktok.com/@/video/" + aweme + "]";
            if (json.has("sticker_id") || json.has("stickerType") || json.has("url")) return "[" + Text.t("стикер или фото") + "]";
        } catch (Throwable ignored) {}
        return "[" + Text.t("вложение") + " " + type + "]";
    }

    /** A text file into Downloads/ttcuz. */
    private static void save(Context c, String name, String body) throws Exception {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (Build.VERSION.SDK_INT < 29) {
            java.io.File dir = new java.io.File(c.getExternalFilesDir(null), "ttcuz");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("no folder");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, name))) { out.write(bytes); }
            return;
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/ttcuz");
        Uri row = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (row == null) throw new java.io.IOException("no row");
        try (java.io.OutputStream out = c.getContentResolver().openOutputStream(row)) {
            if (out == null) throw new java.io.IOException("no stream");
            out.write(bytes);
        }
    }
}
