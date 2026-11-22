package cat.ttcuz.framework;

import android.app.Activity;
import android.content.Context;

/**
 * TikTok's own slips, made harmless where the mod sets them off.
 *
 * TikTok's in-app push (the "shared a live with you" banner, X/0GbU) reads its words from a context whose resources
 * do not see TikTok's NxString tables, and the whole app closed on Resources$NotFoundException 0x7f11419f. The words
 * are looked up as asked, then through the screen on top (whose resources do see them); a banner without its words
 * is better than TikTok closing.
 */
public final class SafeText {
    private SafeText() {}

    public static String getString(Context context, int id) {
        try { return context.getString(id); } catch (RuntimeException missing) { /* below */ }
        Activity top = Screen.now();
        if (top != null && top != context) {
            try { return top.getString(id); } catch (RuntimeException alsoMissing) { /* below */ }
        }
        Diary.note("text " + Integer.toHexString(id) + " missing: left empty");
        return "";
    }

    /** ListProtector.add(list, index, item) with the index kept inside the list (the share panel's danmaku move). */
    public static void add(java.util.List list, int index, Object item) {
        if (list == null) return;
        list.add(Math.max(0, Math.min(index, list.size())), item);
    }

    /** EditText.setSelection, the cursor kept inside the field's text (which may have lost the badge marks). */
    public static void setSelection(android.widget.EditText field, int at) {
        int length = field.length();
        field.setSelection(Math.max(0, Math.min(at, length)));
    }

    public static void setSelection(android.widget.EditText field, int start, int stop) {
        int length = field.length();
        field.setSelection(Math.max(0, Math.min(start, length)), Math.max(0, Math.min(stop, length)));
    }

    /** Editable.setSpan kept inside the text; a span that would be left empty where it may not be is not set. */
    public static void setSpan(android.text.Editable text, Object span, int start, int stop, int flags) {
        int length = text.length();
        int from = Math.max(0, Math.min(start, length)), to = Math.max(from, Math.min(stop, length));
        if (from == to && to != stop && (flags & android.text.Spanned.SPAN_POINT_MARK_MASK) == android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) return;
        text.setSpan(span, from, to, flags);
    }
}
