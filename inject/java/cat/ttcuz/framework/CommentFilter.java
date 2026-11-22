package cat.ttcuz.framework;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Comments left out by what they carry, each kind on its own switch: a sticker, a GIF, a picture.
 *
 * TikTok's page of comments (CommentItemList) is read through getItems(); the call is routed here, and the page's own
 * list loses those comments, so every later read of the same page sees the same comments.
 * A GIF is a sticker struct from Giphy or with a moving picture; a sticker is any other sticker struct.
 */
public final class CommentFilter {
    private CommentFilter() {}

    static final String STICKERS = "comments_hide_stickers", GIFS = "comments_hide_gifs", PICTURES = "comments_hide_pictures";
    private static volatile int mask = -1;

    private static SharedPreferences prefs() {
        Context c = Ttcuz.context();
        return c == null ? null : c.getSharedPreferences(Ttcuz.PREFS, Context.MODE_PRIVATE);
    }

    private static int mask() {
        int known = mask; if (known >= 0) return known;
        SharedPreferences p = prefs(); if (p == null) return 0;
        known = (p.getBoolean(STICKERS, false) ? 1 : 0) | (p.getBoolean(GIFS, false) ? 2 : 0) | (p.getBoolean(PICTURES, false) ? 4 : 0);
        mask = known; return known;
    }

    static boolean on(String key) { SharedPreferences p = prefs(); return p != null && p.getBoolean(key, false); }

    static void set(String key, boolean on) {
        SharedPreferences p = prefs(); if (p != null) p.edit().putBoolean(key, on).apply();
        mask = -1;
    }

    static void reloadSettings() { mask = -1; }

    /** What a comment carries: 1 a sticker, 2 a GIF, 4 a picture, 0 only words. */
    static int kind(Object comment) {
        Object sticker = NativeRead.field(comment, "stickerStruct");
        int kind = 0;
        if (sticker != null) {
            boolean gif = !NativeRead.string(NativeRead.field(sticker, "giphyFormat")).isEmpty() || NativeRead.field(sticker, "animatedUrl") != null;
            kind |= gif ? 2 : 1;
        }
        Object images = NativeRead.field(comment, "imageList");
        if (images instanceof java.util.List && !((java.util.List<?>) images).isEmpty()) kind |= 4;
        return kind;
    }

    /** Called in place of CommentItemList.getItems(). */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static java.util.List getItems(Object page) {
        Object raw = NativeRead.field(page, "items");
        if (!(raw instanceof java.util.List)) raw = NativeRead.get(page, "getItems");
        if (!(raw instanceof java.util.List)) return null;
        java.util.List items = (java.util.List) raw;
        int hide = mask();
        if (hide == 0 || items.isEmpty()) return items;
        try {
            java.util.Iterator it = items.iterator();
            while (it.hasNext()) if ((kind(it.next()) & hide) != 0) it.remove();
            return items;
        } catch (UnsupportedOperationException fixed) {
            // an unmodifiable page: a filtered copy, put back so later reads agree
            java.util.List kept = new java.util.ArrayList(items.size());
            for (Object comment : items) if ((kind(comment) & hide) == 0) kept.add(comment);
            try { page.getClass().getField("items").set(page, kept); } catch (Throwable ignored) {}
            return kept;
        } catch (Throwable error) {
            Diary.note("comment filter: " + error);
            return items;
        }
    }
}
