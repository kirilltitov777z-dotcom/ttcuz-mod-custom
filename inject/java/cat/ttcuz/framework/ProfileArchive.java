package cat.ttcuz.framework;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * Every video of a profile tab saved as one ZIP in Downloads/ttcuz.
 *
 * The tab's pages are loaded as the sort does it (its last card shown until no more come), the addresses are read from
 * the posts on the main thread, then one worker fetches them in turn, the same way the save button does, and streams each
 * into the archive. A photo post goes in as its pictures. Nothing is compressed: video does not shrink, and a stored
 * entry costs no time. The archive is written straight into its Downloads row, so no second copy of it is ever made.
 */
final class ProfileArchive {
    private ProfileArchive() {}

    private static final class Item {
        final String name; final List<List<String>> files = new ArrayList<>(); final boolean photo;
        Item(String name, boolean photo) { this.name = name; this.photo = photo; }
    }

    static void start(final ViewGroup list) {
        if (ProfileSort.busy) return;
        final Context c = list.getContext().getApplicationContext();
        if (!StoragePermissionActivity.ensure(c, () -> start(list))) return;
        ProfileSort.busy = true;
        Screen.say(Text.t("Загружаем все видео..."));
        ProfileSort.loadAll(list, () -> {
            ProfileSort.busy = false;
            List<Item> items = new ArrayList<>();
            String owner = null;
            try {
                Object adapter = ProfileSort.adapter(list);
                List<Object> cards = adapter == null ? null : ProfileSort.items(adapter);
                if (cards != null) for (Object card : new ArrayList<>(cards)) {
                    Object post = ProfileSort.aweme(card, 0);
                    if (post == null) continue;
                    if (owner == null) owner = NativeRead.string(NativeRead.get(NativeRead.get(post, "getAuthor"), "getUniqueId"));
                    Item item = read(post, items.size() + 1);
                    if (item != null) items.add(item);
                }
            } catch (Throwable error) { Diary.note("profile archive: " + error); }
            if (items.isEmpty()) { Screen.say(Text.t("Нет видео для архива")); return; }
            save(c, items, owner == null || owner.isEmpty() ? "profile" : owner);
        });
    }

    /** What one post adds: its video, or each of its pictures; null when it has neither. */
    private static Item read(Object post, int number) {
        String aid = NativeRead.aid(post);
        String name = String.format(java.util.Locale.ROOT, "%03d_%s", number, aid == null || aid.isEmpty() ? "post" : aid.replaceAll("[^A-Za-z0-9_-]", "_"));
        List<DownloadQuality.Choice> photos = DownloadQuality.photos(post, Download.isEnabled());
        if (photos != null && !photos.isEmpty()) {
            Item item = new Item(name, true);
            for (DownloadQuality.Choice photo : photos) if (!photo.candidates().isEmpty()) item.files.add(photo.candidates());
            return item.files.isEmpty() ? null : item;
        }
        Object video = NativeRead.get(post, "getVideo");
        if (video == null) return null;
        List<String> urls = VideoActions.videoUrls(video, 0);
        if (urls.isEmpty()) return null;
        Item item = new Item(name, false);
        item.files.add(urls);
        return item;
    }

    private static void save(final Context c, final List<Item> items, final String owner) {
        final String key = "archive_" + java.util.UUID.randomUUID();
        final DownloadTasks.Control control = DownloadTasks.begin(key, Text.t("Архив") + " @" + owner + " · " + items.size(), "ttcuz", null);
        control.limit = 320L * 1024L * 1024L;
        final String label = Text.t("Архив профиля");
        Screen.progress(key, label, 0);
        final String file = "ttcuz_" + owner.replaceAll("[^A-Za-z0-9._-]", "_") + "_"
                + new java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.ROOT).format(new java.util.Date()) + ".zip";
        boolean queued = MediaQueue.add(() -> {
            int saved = 0; Uri row = null; boolean okay = false;
            java.io.File temporary = new java.io.File(c.getCacheDir(), key + ".media");
            try {
                java.io.OutputStream target;
                if (Build.VERSION.SDK_INT < 29) {
                    java.io.File dir = new java.io.File(c.getExternalFilesDir(null), "ttcuz");
                    if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("no folder");
                    target = new java.io.FileOutputStream(new java.io.File(dir, file));
                } else {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, file);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/ttcuz");
                    values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    row = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (row == null) throw new java.io.IOException("no row");
                    target = c.getContentResolver().openOutputStream(row);
                    if (target == null) throw new java.io.IOException("no stream");
                }
                try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(new java.io.BufferedOutputStream(target, 1 << 16))) {
                    zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION);
                    for (int i = 0; i < items.size() && !control.cancelled(); i++) {
                        Item item = items.get(i);
                        for (int f = 0; f < item.files.size() && !control.cancelled(); f++) {
                            if (fetch(item.files.get(f), temporary, control, key, label, i, items.size())) {
                                String[] format;
                                try { format = item.photo ? DownloadQuality.imageFormat(temporary) : DownloadQuality.videoFormat(temporary); }
                                catch (java.io.IOException bad) { Diary.note("profile archive: " + item.name + " " + bad); temporary.delete(); continue; }
                                zip.putNextEntry(new java.util.zip.ZipEntry(item.name + (item.photo ? "_" + (f + 1) : "") + "." + format[0]));
                                try (java.io.InputStream in = new java.io.FileInputStream(temporary)) {
                                    byte[] buffer = new byte[1 << 16]; int n;
                                    while ((n = in.read(buffer)) != -1) zip.write(buffer, 0, n);
                                }
                                zip.closeEntry();
                                if (f == 0) saved++;
                            } else Diary.note("profile archive: skipped " + item.name);
                            temporary.delete();
                        }
                    }
                }
                okay = saved > 0 && !control.cancelled();
                if (row != null && okay) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    c.getContentResolver().update(row, done, null, null);
                }
            } catch (Throwable error) {
                Diary.note("profile archive: " + error);
                okay = false;
            } finally {
                temporary.delete();
                // a failed or cancelled archive leaves no half-written file in Downloads
                if (!okay && row != null) try { c.getContentResolver().delete(row, null, null); } catch (Throwable ignored) {}
                DownloadTasks.finish(key, okay);
                Diary.note("profile archive: " + saved + " of " + items.size() + " posts");
                if (okay) {
                    if (saved < items.size()) Screen.say(Text.t("В архиве") + " " + saved + " / " + items.size());
                    Screen.progressDone(key, android.os.Environment.DIRECTORY_DOWNLOADS + "/ttcuz");
                } else {
                    Screen.progressGone(key);
                    if (!control.cancelled()) Screen.say(Text.MEDIA_FAILED);
                }
            }
        });
        if (!queued) { DownloadTasks.finish(key, false); Screen.progressGone(key); Screen.say(Text.MEDIA_FAILED); }
    }

    /** The first address of a file that downloads, into temporary. */
    private static boolean fetch(List<String> addresses, java.io.File temporary, DownloadTasks.Control control, String key, String label, int index, int total) {
        for (String address : addresses) {
            if (control.cancelled()) return false;
            temporary.delete();
            if (Net.download(address, temporary, (percent, got, size) -> {
                int overall = (index * 100 + percent) / Math.max(1, total);
                DownloadTasks.progress(key, overall);
                Screen.progress(key, label, overall);
            }, control)) return true;
        }
        return false;
    }
}
