package cat.ttcuz.framework;

import android.content.Context;

import org.json.JSONObject;

/**
 * One request for what the phone polls the mod's server for: the badges and the directory of profiles together
 * (/v1/sync), at most once in 25 minutes, shared by both. Each was a request of its own every few minutes, and the
 * free plan's 100 000 requests a day are shared by every phone with the mod.
 *
 * A server without /v1/sync (not deployed yet) answers 404: then null, and each asks its old address as before.
 */
final class ServerSync {
    private ServerSync() {}

    private static final long FRESH = 25 * 60 * 1000L;
    private static JSONObject latest;
    private static long at, failedAt;

    /**
     * The last answer, asked again when it is older than 25 minutes; null when there is none to give. A failed ask
     * (no /v1/sync on a server not yet updated, or no answer) is not repeated for six hours: meanwhile each caller
     * asks its old address, which every server has.
     */
    static synchronized JSONObject get(Context context) {
        long now = System.currentTimeMillis();
        if (failedAt != 0 && now - failedAt < 6 * 3600_000L) return null;
        if (latest != null && now - at < FRESH) return latest;
        Net.Answer answer = Net.fetch(Server.ROOT + "/v1/sync", null, ModStats.HEADER, ModStats.here(context));
        try {
            JSONObject read = answer == null || answer.body == null ? null : new JSONObject(new String(answer.body, "UTF-8"));
            if (read == null || !read.has("badges")) { failedAt = now; return null; }
            failedAt = 0; at = now;
            return latest = read;
        } catch (Throwable error) {
            Diary.note("server sync: " + error);
            failedAt = now;
            return null;
        }
    }
}
