package cat.ttcuz.framework;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/**
 * ayumoments: a copy of every direct message the phone holds, kept by the mod in a database of its own
 * (databases/ttcuz-archive.db), so nothing the other side takes back is lost -- not the realtime unsend, not the mark a
 * reload brings, not a message TikTok wipes or drops from its own database later.
 *
 * TikTok's messages are copied as they come: at start, when a chat is opened, the moment an unsend arrives (before
 * TikTok applies it), and every minute while TikTok runs, on any screen and in the background (MessageKeeper keeps it). Only messages newer than the last copied are read, so a
 * pass costs one small query. A message is stored the first time it is seen and never overwritten: the copy keeps what
 * it said before anything changed it. Nothing leaves the phone. It works only with "Читать удалённые сообщения" on.
 */
final class MessageArchive {
    private MessageArchive() {}

    private static final String FILE = "ttcuz-archive.db", LAST = "archive_last_created";
    /** Messages kept at most: the oldest go first beyond this. */
    private static final int MOST = 200_000;
    // every minute, whatever is on screen (the feed too) and in the background while the process lives: a pass reads
    // only what is new, one small query
    private static final long EVERY = 60 * 1000L;
    private static final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ttcuz-archive");
        thread.setDaemon(true);
        return thread;
    });
    private static SQLiteDatabase db;
    private static boolean started;

    /** From the start of TikTok: a copy now, then every few minutes. */
    static synchronized void start(Context context) {
        if (started) return;
        started = true;
        final Context app = context.getApplicationContext();
        main.post(new Runnable() {
            @Override public void run() {
                copy(app);
                main.postDelayed(this, EVERY);
            }
        });
    }

    /** Copies what is new, off the main thread; nothing when deleted messages are not kept. */
    static void copy(Context context) {
        if (context == null || !DeletedMessages.enabled()) return;
        final Context app = context.getApplicationContext();
        worker.execute(() -> {
            try { copyNow(app); } catch (Throwable error) { Diary.note("message archive: " + error); }
        });
    }

    private static synchronized SQLiteDatabase mine(Context context) {
        if (db != null && db.isOpen()) return db;
        db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(FILE), null);
        db.execSQL("CREATE TABLE IF NOT EXISTS msg (conversation TEXT NOT NULL, sender INTEGER NOT NULL, created INTEGER NOT NULL, "
                + "type INTEGER, content TEXT, PRIMARY KEY (conversation, sender, created))");
        return db;
    }

    private static void copyNow(Context context) {
        java.io.File dir = context.getDatabasePath("x").getParentFile();
        String me = Account.liveId();
        java.io.File[] files = dir == null ? null : dir.listFiles((d, n) -> n.endsWith("_im.db") && (me == null || n.contains(me)));
        if (files == null || files.length == 0) return;
        android.content.SharedPreferences prefs = context.getSharedPreferences(Ttcuz.PREFS, Context.MODE_PRIVATE);
        long newest = prefs.getLong(LAST, 0);
        // two days back again each time: a message that arrives late (synced after others newer than it) is still caught,
        // and what was copied already is simply skipped. The IM keeps seconds, milliseconds or microseconds
        long last = Math.max(0, newest - (newest > 100_000_000_000_000L ? 172_800_000_000L : newest > 100_000_000_000L ? 172_800_000L : 172_800L));
        SQLiteDatabase out = mine(context);
        int added = 0;
        for (java.io.File file : files) {
            try (SQLiteDatabase in = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                 Cursor rows = in.rawQuery("SELECT conversation_id, sender, created_time, type, content FROM msg WHERE created_time>? ORDER BY created_time",
                         new String[]{Long.toString(last)})) {
                out.beginTransaction();
                try {
                    ContentValues row = new ContentValues(5);
                    while (rows.moveToNext()) {
                        String content = rows.getString(4);
                        if (content == null || content.isEmpty()) continue;
                        row.clear();
                        row.put("conversation", rows.getString(0));
                        row.put("sender", rows.getLong(1));
                        row.put("created", rows.getLong(2));
                        row.put("type", rows.getInt(3));
                        row.put("content", content);
                        if (out.insertWithOnConflict("msg", null, row, SQLiteDatabase.CONFLICT_IGNORE) != -1) added++;
                        newest = Math.max(newest, rows.getLong(2));
                    }
                    out.setTransactionSuccessful();
                } finally { out.endTransaction(); }
            } catch (Throwable error) {
                Diary.note("message archive: " + file.getName() + ": " + error.getClass().getSimpleName());
            }
        }
        if (newest > last) prefs.edit().putLong(LAST, newest).apply();
        if (added > 0) {
            Diary.note("message archive: " + added + " new");
            out.execSQL("DELETE FROM msg WHERE rowid IN (SELECT rowid FROM msg ORDER BY created DESC LIMIT -1 OFFSET " + MOST + ")");
        }
    }

    /**
     * One chat's archived messages, oldest first: sender, created, type, content. Empty when there are none or the
     * archive cannot be read. Blocks: off the main thread.
     */
    static java.util.List<String[]> of(Context context, String conversation) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        if (context == null || conversation == null) return out;
        try {
            java.util.concurrent.Future<?> done = worker.submit(() -> { try { copyNow(context.getApplicationContext()); } catch (Throwable ignored) { } });
            try { done.get(10, java.util.concurrent.TimeUnit.SECONDS); } catch (Throwable ignored) { }
            try (Cursor rows = mine(context).rawQuery("SELECT sender, created, type, content FROM msg WHERE conversation=? ORDER BY created",
                    new String[]{conversation})) {
                while (rows.moveToNext())
                    out.add(new String[]{String.valueOf(rows.getLong(0)), String.valueOf(rows.getLong(1)), String.valueOf(rows.getInt(2)), rows.getString(3)});
            }
        } catch (Throwable error) { Diary.note("message archive read: " + error); }
        return out;
    }
}
