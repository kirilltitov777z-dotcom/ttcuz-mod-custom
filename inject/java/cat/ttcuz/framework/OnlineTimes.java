package cat.ttcuz.framework;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exact times a person came online and went offline, as TikTok's server sends them.
 *
 * TikTok's status model (X.0IBh, "ActivityStatusResponse": uid, lastActiveTimestamp, lastInactiveTimestamp, in
 * seconds) is built for every status it receives; the patcher calls saw() at the end of its constructor. TikTok itself
 * shows only "active now" or "active N minutes ago" from it -- here the two moments are kept as they came.
 */
public final class OnlineTimes {
    private OnlineTimes() {}

    /** uid -> {came online, went offline}, in milliseconds */
    private static final Map<String, long[]> times = new ConcurrentHashMap<>();

    /** Called at the end of X.0IBh's constructor. */
    public static void saw(Object status) {
        try {
            String uid = NativeRead.string(NativeRead.field(status, "LIZ"));
            long active = NativeRead.number(NativeRead.field(status, "LIZIZ")), inactive = NativeRead.number(NativeRead.field(status, "LIZJ"));
            if (uid.isEmpty() || (active <= 0 && inactive <= 0)) return;
            long[] now = {active * 1000L, inactive * 1000L}, before = times.put(uid, now);
            if (before == null || before[0] != now[0] || before[1] != now[1]) { ChatTools.statusChanged(uid); OnlineLog.saw(uid, now[0], now[1]); }
        } catch (Throwable error) { Diary.note("online times: " + error); }
    }

    /** {came online, went offline} in milliseconds, or null when nothing came for this person. */
    static long[] of(String uid) { return uid == null ? null : times.get(uid); }

    static boolean online(long[] t) { return t != null && t[0] > t[1]; }

    /** "в сети с 14:32" or "вышел(а) в 15:10" (with the date when it is not today); null when unknown. */
    static String line(String uid) {
        long[] t = of(uid);
        if (t == null) {
            // nothing from TikTok yet in this run (a chat just opened): the journal's last session says it until then,
            // instead of no line at all
            long[] kept = OnlineLog.last(uid);
            if (kept == null) return null;
            return kept[1] > 0 ? Text.t("вышел(а) в") + " " + when(kept[1]) : Text.t("был(а) в") + " " + when(kept[0]);
        }
        return online(t) ? Text.t("в сети с") + " " + when(t[0]) : t[1] > 0 ? Text.t("вышел(а) в") + " " + when(t[1]) : null;
    }

    static String when(long millis) {
        if (millis <= 0) return "—";
        java.util.Calendar then = java.util.Calendar.getInstance(), today = java.util.Calendar.getInstance();
        then.setTimeInMillis(millis);
        boolean same = then.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) && then.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR);
        return new java.text.SimpleDateFormat(same ? "HH:mm" : "dd.MM HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(millis));
    }
}
