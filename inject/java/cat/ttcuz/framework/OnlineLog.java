package cat.ttcuz.framework;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * When people were online, kept as a journal: every time TikTok sends someone's status (OnlineTimes), the session it
 * tells of -- came online, went offline -- is added here or the open one is closed. Only what TikTok already sends
 * to this phone is kept, and only on it (files/ttcuz-online.json); nothing is sent anywhere.
 */
final class OnlineLog {
    private OnlineLog() {}

    static final String KEY = "online_log";
    private static final String FILE = "ttcuz-online.json";
    private static final int PEOPLE = 200, SESSIONS = 300;
    private static final long KEEP = 60L * 86400000L;
    /** uid -> {name, sessions as {came, went (0: still online)}}, newest last */
    private static Map<String, Person> people;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Runnable save = OnlineLog::save;

    private static final class Person { String name = ""; final List<long[]> sessions = new ArrayList<>(); }

    static boolean on() {
        Context c = Ttcuz.context();
        return c != null && c.getSharedPreferences(Ttcuz.PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true);
    }

    static void set(boolean value) {
        Context c = Ttcuz.context();
        if (c != null) c.getSharedPreferences(Ttcuz.PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply();
    }

    /** A status came: came online and went offline, in milliseconds (0 when unknown). */
    static synchronized void saw(String uid, long came, long went) {
        if (came <= 0 || !on() || !load()) return;
        Person person = people.remove(uid);
        if (person == null) person = new Person();
        people.put(uid, person); // the latest seen last
        String name = PinnedFriends.nameOf(uid);
        if (name != null && !name.isEmpty()) person.name = name;
        long end = went > came ? went : 0;
        long[] last = person.sessions.isEmpty() ? null : person.sessions.get(person.sessions.size() - 1);
        if (last != null && last[0] == came) {
            if (last[1] == end) return;
            last[1] = end;
        } else if (last == null || came > last[0]) {
            // a session left open had its end missed: it ended no later than this one began
            if (last != null && last[1] == 0) last[1] = -1;
            person.sessions.add(new long[]{came, end});
            while (person.sessions.size() > SESSIONS) person.sessions.remove(0);
        } else return;
        while (people.size() > PEOPLE) people.remove(people.keySet().iterator().next());
        main.removeCallbacks(save);
        main.postDelayed(save, 3000L);
    }

    private static boolean load() {
        if (people != null) return true;
        Context c = Ttcuz.context();
        if (c == null) return false;
        people = new LinkedHashMap<>();
        try {
            File file = new File(c.getFilesDir(), FILE);
            if (!file.exists()) return true;
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) { byte[] b = new byte[8192]; for (int n; (n = in.read(b)) > 0; ) bytes.write(b, 0, n); }
            JSONObject all = new JSONObject(bytes.toString("UTF-8"));
            long oldest = System.currentTimeMillis() - KEEP;
            for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
                String uid = it.next();
                JSONObject row = all.getJSONObject(uid);
                Person person = new Person();
                person.name = row.optString("n");
                JSONArray list = row.optJSONArray("s");
                for (int i = 0; list != null && i < list.length(); i++) {
                    JSONArray s = list.getJSONArray(i);
                    if (s.getLong(0) >= oldest) person.sessions.add(new long[]{s.getLong(0), s.getLong(1)});
                }
                if (!person.sessions.isEmpty()) people.put(uid, person);
            }
        } catch (Throwable error) { Diary.note("online log read: " + error); }
        return true;
    }

    private static void save() {
        String text;
        synchronized (OnlineLog.class) {
            if (people == null) return;
            try {
                JSONObject all = new JSONObject();
                for (Map.Entry<String, Person> e : people.entrySet()) {
                    JSONArray list = new JSONArray();
                    for (long[] s : e.getValue().sessions) list.put(new JSONArray().put(s[0]).put(s[1]));
                    all.put(e.getKey(), new JSONObject().put("n", e.getValue().name).put("s", list));
                }
                text = all.toString();
            } catch (Throwable error) { Diary.note("online log: " + error); return; }
        }
        Context c = Ttcuz.context();
        if (c == null) return;
        new Thread(() -> {
            try {
                File file = new File(c.getFilesDir(), FILE), part = new File(c.getFilesDir(), FILE + ".part");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(part)) { out.write(text.getBytes("UTF-8")); }
                if (!part.renameTo(file)) Diary.note("online log: rename failed");
            } catch (Throwable error) { Diary.note("online log write: " + error); }
        }, "ttcuz-online-log").start();
    }

    /** The latest session the journal kept for someone, {came, went} (went 0: no end came, -1: missed); null if none. */
    static synchronized long[] last(String uid) {
        if (uid == null || !load()) return null;
        Person person = people.get(uid);
        return person == null || person.sessions.isEmpty() ? null : person.sessions.get(person.sessions.size() - 1).clone();
    }

    static synchronized int size() { return load() ? people.size() : 0; }

    static synchronized void clear() {
        if (!load()) return;
        people.clear();
        main.removeCallbacks(save);
        save();
    }

    // ------------------------------------------------------------------ the journal, in ayumoments

    /** Everyone seen, the latest first; a tap shows that person's sessions. */
    static void show(Context context) {
        Panel panel = Panel.with(context, Skin.remembered(context), Text.t("Когда были в сети"))
                .subtitle(Text.t("Записывается, когда TikTok присылает статус: в чатах и во «Входящих». Хранится только на телефоне, 60 дней."));
        List<String> uids;
        Map<String, Person> copy;
        synchronized (OnlineLog.class) {
            load();
            copy = new LinkedHashMap<>(people);
        }
        uids = new ArrayList<>(copy.keySet());
        Collections.reverse(uids);
        if (uids.isEmpty()) panel.text(Text.t("Пока пусто. Откройте «Входящие» или чат с другом."));
        for (String uid : uids) {
            Person person = copy.get(uid);
            String name = PinnedFriends.nameOf(uid);
            if (name == null || name.isEmpty()) name = person.name.isEmpty() ? uid : person.name;
            long[] last = person.sessions.get(person.sessions.size() - 1);
            String state = last[1] == 0 ? Text.t("в сети с") + " " + OnlineTimes.when(last[0])
                    : last[1] > 0 ? Text.t("вышел(а) в") + " " + OnlineTimes.when(last[1]) : Text.t("был(а) в") + " " + OnlineTimes.when(last[0]);
            final String title = name;
            panel.item(last[1] == 0 ? "visibility" : "schedule", name, state, String.valueOf(person.sessions.size()),
                    () -> sessions(context, title, uid));
        }
        if (!uids.isEmpty()) panel.danger("delete", Text.t("Очистить журнал"), null, OnlineLog::clear);
        panel.quiet(Text.CLOSE, null).show();
    }

    private static void sessions(Context context, String name, String uid) {
        List<long[]> list;
        synchronized (OnlineLog.class) {
            Person person = people == null ? null : people.get(uid);
            list = person == null ? new ArrayList<>() : new ArrayList<>(person.sessions);
        }
        Collections.reverse(list);
        Panel panel = Panel.with(context, Skin.remembered(context), name);
        java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("d MMMM, EEEE", java.util.Locale.getDefault()),
                time = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
        String shownDay = null;
        for (long[] s : list) {
            String today = day.format(new java.util.Date(s[0]));
            if (!today.equals(shownDay)) { panel.section(today); shownDay = today; }
            String from = time.format(new java.util.Date(s[0]));
            String span = s[1] == 0 ? from + " — " + Text.t("сейчас")
                    : s[1] > 0 ? from + " — " + time.format(new java.util.Date(s[1])) : from + " — ?";
            String length = s[1] > 0 ? minutes(s[1] - s[0]) : null;
            panel.info(s[1] == 0 ? "visibility" : "schedule", span, length == null ? (s[1] == 0 ? Text.t("в сети") : Text.t("конец не пришёл")) : length, null);
        }
        panel.quiet(Text.CLOSE, null).show();
    }

    private static String minutes(long millis) {
        long m = Math.max(1, Math.round(millis / 60000.0));
        return m < 60 ? m + " " + Text.t("мин") : (m / 60) + " " + Text.t("ч") + " " + (m % 60) + " " + Text.t("мин");
    }
}
