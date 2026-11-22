package cat.ttcuz.framework;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * TikTok's own leftovers removed: its caches (video pieces, pictures, temporary files -- all fetched again when needed)
 * and its logs and crash dumps. Nothing that holds the account, settings, drafts, chats or the app's code is touched:
 * only the cache folders Android itself treats as disposable, and folders named as logs.
 *
 * What the app's data holds, biggest first, goes to the mod's journal each time, so what is left can be judged.
 */
final class Junk {
    private Junk() {}

    /** Folders of logs, traces and crash dumps (ByteDance's alog, npth, apm, slardar among them). */
    private static final Pattern LOGS = Pattern.compile("(?i)(^|[._-])(alogs?|logs?|npth|apm|crash(es)?|tombstones?|slardar|anrs?|traces?|dumps?)($|[._-])");

    static void clean(final Context context) {
        final Context app = context.getApplicationContext();
        Screen.say(Text.t("Чистим мусор TikTok..."));
        boolean queued = MediaQueue.add(() -> {
            long freed = 0;
            try {
                File data = app.getDataDir();
                Diary.note("junk: before -- " + breakdown(data));
                List<File> targets = new ArrayList<>();
                targets.add(app.getCacheDir());
                File external = app.getExternalCacheDir();
                if (external != null) targets.add(external);
                for (File root : new File[]{data, app.getFilesDir()}) {
                    File[] inside = root.listFiles();
                    if (inside != null) for (File each : inside) if (each.isDirectory() && LOGS.matcher(each.getName()).find()) targets.add(each);
                }
                for (File target : targets) freed += emptyOut(target);
                Diary.note("junk: freed " + (freed >> 20) + " MB from " + targets.size() + " folders; after -- " + breakdown(data));
            } catch (Throwable error) {
                Diary.note("junk: " + error);
            }
            Screen.say(Text.t("Освобождено") + ": " + (freed >> 20) + " " + Text.t("МБ"));
        });
        if (!queued) Screen.say(Text.MEDIA_FAILED);
    }

    /** What is inside a folder, removed; the folder itself stays (TikTok may hold it open). Returns the bytes freed. */
    private static long emptyOut(File folder) {
        long freed = 0;
        File[] inside = folder == null ? null : folder.listFiles();
        if (inside == null) return 0;
        for (File each : inside) freed += remove(each, 0);
        return freed;
    }

    private static long remove(File file, int depth) {
        long freed = 0;
        // a link is removed, never followed out of the app's data
        if (file.isDirectory() && !isLink(file) && depth < 32) {
            File[] inside = file.listFiles();
            if (inside != null) for (File each : inside) freed += remove(each, depth + 1);
        } else freed = file.length();
        return file.delete() ? freed : (file.isDirectory() ? freed : 0);
    }

    private static boolean isLink(File file) {
        try { return java.nio.file.Files.isSymbolicLink(file.toPath()); } catch (Throwable error) { return true; }
    }

    private static long size(File file, int depth) {
        if (isLink(file)) return 0;
        if (!file.isDirectory()) return file.length();
        long total = 0;
        File[] inside = file.listFiles();
        if (inside != null && depth < 32) for (File each : inside) total += size(each, depth + 1);
        return total;
    }

    /** The biggest folders of the app's data, two levels down: "files/xyz 120 MB, ...". */
    private static String breakdown(File data) {
        List<Object[]> found = new ArrayList<>();
        File[] top = data.listFiles();
        long all = 0;
        if (top != null) for (File each : top) {
            long bytes = size(each, 0); all += bytes;
            File[] inside = each.isDirectory() && !isLink(each) ? each.listFiles() : null;
            if (inside != null && bytes > (8L << 20)) for (File sub : inside) { long b = size(sub, 1); if (b > (4L << 20)) found.add(new Object[]{each.getName() + "/" + sub.getName(), b}); }
            else found.add(new Object[]{each.getName(), bytes});
        }
        Collections.sort(found, (a, b) -> Long.compare((Long) b[1], (Long) a[1]));
        StringBuilder line = new StringBuilder().append(all >> 20).append(" MB:");
        for (int i = 0; i < found.size() && i < 25; i++) line.append(' ').append(found.get(i)[0]).append(' ').append(((Long) found.get(i)[1]) >> 20).append(',');
        return line.toString();
    }
}
