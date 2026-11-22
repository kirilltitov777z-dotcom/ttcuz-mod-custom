package cat.ttcuz.framework;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Music on a profile, as Telegram has it. A person puts up to 12 TikTok sounds on their profile; everybody with the mod
 * sees a note button among that profile's buttons, in place of TikTok's suggested-accounts one, which opens the
 * player "Треки @nick": the cover, title and artist, the seek line, previous / play / next, and the list. One's own
 * profile always has the button, to add music.
 *
 * The profile keeps only each sound's id, title and artist (the server's "tracks"); the sound itself, its cover and its
 * address are asked from TikTok (MusicDetailApi.queryMusic) when it is played, so nothing is stored or uploaded but text.
 */
final class ProfileMusic {
    private ProfileMusic() {}

    static final int MAX = 12;
    private static final String KEY = "profile_tracks";
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** One track: a TikTok sound id with its title and artist. */
    static final class Track {
        final String id, title, artist;
        Track(String id, String title, String artist) { this.id = id; this.title = title; this.artist = artist; }
    }

    // ------------------------------------------------- whose tracks

    private static SharedPreferences prefs() {
        Context c = Ttcuz.context();
        return c == null ? null : AccountAppearance.prefs(c);
    }

    static List<Track> mine() {
        SharedPreferences p = prefs();
        return p == null ? Collections.<Track>emptyList() : parse(p.getString(KEY, "[]"));
    }

    static List<Track> parse(String json) {
        List<Track> out = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(json);
            for (int i = 0; i < list.length() && out.size() < MAX; i++) {
                JSONObject t = list.optJSONObject(i);
                String id = t == null ? "" : t.optString("id", "");
                if (id.matches("[0-9]{1,24}")) out.add(new Track(id, t.optString("title", ""), t.optString("artist", "")));
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /** The list as the server takes it (tracks in the profile's style). */
    static JSONArray json(List<Track> tracks) {
        JSONArray list = new JSONArray();
        try {
            for (Track t : tracks) list.put(new JSONObject().put("id", t.id).put("title", clip(t.title)).put("artist", clip(t.artist)));
        } catch (Throwable ignored) { }
        return list;
    }

    private static String clip(String text) {
        String clean = text == null ? "" : text.replaceAll("[\\x00-\\x1f\\x7f]", " ").trim();
        return clean.length() > 120 ? clean.substring(0, 120) : clean;
    }

    private static void save(List<Track> tracks) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putString(KEY, json(tracks).toString()).apply();
        TtcuzProfileSync.publishOwn();
        refreshIslands();
    }

    static List<Track> of(String uid) {
        if (uid == null) return Collections.emptyList();
        if (uid.equals(Account.liveId())) return mine();
        TtcuzProfileSync.Metadata m = TtcuzProfileSync.cached(uid);
        return m == null || m.tracks == null ? Collections.<Track>emptyList() : m.tracks;
    }

    static boolean add(Track track) {
        List<Track> list = new ArrayList<>(mine());
        for (Track t : list) if (t.id.equals(track.id)) { Screen.say(Text.t("Этот трек уже есть")); return false; }
        if (list.size() >= MAX) { Screen.say(Text.t("Не больше 12 треков")); return false; }
        list.add(track);
        save(list);
        Screen.say(Text.t("Трек добавлен в профиль"));
        return true;
    }

    static void remove(String id) {
        List<Track> list = new ArrayList<>(mine());
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(id)) { list.remove(i); break; }
        save(list);
    }

    // ------------------------------------------------- the sound, from TikTok

    /** What TikTok says of a sound: where it plays from and its cover. Blocks: off the main thread. */
    static String[] resolve(String id) {
        try {
            Object api = Class.forName("com.ss.android.ugc.aweme.music.api.MusicDetailApi").getField("LIZ").get(null);
            Object future = api.getClass().getMethod("queryMusic", String.class, int.class).invoke(api, id, 0);
            Object detail = future.getClass().getMethod("get").invoke(future);
            Object music = detail.getClass().getField("music").get(detail);
            if (music == null) return null;
            return new String[]{first(NativeRead.get(music, "getPlayUrl")), first(NativeRead.get(music, "getCoverMedium")),
                    String.valueOf(NativeRead.get(music, "getMusicName")), String.valueOf(NativeRead.get(music, "getAuthorName"))};
        } catch (Throwable error) {
            Diary.note("profile music " + id + ": " + (error.getCause() != null ? error.getCause() : error));
            return null;
        }
    }

    private static String first(Object urlModel) {
        try {
            Object list = urlModel == null ? null : urlModel.getClass().getMethod("getUrlList").invoke(urlModel);
            if (list instanceof List && !((List<?>) list).isEmpty()) return String.valueOf(((List<?>) list).get(0));
        } catch (Throwable ignored) { }
        return "";
    }

    private static final Map<String, String[]> resolved = Collections.synchronizedMap(new java.util.HashMap<String, String[]>());

    static String[] known(String id) {
        String[] got = resolved.get(id);
        if (got != null) return got;
        got = resolve(id);
        if (got != null) resolved.put(id, got);
        return got;
    }

    /**
     * A sound as TikTok's lists hand it (a Music), as a track; what TikTok said of it with it (its cover, where it plays
     * from) is kept, so its cover needs no question of its own. Null when it has no id.
     */
    static Track trackOf(Object music) {
        try {
            String id = String.valueOf(NativeRead.get(music, "getMid"));
            if (!id.matches("[0-9]{1,24}")) id = String.valueOf(NativeRead.get(music, "getId"));
            if (!id.matches("[0-9]{1,24}")) return null;
            String title = String.valueOf(NativeRead.get(music, "getMusicName")), artist = String.valueOf(NativeRead.get(music, "getAuthorName"));
            if ("null".equals(title)) title = "";
            if ("null".equals(artist)) artist = "";
            String cover = first(NativeRead.get(music, "getCoverMedium"));
            if (!TextUtils.isEmpty(cover) && !resolved.containsKey(id))
                resolved.put(id, new String[]{first(NativeRead.get(music, "getPlayUrl")), cover, title, artist});
            return new Track(id, title, artist);
        } catch (Throwable error) { return null; }
    }

    /**
     * Sounds found by words: TikTok's own sound search (/aweme/v1/music/search/, SearchApi.RealApi), whose answer is
     * raw JSON. Every object in it that is a sound (an id, a title and a play_url) is taken. Off the main thread.
     */
    static List<Track> search(String words, long cursor) throws Exception {
        Object api = Class.forName("com.ss.android.ugc.aweme.search.pages.result.core.repo.SearchApi").getMethod("LIZ").invoke(null);
        java.lang.reflect.Method find = null;
        for (java.lang.reflect.Method m : api.getClass().getMethods()) if (m.getName().equals("searchDynamicMusicList")) find = m;
        if (find == null) throw new NoSuchMethodException("searchDynamicMusicList");
        // cursor, keyword, enter_from, count, hot_search, search_id, last_search_id, source, search_source,
        // query_correct_type, is_filter_search, filter_by, sort_type; the rest TikTok leaves empty too
        Object future = find.invoke(api, cursor, words, "search_result", 20, 0, "", "", "", "normal_search",
                1, 0, 0, 0, new java.util.LinkedHashMap<String, Integer>(), "", "", 0, "", null, "", new java.util.HashMap<String, String>());
        Object answer = ((java.util.concurrent.Future<?>) future).get(20, java.util.concurrent.TimeUnit.SECONDS);
        Object json = NativeRead.field(answer, "LL");
        List<Track> out = new ArrayList<>();
        if (json != null) sounds(new org.json.JSONTokener(json.toString()).nextValue(), out, 0);
        return out;
    }

    private static void sounds(Object node, List<Track> out, int depth) {
        if (depth > 12 || out.size() >= 40) return;
        if (node instanceof org.json.JSONArray) {
            org.json.JSONArray a = (org.json.JSONArray) node;
            for (int i = 0; i < a.length(); i++) sounds(a.opt(i), out, depth + 1);
            return;
        }
        if (!(node instanceof org.json.JSONObject)) return;
        org.json.JSONObject o = (org.json.JSONObject) node;
        String id = o.optString("id_str", o.optString("mid", ""));
        if (id.matches("[0-9]{6,24}") && o.has("title") && o.has("play_url")) {
            for (Track t : out) if (t.id.equals(id)) return;
            String title = o.optString("title"), artist = o.optString("author");
            String play = urlOf(o.optJSONObject("play_url")), cover = urlOf(o.optJSONObject("cover_medium"));
            if (cover.isEmpty()) cover = urlOf(o.optJSONObject("cover_thumb"));
            if (!play.isEmpty() && !resolved.containsKey(id)) resolved.put(id, new String[]{play, cover, title, artist});
            out.add(new Track(id, title, artist));
            return;
        }
        for (java.util.Iterator<String> keys = o.keys(); keys.hasNext(); ) sounds(o.opt(keys.next()), out, depth + 1);
    }

    private static String urlOf(org.json.JSONObject model) {
        org.json.JSONArray list = model == null ? null : model.optJSONArray("url_list");
        return list == null ? "" : list.optString(0, "");
    }

    /** A TikTok sound link (tiktok.com/music/Name-7300...) or a bare id: the id, or null. */
    static String idIn(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9]{10,24})").matcher(text);
        String last = null;
        while (m.find()) last = m.group(1);
        return last;
    }

    // ------------------------------------------------- the player

    private static MediaPlayer player;
    private static List<Track> queue = Collections.emptyList();
    private static int index = -1;
    private static boolean preparing, paused;
    private static String owner = "";
    private static final List<Runnable> listeners = new ArrayList<>();

    static boolean playing() { return player != null && !paused && !preparing && index >= 0; }
    /** The track is being fetched or buffered: asked for, not playing yet. */
    static boolean preparing() { return preparing && index >= 0; }
    static Track now() { return index >= 0 && index < queue.size() ? queue.get(index) : null; }
    static boolean isFrom(String uid) { return owner.equals(uid) && index >= 0; }

    static void listen(Runnable r) { listeners.add(r); }
    static void unlisten(Runnable r) { listeners.remove(r); }
    private static void changed() { for (Runnable r : new ArrayList<>(listeners)) try { r.run(); } catch (Throwable ignored) { } refreshIslands(); }

    static int position() { try { return player == null || preparing ? 0 : player.getCurrentPosition(); } catch (Throwable e) { return 0; } }
    static int duration() { try { return player == null || preparing ? 0 : player.getDuration(); } catch (Throwable e) { return 0; } }
    static void seek(int ms) { try { if (player != null && !preparing) player.seekTo(ms); } catch (Throwable ignored) { } }

    static void play(String uid, List<Track> tracks, int at) {
        if (tracks.isEmpty()) return;
        owner = uid == null ? "" : uid;
        queue = new ArrayList<>(tracks);
        index = Math.max(0, Math.min(at, queue.size() - 1));
        start();
    }

    static void toggle(String uid, List<Track> tracks) {
        if (isFrom(uid) && player != null && !preparing) {
            try {
                if (paused) { if (focus(true)) { startedAt = android.os.SystemClock.uptimeMillis(); player.start(); paused = false; PlayerTools.pauseFeed(); } }
                else { player.pause(); paused = true; }
            } catch (Throwable ignored) { }
            changed();
            return;
        }
        play(uid, tracks, 0);
    }

    static void next() { if (!queue.isEmpty()) { index = following(index); start(); } }
    static void previous() { if (!queue.isEmpty()) { index = shuffle() && queue.size() > 1 ? following(index) : (index - 1 + queue.size()) % queue.size(); start(); } }

    // ------------------------------------------------- repeat and shuffle (kept between runs)

    static final int REPEAT_OFF = 0, REPEAT_ALL = 1, REPEAT_ONE = 2;
    private static final String REPEAT = "profile_music_repeat", SHUFFLE = "profile_music_shuffle";
    private static final java.util.Random dice = new java.util.Random();

    static int repeat() { SharedPreferences p = prefs(); return p == null ? REPEAT_ALL : p.getInt(REPEAT, REPEAT_ALL); }
    static boolean shuffle() { SharedPreferences p = prefs(); return p != null && p.getBoolean(SHUFFLE, false); }

    /** Off → the whole list again and again → this track again and again → off. */
    static void cycleRepeat() {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putInt(REPEAT, (repeat() + 1) % 3).apply();
        changed();
    }

    static void toggleShuffle() {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putBoolean(SHUFFLE, !shuffle()).apply();
        changed();
    }

    /** The track after `at`: any other one when shuffled, else the next in the list (round to the first). */
    private static int following(int at) {
        int n = queue.size();
        if (shuffle() && n > 1) { int pick = dice.nextInt(n - 1); return pick >= at ? pick + 1 : pick; }
        return (at + 1) % n;
    }

    /** A track played to its end: the same once more, the next one, or -- the last of a list not repeated -- stop. */
    private static void ended() {
        int mode = repeat();
        if (mode == REPEAT_ONE) { start(); return; }
        if (mode == REPEAT_OFF && !shuffle() && index >= queue.size() - 1) {
            try { if (player != null) { player.pause(); player.seekTo(0); } } catch (Throwable ignored) { }
            paused = true;
            changed();
            return;
        }
        next();
    }

    private static int generation;

    private static void start() {
        final Track track = now();
        if (track == null) return;
        final int mine = ++generation;
        release();
        preparing = true;
        paused = false;
        changed();
        Net.away("profile music", () -> {
            // a fresh answer every time: TikTok's sound addresses are signed and run out
            final String[] sound = resolve(track.id);
            if (sound != null) resolved.put(track.id, sound);
            main.post(() -> {
                if (mine != generation) return;
                if (sound == null || TextUtils.isEmpty(sound[0])) { preparing = false; changed(); Screen.say(Text.t("Трек сейчас недоступен")); return; }
                try {
                    MediaPlayer media = new MediaPlayer();
                    media.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
                    media.setDataSource(sound[0]);
                    media.setOnPreparedListener(mp -> {
                        if (mine != generation) return;
                        preparing = false;
                        if (!focus(true)) { paused = true; changed(); return; }
                        startedAt = android.os.SystemClock.uptimeMillis();
                        PlayerTools.pauseFeed();
                        mp.start();
                        changed();
                    });
                    media.setOnCompletionListener(mp -> { if (mine == generation) ended(); });
                    media.setOnErrorListener((mp, what, extra) -> { if (mine == generation) { preparing = false; changed(); } return true; });
                    player = media;
                    media.prepareAsync();
                } catch (Throwable error) {
                    preparing = false;
                    Diary.note("profile music play: " + error);
                    changed();
                }
            });
        });
    }

    private static void release() {
        MediaPlayer old = player;
        player = null;
        if (old != null) try { old.release(); } catch (Throwable ignored) { }
    }

    private static long startedAt;

    /** A video of the feed started playing: the profile's music makes way for it (it can be played on from the sheet). */
    static void feedPlays() {
        if (player == null || paused || preparing) return;
        // TikTok reports its video once more right after the music started (the pause of the feed): not a new video
        if (android.os.SystemClock.uptimeMillis() - startedAt < 1500L) return;
        try { player.pause(); paused = true; } catch (Throwable ignored) { }
        changed();
    }

    /** TikTok went to the background: the music stops with it (nothing would be on screen to stop it). */
    static void background() {
        if (player == null || paused || preparing) return;
        try { player.pause(); paused = true; } catch (Throwable ignored) { }
        focus(false);
        changed();
    }

    private static Object focusRequest;

    /** Android's audio focus, as any music player asks it: other players pause; a call or another player pauses this one. */
    private static boolean focus(boolean take) {
        try {
            Context c = Ttcuz.context();
            android.media.AudioManager audio = c == null ? null : (android.media.AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null || android.os.Build.VERSION.SDK_INT < 26) return true;
            if (!take) {
                if (focusRequest != null) audio.abandonAudioFocusRequest((android.media.AudioFocusRequest) focusRequest);
                focusRequest = null;
                return true;
            }
            if (focusRequest == null) focusRequest = new android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(change -> main.post(() -> {
                        if (change < 0 && player != null && !paused) { try { player.pause(); paused = true; } catch (Throwable ignored) { } changed(); }
                    })).build();
            return audio.requestAudioFocus((android.media.AudioFocusRequest) focusRequest) != android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        } catch (Throwable error) { return true; }
    }

    static void stop() {
        generation++;
        release();
        focus(false);
        index = -1;
        preparing = paused = false;
        changed();
    }

    // ------------------------------------------------- covers

    private static final LruCache<String, Bitmap> covers = new LruCache<>(24);

    static void cover(final ImageView view, final String id) { cover(view, id, null); }

    /** The cover into a view; then {@code got} hears it (on the main thread), when there is one. */
    static void cover(final ImageView view, final String id, final java.util.function.Consumer<Bitmap> got_) {
        view.setTag(id);
        Bitmap known = covers.get(id);
        if (known != null) { view.setImageBitmap(known); if (got_ != null) got_.accept(known); return; }
        view.setImageDrawable(Symbols.drawable("music_note", 0x99FFFFFF));
        Net.away("profile music cover", () -> {
            // kept on disk too, so a cover seen once never downloads again after TikTok restarts
            Context app = Ttcuz.context();
            java.io.File dir = app == null ? null : new java.io.File(app.getCacheDir(), "ttcuz-covers");
            java.io.File file = dir == null ? null : new java.io.File(dir, id.replaceAll("[^A-Za-z0-9_-]", "_") + ".jpg");
            Bitmap picture = file != null && file.isFile() ? BitmapFactory.decodeFile(file.getPath()) : null;
            String[] sound = picture == null ? known(id) : null;
            if (picture == null && sound != null && !TextUtils.isEmpty(sound[1])) try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(sound[1]).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                try (java.io.InputStream in = c.getInputStream()) { picture = BitmapFactory.decodeStream(in); }
                if (picture != null && file != null && (dir.isDirectory() || dir.mkdirs()))
                    try (java.io.OutputStream out = new java.io.FileOutputStream(file)) { picture.compress(Bitmap.CompressFormat.JPEG, 88, out); }
            } catch (Throwable ignored) { }
            final Bitmap got = picture;
            if (got != null) covers.put(id, got);
            main.post(() -> { if (got != null && id.equals(view.getTag())) { view.setImageBitmap(got); if (got_ != null) got_.accept(got); } });
        });
    }

    // ------------------------------------------------- the island on a profile

    /** a profile's nickname → the island it keeps on screen (both the big name and the top bar's lead to one island) */
    private static final Map<View, Island> islands = new WeakHashMap<>();
    /** a window → the island of the profile opened in it last: a profile opened over another one hides that one's */
    private static final Map<View, Island> active = new WeakHashMap<>();
    /** a profile's name → the profile it shows */
    private static final Map<View, String> shownBy = new WeakHashMap<>();

    /**
     * A profile's nickname was written (Badge, the header's own name): its island is made, or brought up to date for
     * the profile it now shows. Every nickname of one profile in a window leads to the same island, which stays as long
     * as any of them is on screen -- the big name at the top and the one in the bar that appears on scrolling.
     */
    private static final int RETRIES = 0x7e0c03a1;

    static void attach(final View header, final String uid) { attach(header, uid, false); }

    /**
     * One's own name alone, from TikTok's saved account rather than the profile's own model: the first opening of one's
     * own profile draws the name before that model is asked for, and the island came only on the second. Taken only
     * once it is inside a profile header bound here (asked again for about twelve seconds) -- one's own name alone is
     * also under one's own videos in the feed.
     */
    static void attachOwn(final View name, final String uid) {
        if (shownBy.containsKey(name) || name.getTag(OWN_WAIT) != null) return;
        name.setTag(OWN_WAIT, 0);
        waitOwn(name, uid);
    }

    private static final int OWN_WAIT = 0x7e0c03a2;

    private static void waitOwn(final View name, final String uid) {
        Object tries = name.getTag(OWN_WAIT);
        int n = tries instanceof Integer ? (Integer) tries : 0;
        if (name.isAttachedToWindow() && ProfileLayout.within(name)) { name.setTag(OWN_WAIT, null); attach(name, uid); return; }
        if (n >= 80) { name.setTag(OWN_WAIT, null); return; }
        name.setTag(OWN_WAIT, n + 1);
        name.postDelayed(() -> waitOwn(name, uid), 150L);
    }

    private static void attach(final View header, final String uid, boolean retry) {
        try {
            // a new binding of the name (a profile opened again) waits afresh: the count of the last wait stayed on the
            // reused nickname view, and once spent the island never came on that view again
            if (!retry) header.setTag(RETRIES, null);
            if (uid == null || uid.isEmpty()) return;
            // a name that leaves its window and comes back (a tab switched away and back) gets its island again
            if (shownBy.put(header, uid) == null) header.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) { String again = shownBy.get(v); if (again != null) v.post(() -> attach(v, again)); }
                @Override public void onViewDetachedFromWindow(View v) { }
            });
            if (!header.isAttachedToWindow()) return;
            // a profile's own header only: the same nickname marks reach comments and other sheets, where the note was
            // put on whatever looked like a button there
            if (!ProfileLayout.holds(header)) {
                // the name can be bound well before the profile's header is (a slow network): asked again for about twelve seconds,
                // or the island came on one opening of a profile and not on the next
                Object tries = header.getTag(RETRIES);
                int n = tries instanceof Integer ? (Integer) tries : 0;
                if (n < 80) { header.setTag(RETRIES, n + 1); header.postDelayed(() -> attach(header, uid, true), 150L); }
                else if (uid.equals(Account.liveId())) Diary.note("profile music island: own name never in a profile header");
                return;
            }
            header.setTag(RETRIES, null);
            View root = header.getRootView();
            if (!(root instanceof FrameLayout)) { Diary.note("profile music island: the window is a " + root.getClass().getName()); return; }
            Island island = islands.get(header);
            // a nickname view TikTok reused for another profile
            if (island != null && !uid.equals(island.uid)) { island.names.remove(header); islands.remove(header); island = null; }
            if (island == null) {
                for (Island other : new ArrayList<>(islands.values()))
                    if (other.getParent() == root && uid.equals(other.uid)) { island = other; break; }
                if (island == null) {
                    final Island made = new Island(header.getContext());
                    made.uid = uid;
                    // laid over TikTok's own button by place(): its size and place are only known then
                    ((FrameLayout) root).addView(made, new FrameLayout.LayoutParams(dp(header, 44), dp(header, 44), Gravity.TOP | Gravity.LEFT));
                    ((FrameLayout) root).addView(made.hint, new FrameLayout.LayoutParams(dp(header, 30), dp(header, 30), Gravity.TOP | Gravity.LEFT));
                    // the window's own observer, so the listener can be taken off again once the names have gone
                    made.frame = () -> { place(made); return true; };
                    root.getViewTreeObserver().addOnPreDrawListener(made.frame);
                    island = made;
                    Diary.note("profile music island: made for " + (uid.equals(Account.liveId()) ? "own profile" : "a profile"));
                }
                islands.put(header, island);
                island.names.put(header, Boolean.TRUE);
            }
            active.put(root, island);
            island.show(uid);
            place(island);
        } catch (Throwable error) { Diary.note("profile music island: " + error); }
    }

    /** Whether a nickname's profile is on screen. The name itself and the row holding it do not count: the bar hides
     *  its own copy of the name until the page is scrolled, and the island is wanted all along. */
    private static boolean onScreen(View name) {
        if (name.getWindowVisibility() != View.VISIBLE) return false;
        // a profile paged aside (the author's page beside the feed, one's own tab behind it) is laid out off screen
        int[] at = new int[2];
        name.getLocationOnScreen(at);
        if (at[0] >= name.getRootView().getWidth() || at[0] + name.getWidth() <= 0) return false;
        android.view.ViewParent up = name.getParent();
        up = up == null ? null : up.getParent();
        while (up instanceof View) {
            if (((View) up).getVisibility() != View.VISIBLE) return false;
            up = up.getParent();
        }
        return true;
    }

    /**
     * In place of TikTok's own small square button beside "Сообщение" / "Подписаться" (the person with a plus, which
     * opens suggested accounts): the same background, a note in it, the playing bars while the music plays; TikTok's
     * button is kept, only unseen, under it. A small "!" under it tells what it is. Clipped with the row as the page
     * scrolls, gone with the profile.
     */
    private static void place(Island pill) {
        View anchor = null;
        boolean seen = false;
        for (View name : new ArrayList<>(pill.names.keySet())) {
            if (!name.isAttachedToWindow()) { pill.names.remove(name); islands.remove(name); continue; }
            if (anchor == null) anchor = name;
            if (onScreen(name)) { seen = true; anchor = name; }
        }
        if (anchor == null) { drop(pill); return; }
        View root = (View) pill.getParent();
        // the profile on screen takes the window's button back: one's own tab came back after the author's page beside
        // the feed was seen, and its name, bound long before, was not bound again -- the button stayed the author's
        pill.seenNow = seen;
        Island owner = root == null ? null : active.get(root);
        if (seen && root != null && owner != pill && (owner == null || !owner.seenNow || owner.getParent() != root)) active.put(root, pill);
        boolean wanted = seen && pill.wanted && root != null && active.get(root) == pill;
        // one's own profile: a note among the top bar's icons, as TikTok draws its own there
        if (pill.uid.equals(Account.liveId())) { inBar(pill, root, wanted); return; }
        // TikTok's button is looked for now and then, not on every frame
        View target = pill.target == null ? null : pill.target.get();
        if (wanted && (target == null || !target.isAttachedToWindow() || ++pill.frames % 30 == 0)) {
            View found = squareButton(root, anchor, pill);
            if (found != target) {
                pill.release();
                target = found;
                pill.take(found);
            }
        }
        Rect shown = new Rect();
        boolean visible = wanted && target != null && target.isShown() && target.getGlobalVisibleRect(shown);
        if (!visible) {
            pill.hide();
            if (!wanted) pill.release();
            return;
        }
        // TikTok's own button stays where it is, unseen, so the row keeps its shape
        if (target.getAlpha() != 0f) target.setAlpha(0f);
        int[] at = new int[2], origin = new int[2];
        target.getLocationOnScreen(at);
        root.getLocationOnScreen(origin);
        int w = target.getWidth(), h = target.getHeight();
        FrameLayout.LayoutParams size = (FrameLayout.LayoutParams) pill.getLayoutParams();
        if (size.width != w || size.height != h) { size.width = w; size.height = h; pill.setLayoutParams(size); }
        float x = at[0] - origin[0], y = at[1] - origin[1];
        if (pill.getTranslationX() != x) pill.setTranslationX(x);
        if (pill.getTranslationY() != y) pill.setTranslationY(y);
        // the part of the button the page shows: under the top bar while scrolling, only the rest of it
        shown.offset(-at[0], -at[1]);
        if (!shown.equals(pill.clip)) { pill.clip.set(shown); pill.setClipBounds(new Rect(shown)); }
        // the "!" just under it, centred
        View hint = pill.hint;
        int side = hint.getLayoutParams().width;
        float hx = x + (w - side) / 2f, hy = y + h + dp(pill, 2);
        if (hint.getTranslationX() != hx) hint.setTranslationX(hx);
        if (hint.getTranslationY() != hy) hint.setTranslationY(hy);
        boolean hintFits = shown.top <= 0 && shown.height() == h;
        hint.setVisibility(hintFits ? View.VISIBLE : View.INVISIBLE);
        // at once, in the same frame TikTok's button goes: a fade showed an empty place first
        if (pill.getVisibility() != View.VISIBLE) { pill.animate().cancel(); pill.setAlpha(1f); pill.setVisibility(View.VISIBLE); }
    }

    /**
     * TikTok's small button at the end of the profile's buttons: clickable, about as tall as it is wide, on the right,
     * below the nickname, level with a wide button to its left ("Сообщение", "Поделиться профилем").
     */
    /**
     * TikTok's own "add person" button (id foz) below the profile's name, or null. Only that button: a search by shape
     * took the music disc in the feed and buttons in the comments for it.
     */
    private static View squareButton(View root, View name, Island pill) {
        View button = ctaById(root);
        if (button == null) return null;
        int[] at = new int[2], nameAt = new int[2];
        button.getLocationOnScreen(at);
        name.getLocationOnScreen(nameAt);
        // the profile's buttons are a little below its name: well under a screen's third
        float unit = root.getResources().getDisplayMetrics().density;
        return at[1] >= nameAt[1] - 40 * unit && at[1] - nameAt[1] < 360 * unit ? button : null;
    }

    private static int ctaId;

    /** The shown "add person" button with the most of it on screen, or null; its circle when the circle is its parent's. */
    private static View ctaById(View root) {
        if (ctaId == 0) ctaId = root.getResources().getIdentifier("foz", "id", root.getContext().getPackageName());
        if (ctaId == 0) ctaId = -1;
        if (ctaId < 0) return null;
        List<View> found = new ArrayList<>();
        withId(root, ctaId, found);
        View best = null;
        int most = 0;
        Rect seen = new Rect();
        for (View v : found) {
            if (!v.isShown() || !v.getGlobalVisibleRect(seen)) continue;
            int area = seen.width() * seen.height();
            if (area > most) { most = area; best = v; }
        }
        if (best == null) return null;
        // the grey circle may be drawn by the button's holder rather than by the icon
        if (best.getBackground() == null && best.getParent() instanceof View) {
            View holder = (View) best.getParent();
            if (holder.getBackground() != null && holder.getWidth() <= best.getWidth() * 1.6f && holder.getHeight() <= best.getHeight() * 1.6f) return holder;
        }
        return best;
    }

    private static void withId(View view, int id, List<View> out) {
        if (view.getId() == id) out.add(view);
        if (view instanceof ViewGroup)
            for (int i = 0, n = ((ViewGroup) view).getChildCount(); i < n; i++) withId(((ViewGroup) view).getChildAt(i), id, out);
    }

    private static void clickables(View view, Island pill, List<View> out) {
        if (view == pill || view == pill.hint || view instanceof InfoDrop || view.getVisibility() != View.VISIBLE) return;
        if (view.isClickable() && view.getWidth() > 0) out.add(view);
        if (view instanceof ViewGroup)
            for (int i = 0, n = ((ViewGroup) view).getChildCount(); i < n; i++) clickables(((ViewGroup) view).getChildAt(i), pill, out);
    }

    /**
     * One's own profile: the note goes into the top bar, just left of TikTok's own icons on the right (the visits,
     * add friends, the menu), the same size as they are and bare like them; the playing bars while music plays.
     */
    private static void inBar(Island pill, View root, boolean wanted) {
        if (!wanted) { pill.hide(); return; }
        // TikTok's leftmost icon on the right is kept, and the note follows it on every frame: looking for it anew
        // while the header collapses made the note jump and blink
        View icon = pill.barIcon == null ? null : pill.barIcon.get();
        if (icon == null || !icon.isShown()) {
            int[] found = topIcons(root, pill);
            icon = pill.barIcon == null ? null : pill.barIcon.get();
            if (found == null || icon == null) { if (!pill.saidNoIcons) { pill.saidNoIcons = true; Diary.note("profile music island: no top bar icons on own profile"); } pill.hide(); return; }
            pill.room = found;
        }
        int[] spot = new int[2];
        icon.getLocationOnScreen(spot);
        int[] room = {spot[0], spot[1] + icon.getHeight() / 2, pill.room[2]};
        if (!(pill.getBackground() instanceof GradientDrawable)) {
            // a darkened circle the size of TikTok's own there, so the note is seen
            GradientDrawable round = new GradientDrawable();
            round.setShape(GradientDrawable.OVAL);
            round.setColor(0x66000000);
            pill.setBackground(round);
        }
        int size = room[2];
        FrameLayout.LayoutParams at = (FrameLayout.LayoutParams) pill.getLayoutParams();
        if (at.width != size || at.height != size) { at.width = size; at.height = size; pill.setLayoutParams(at); }
        int[] origin = new int[2];
        root.getLocationOnScreen(origin);
        float x = room[0] - origin[0] - size - dp(pill, 8), y = room[1] - origin[1] - size / 2f;
        if (pill.getTranslationX() != x) pill.setTranslationX(x);
        if (pill.getTranslationY() != y) pill.setTranslationY(y);
        if (pill.getClipBounds() != null) pill.setClipBounds(null);
        // the "!" just left of it, level with it
        View hint = pill.hint;
        int side = hint.getLayoutParams().width;
        float hx = x - side - dp(pill, 2), hy = y + (size - side) / 2f;
        if (hint.getTranslationX() != hx) hint.setTranslationX(hx);
        if (hint.getTranslationY() != hy) hint.setTranslationY(hy);
        if (hint.getVisibility() != View.VISIBLE) hint.setVisibility(View.VISIBLE);
        if (pill.getVisibility() != View.VISIBLE) { pill.animate().cancel(); pill.setAlpha(1f); pill.setScaleX(1f); pill.setScaleY(1f); pill.setVisibility(View.VISIBLE); }
    }

    /** The top bar's icons on the right, on screen: {left edge of the leftmost one, their middle, their size}. */
    private static int[] topIcons(View root, Island pill) {
        int top = 0;
        try {
            android.view.WindowInsets insets = root.getRootWindowInsets();
            if (insets != null) top = android.os.Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(android.view.WindowInsets.Type.statusBars()).top : insets.getSystemWindowInsetTop();
        } catch (Throwable ignored) { }
        int[] origin = new int[2], at = new int[2];
        root.getLocationOnScreen(origin);
        top += origin[1];
        List<View> all = new ArrayList<>();
        clickables(root, pill, all);
        int left = Integer.MAX_VALUE, middle = 0, size = 0, half = origin[0] + root.getWidth() / 2;
        View leftmost = null;
        for (View v : all) {
            if (!v.isShown() || v.getWidth() > dp(v, 150) || v.getHeight() < dp(v, 24) || v.getHeight() > dp(v, 60)) continue;
            v.getLocationOnScreen(at);
            if (at[1] < top - dp(v, 4) || at[1] + v.getHeight() > top + dp(v, 72) || at[0] + v.getWidth() / 2 < half) continue;
            if (at[0] < left) { left = at[0]; middle = at[1] + v.getHeight() / 2; leftmost = v; }
            size = Math.max(size, Math.min(v.getWidth(), v.getHeight()));
        }
        if (leftmost == null) return null;
        pill.barIcon = new WeakReference<>(leftmost);
        return new int[]{left, middle, Math.max(dp(pill, 36), Math.min(dp(pill, 44), size))};
    }

    /** The profile's names have all left the window: the button goes with them, and TikTok's is seen again. */
    private static void drop(Island pill) {
        for (View name : new ArrayList<>(pill.names.keySet())) islands.remove(name);
        pill.names.clear();
        for (Map.Entry<View, Island> e : new ArrayList<>(active.entrySet())) if (e.getValue() == pill) active.remove(e.getKey());
        pill.release();
        pill.setVisibility(View.GONE);
        View root = pill.getRootView();
        if (pill.frame != null && root.getViewTreeObserver().isAlive()) root.getViewTreeObserver().removeOnPreDrawListener(pill.frame);
        // taken out after the frame: a view may not leave its parent while the parent draws
        pill.post(() -> {
            for (View v : new View[]{pill, pill.hint}) if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        });
    }

    /** Buttons drawn again: the player changed, or someone's tracks came from the server. */
    static void refreshIslands() {
        main.post(() -> { for (Island island : new java.util.HashSet<>(islands.values())) island.refresh(); });
    }

    /** The profile's music button: TikTok's button's own background, the note in it, the bars while it plays. */
    private static final class Island extends FrameLayout {
        final ImageView note = new ImageView(getContext());
        final ProfileMusicViews.Bars bars;
        final View hint;
        final Map<View, Boolean> names = new WeakHashMap<>();
        final Rect clip = new Rect();
        String uid = "";
        boolean wanted, asked, seenNow, saidNoIcons;
        int frames;
        int[] room;
        WeakReference<View> target, barIcon;
        android.view.ViewTreeObserver.OnPreDrawListener frame;

        Island(Context c) {
            super(c);
            int ink = night(c) ? 0xFFFFFFFF : 0xFF161823;
            note.setImageDrawable(Symbols.drawable("music_note", ink));
            addView(note, new LayoutParams(dp(this, 22), dp(this, 22), Gravity.CENTER));
            bars = new ProfileMusicViews.Bars(c, Accent.colour());
            bars.setVisibility(GONE);
            addView(bars, new LayoutParams(dp(this, 18), dp(this, 16), Gravity.CENTER));
            setContentDescription(Text.t("Музыка профиля"));
            setVisibility(GONE);
            setOnClickListener(v -> { if (getContext() instanceof Activity) sheet((Activity) getContext(), uid); });
            Motion.press(this);
            hint = InfoDrop.button(c, Text.t("Музыка профиля"),
                    Text.t("Треки, которые человек поставил в ttcuz. Нажмите ♪, чтобы послушать. Видят все, у кого есть мод; свои треки добавляются там же."));
            hint.setPadding(dp(this, 6), dp(this, 6), dp(this, 6), dp(this, 6));
            hint.setVisibility(GONE);
        }

        /** TikTok's button: its background is worn here, and it stays under this one unseen. */
        void take(View button) {
            target = button == null ? null : new WeakReference<>(button);
            if (button == null) return;
            android.graphics.drawable.Drawable own = button.getBackground();
            android.graphics.drawable.Drawable.ConstantState copy = own == null ? null : own.getConstantState();
            if (copy != null) setBackground(copy.newDrawable(getResources()).mutate());
            else {
                GradientDrawable round = new GradientDrawable();
                round.setCornerRadius(dp(this, 999));
                round.setColor(night(getContext()) ? 0xFF2F2F2F : 0xFFF1F1F2);
                setBackground(round);
            }
            Diary.note("profile music button: over " + button.getClass().getName());
        }

        /** TikTok's button seen again. */
        void release() {
            View old = target == null ? null : target.get();
            if (old != null && old.getAlpha() == 0f) old.setAlpha(1f);
            target = null;
            hide();
        }

        void hide() {
            if (getVisibility() != GONE) setVisibility(GONE);
            if (hint.getVisibility() != GONE) hint.setVisibility(GONE);
        }

        void show(String uid) {
            this.uid = uid;
            // in one's own top bar the note is drawn as TikTok's icons there are: white, a little larger
            if (uid.equals(Account.liveId())) {
                note.setImageDrawable(Symbols.drawable("music_note", 0xFFFFFFFF));
                note.setLayoutParams(new LayoutParams(dp(this, 25), dp(this, 25), Gravity.CENTER));
            }
            // someone else's tracks come with their profile from the server (asked once; Badge asks for it too)
            if (!asked && !uid.equals(Account.liveId())) { asked = true; TtcuzProfileSync.request(uid); }
            refresh();
        }

        void refresh() {
            List<Track> list = of(uid);
            // on every profile: one's own to add music, anyone else's to hear theirs (the player says when there is none)
            wanted = true;
            boolean on = isFrom(uid) && playing();
            bars.set(on);
            boolean moving = Motion.enabled(getContext()) && isShown();
            View in = on ? bars : note, out = on ? note : bars;
            if (in.getVisibility() != VISIBLE) {
                in.setVisibility(VISIBLE);
                out.setVisibility(GONE);
                if (moving) {
                    in.setScaleX(.4f); in.setScaleY(.4f); in.setAlpha(0f);
                    in.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(Motion.time(260L))
                            .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
                }
            }
        }
    }

    private static boolean night(Context c) {
        return (c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** Rounded corners for a cover, `radius` in dp. */
    static void round(ImageView view, final int radius) {
        view.setClipToOutline(true);
        view.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(v, radius)); }
        });
    }

    // ------------------------------------------------- the player

    /**
     * "Моя музыка" / "Треки @nick": the player first -- a large cover that leans back while paused, the title and the
     * artist, the seek line with its times, previous / play / next -- and the list under it; one's own also adds and
     * removes tracks.
     */
    static void sheet(final Activity activity, final String uid) {
        final boolean own = uid.equals(Account.liveId());
        final Skin skin = Skin.remembered(activity);
        String handle = own ? Account.profileHandle() : null;
        if (!own) { TtcuzProfileSync.Metadata m = TtcuzProfileSync.cached(uid); handle = m == null ? null : m.displayName; }
        final Panel panel = Panel.with(activity, skin, own ? Text.t("Моя музыка")
                : Text.t("Треки") + (TextUtils.isEmpty(handle) ? "" : " " + handle));
        final int accent = Accent.colour();
        final LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);

        // the player
        // the play button's row and its holder, known once they are made below
        final View[] folding = new View[2];
        final LinearLayout hero = new LinearLayout(activity) {
            boolean pressing;
            // folded into the bar, the rest of the player is cut away but still laid out under the list: a touch
            // there belongs to the list, not to the hidden controls
            @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
                android.graphics.Rect shown = getClipBounds();
                int action = e.getActionMasked();
                if (shown != null && action == android.view.MotionEvent.ACTION_DOWN && e.getY() > shown.bottom) return false;
                // the play button moved up into the bar is still a child of its row down below, and a touch is only
                // handed to a child inside its parent's own bounds: the bar hands it the tap itself
                View row = folding[0], button = folding[1];
                if (row != null && button != null && button.getScaleX() < .99f) {
                    float half = button.getWidth() * button.getScaleX() / 2f + dp(this, 6);
                    float cx = row.getLeft() + button.getLeft() + button.getWidth() / 2f + button.getTranslationX();
                    float cy = row.getTop() + button.getTop() + button.getHeight() / 2f + button.getTranslationY();
                    boolean in = Math.abs(e.getX() - cx) <= half && Math.abs(e.getY() - cy) <= half;
                    if (action == android.view.MotionEvent.ACTION_DOWN && in) { pressing = true; return true; }
                    if (pressing) {
                        if (action == android.view.MotionEvent.ACTION_UP) { pressing = false; if (in) ((ViewGroup) button).getChildAt(0).performClick(); }
                        else if (action == android.view.MotionEvent.ACTION_CANCEL) pressing = false;
                        return true;
                    }
                }
                return super.dispatchTouchEvent(e);
            }
        };
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setClipChildren(false);
        hero.setOutlineProvider(null);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(0, dp(box, 4), 0, dp(box, 6));
        final ImageView art = new ImageView(activity);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        round(art, 22);
        art.setElevation(dp(box, 10));
        art.setBackgroundColor(Expressive.mix(skin.card, skin.text, .08f));
        int side = Math.min(dp(box, 216), Math.round(activity.getResources().getDisplayMetrics().widthPixels * .56f));
        LinearLayout.LayoutParams artAt = new LinearLayout.LayoutParams(side, side);
        artAt.topMargin = dp(box, 8);
        artAt.bottomMargin = dp(box, 18);
        // the cover sits in a holder: the holder folds it into the bar, the cover itself leans back while paused
        final FrameLayout artHolder = new FrameLayout(activity);
        artHolder.setClipChildren(false);
        artHolder.addView(art, new FrameLayout.LayoutParams(side, side));
        hero.addView(artHolder, artAt);
        final TextView name = new TextView(activity), by = new TextView(activity);
        name.setTextColor(skin.text);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        by.setTextColor(skin.muted());
        by.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        by.setGravity(Gravity.CENTER);
        by.setSingleLine(true);
        by.setEllipsize(TextUtils.TruncateAt.END);
        by.setPadding(0, dp(box, 2), 0, 0);
        // the title, and on its right the heart: the playing track into one's favourite sounds in TikTok
        FrameLayout titleLine = new FrameLayout(activity);
        name.setPadding(dp(box, 44), 0, dp(box, 44), 0);
        titleLine.addView(name, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        final ImageView love = control(activity, "favorite", skin.muted());
        love.setContentDescription(Text.t("В избранные звуки"));
        titleLine.addView(love, new FrameLayout.LayoutParams(dp(box, 40), dp(box, 40), Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        hero.addView(titleLine, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        hero.addView(by, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final ProfileMusicViews.SeekLine seek = new ProfileMusicViews.SeekLine(activity, accent, skin.text);
        LinearLayout.LayoutParams seekAt = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(box, 30));
        seekAt.topMargin = dp(box, 14);
        hero.addView(seek, seekAt);
        LinearLayout times = new LinearLayout(activity);
        times.setPadding(dp(box, 8), 0, dp(box, 8), 0);
        final TextView from = new TextView(activity), length = new TextView(activity);
        for (TextView t : new TextView[]{from, length}) {
            t.setTextColor(skin.muted());
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            t.setFontFeatureSettings("tnum");
        }
        times.addView(from, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(length);
        hero.addView(times, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout controls = new LinearLayout(activity);
        controls.setGravity(Gravity.CENTER);
        final ImageView back = control(activity, "skip_previous_fill1", skin.text), forward = control(activity, "skip_next_fill1", skin.text);
        back.setContentDescription(Text.t("Предыдущий"));
        forward.setContentDescription(Text.t("Следующий"));
        final ProfileMusicViews.PlayButton toggle = new ProfileMusicViews.PlayButton(activity, accent, ink(accent));
        Motion.press(toggle);
        LinearLayout.LayoutParams side1 = new LinearLayout.LayoutParams(dp(box, 52), dp(box, 52));
        LinearLayout.LayoutParams middle = new LinearLayout.LayoutParams(dp(box, 68), dp(box, 68));
        middle.leftMargin = middle.rightMargin = dp(box, 14);
        final ImageView mix = control(activity, "shuffle", skin.muted()), loop = control(activity, "repeat", skin.muted());
        for (ImageView v : new ImageView[]{mix, loop}) {
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(dp(box, 44), dp(box, 44));
            v.setPadding(dp(box, 11), dp(box, 11), dp(box, 11), dp(box, 11));
            v.setLayoutParams(at);
        }
        ((LinearLayout.LayoutParams) mix.getLayoutParams()).rightMargin = dp(box, 6);
        ((LinearLayout.LayoutParams) loop.getLayoutParams()).leftMargin = dp(box, 6);
        mix.setOnClickListener(v -> { toggleShuffle(); pop(v); Screen.say(shuffle() ? Text.t("Вперемешку") : Text.t("По порядку")); });
        loop.setOnClickListener(v -> {
            cycleRepeat();
            pop(v);
            int mode = repeat();
            Screen.say(mode == REPEAT_ONE ? Text.t("Повтор трека") : mode == REPEAT_ALL ? Text.t("Повтор списка") : Text.t("Без повтора"));
        });
        back.setLayoutParams(side1);
        forward.setLayoutParams(new LinearLayout.LayoutParams(dp(box, 52), dp(box, 52)));
        back.setPadding(dp(box, 11), dp(box, 11), dp(box, 11), dp(box, 11));
        forward.setPadding(dp(box, 11), dp(box, 11), dp(box, 11), dp(box, 11));
        controls.addView(mix);
        controls.addView(back);
        // in a holder too: a press springs the button's own scale back to 1, the fold scales the holder
        final FrameLayout toggleHolder = new FrameLayout(activity);
        toggleHolder.addView(toggle, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        controls.addView(toggleHolder, middle);
        folding[0] = controls; folding[1] = toggleHolder;
        controls.addView(forward);
        controls.addView(loop);
        LinearLayout.LayoutParams controlsAt = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        controlsAt.topMargin = dp(box, 6);
        hero.addView(controls, controlsAt);
        box.addView(hero);

        // the list
        final TextView heading = new TextView(activity);
        heading.setTextColor(skin.muted());
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setPadding(dp(box, 4), dp(box, 18), 0, dp(box, 4));
        box.addView(heading);
        final LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        box.addView(rows);

        seek.onMoved((ms, done) -> {
            from.setText(time(ms));
            if (done && isFrom(uid)) seek(ms);
        });
        back.setOnClickListener(v -> { if (isFrom(uid)) previous(); else { List<Track> list = of(uid); if (!list.isEmpty()) play(uid, list, list.size() - 1); } });
        forward.setOnClickListener(v -> { if (isFrom(uid)) next(); else { List<Track> list = of(uid); if (!list.isEmpty()) play(uid, list, Math.min(1, list.size() - 1)); } });
        toggle.setOnClickListener(v -> { List<Track> list = of(uid); if (!list.isEmpty()) toggle(uid, list); });

        final List<Runnable> rowPaint = new ArrayList<>();
        final Runnable fill = new Runnable() {
            @Override public void run() {
                rows.removeAllViews();
                rowPaint.clear();
                final List<Track> list = of(uid);
                hero.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
                heading.setText(list.isEmpty() ? "" : Text.t("Треки") + " · " + list.size());
                heading.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
                if (list.isEmpty()) {
                    TextView empty = new TextView(activity);
                    empty.setText(own ? Text.t("Добавьте треки — их услышат все, кто откроет ваш профиль с модом") : Text.t("Здесь пока нет треков"));
                    empty.setTextColor(skin.muted());
                    empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                    empty.setGravity(Gravity.CENTER);
                    empty.setPadding(dp(box, 24), dp(box, 36), dp(box, 24), dp(box, 20));
                    rows.addView(empty);
                }
                for (int i = 0; i < list.size(); i++) rows.addView(trackRow(activity, skin, uid, own, list, i, rowPaint, this));
                if (own && list.size() < MAX) rows.addView(addRow(activity, skin, rows, this));
            }
        };
        final String[] painted = {""};
        final Runnable paint = () -> {
            List<Track> list = of(uid);
            Track t = isFrom(uid) ? now() : null;
            if (t == null && !list.isEmpty()) t = list.get(0);
            boolean mine = isFrom(uid), on = mine && playing();
            boolean moving = Motion.enabled(activity) && box.isShown();
            if (t != null && !t.id.equals(painted[0])) {
                boolean first = painted[0].isEmpty();
                painted[0] = t.id;
                final Track show = t;
                Runnable swap = () -> {
                    cover(art, show.id, picture -> tint(box, seek, skin, picture));
                    name.setText(TextUtils.isEmpty(show.title) ? Text.t("Трек") : show.title);
                    by.setText(show.artist);
                };
                if (moving && !first) {
                    // the next track slides in over the last one
                    for (View v : new View[]{art, name, by}) v.animate().cancel();
                    art.animate().alpha(0f).translationX(-dp(box, 16)).setDuration(Motion.time(120L)).withEndAction(() -> {
                        swap.run();
                        art.setTranslationX(dp(box, 16));
                        art.animate().alpha(1f).translationX(0f).setDuration(Motion.time(220L)).setInterpolator(Motion.OUT).start();
                    }).start();
                    for (View v : new View[]{name, by})
                        v.animate().alpha(0f).setDuration(Motion.time(120L)).withEndAction(() -> v.animate().alpha(1f).setDuration(Motion.time(200L)).start()).start();
                } else swap.run();
            }
            toggle.set(on, mine && preparing(), moving);
            float scale = on || (mine && preparing()) ? 1f : .9f;
            if (moving) art.animate().scaleX(scale).scaleY(scale).setDuration(Motion.time(360L)).setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).start();
            else { art.setScaleX(scale); art.setScaleY(scale); }
            back.setAlpha(list.size() > 1 ? 1f : .35f);
            forward.setAlpha(list.size() > 1 ? 1f : .35f);
            int lit = Expressive.mix(Accent.colour(), skin.text, .15f), mode = repeat();
            mix.setImageDrawable(Symbols.drawable("shuffle", shuffle() ? lit : skin.muted()));
            loop.setImageDrawable(Symbols.drawable(mode == REPEAT_ONE ? "repeat_one" : "repeat", mode == REPEAT_OFF ? skin.muted() : lit));
            mix.setContentDescription(shuffle() ? Text.t("Вперемешку") : Text.t("По порядку"));
            loop.setContentDescription(mode == REPEAT_ONE ? Text.t("Повтор трека") : mode == REPEAT_ALL ? Text.t("Повтор списка") : Text.t("Без повтора"));
            if (t != null) {
                final String id = t.id;
                boolean kept = saved.contains(id);
                love.setImageDrawable(Symbols.drawable(kept ? "favorite_fill1" : "favorite", kept ? Accent.colour() : skin.muted()));
                love.setOnClickListener(v -> favourite(id, love, skin));
            }
            for (Runnable r : rowPaint) r.run();
        };
        final int[] count = {of(uid).size()};
        final Runnable onChange = () -> {
            int size = of(uid).size();
            if (size != count[0]) { count[0] = size; fill.run(); }
            paint.run();
        };
        final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!box.isAttachedToWindow()) return;
                boolean mine = isFrom(uid);
                int d = mine ? duration() : 0, p = mine ? position() : 0;
                if (!seek.dragging()) { seek.set(p, d, mine && preparing(), mine && playing()); from.setText(time(p)); }
                length.setText(d > 0 ? time(d) : "–:––");
                main.postDelayed(this, mine && playing() ? 250L : 600L);
            }
        };
        fill.run();
        paint.run();
        listen(onChange);
        final Runnable fold = () -> fold(hero, artHolder, titleLine, name, by, love, controls, toggleHolder,
                new View[]{seek, times, mix, back, forward, loop}, skin.card);
        final android.view.ViewTreeObserver.OnScrollChangedListener scrolled = fold::run;
        hero.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fold.run());
        box.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) { main.post(tick); v.getViewTreeObserver().addOnScrollChangedListener(scrolled); }
            public void onViewDetachedFromWindow(View v) { unlisten(onChange); main.removeCallbacks(tick); v.getViewTreeObserver().removeOnScrollChangedListener(scrolled); }
        });
        panel.view(box).show();
    }

    /**
     * Scrolled down the list, the player folds into a bar pinned at the top of the sheet: the cover shrinks into its
     * left end, the title and the artist slide to its right and get smaller, the play button moves to the right end,
     * the rest fades. All of it follows the scroll itself -- the part of the player still shown is exactly what the
     * list has not yet reached -- so it is as smooth as the scroll and stops wherever the finger does.
     */
    private static void fold(View hero, View art, View titleLine, TextView name, TextView by, View love, View controls,
                             View toggle, View[] fading, int background) {
        if (hero.getVisibility() != View.VISIBLE || hero.getHeight() == 0) return;
        View scroll = hero;
        int top = 0;
        while (scroll.getParent() instanceof View && !(scroll.getParent() instanceof android.widget.ScrollView)) { top += scroll.getTop(); scroll = (View) scroll.getParent(); }
        if (!(scroll.getParent() instanceof android.widget.ScrollView)) return;
        int bar = dp(hero, 64), height = hero.getHeight(), width = hero.getWidth();
        View list = (View) scroll.getParent();
        int d = Math.max(0, list.getScrollY() - top);
        // a short list (two or three tracks) cannot be scrolled far enough to fold the player all the way: it stopped
        // half folded. The player folds only when it can fold through; otherwise it just scrolls as it is
        if (scroll.getHeight() - list.getHeight() - top < height - bar) d = 0;
        float p = Math.min(1f, d / (float) Math.max(1, height - bar));
        hero.setTranslationY(d);
        hero.setTranslationZ(d > 0 ? 1f : 0f);   // drawn over the list that scrolls under the bar
        hero.setClipBounds(d == 0 ? null : new android.graphics.Rect(0, 0, width, Math.max(bar, height - d)));
        float under = Math.max(0f, Math.min(1f, (p - .75f) / .25f));
        hero.setBackgroundColor(under == 0f ? 0 : (background & 0xFFFFFF) | (Math.round(255 * under) << 24));

        int small = dp(hero, 48), edge = dp(hero, 4);
        float s = 1f + (small / (float) Math.max(1, art.getWidth()) - 1f) * p;
        art.setPivotX(0f); art.setPivotY(0f);
        art.setScaleX(s); art.setScaleY(s);
        art.setTranslationX(p * (edge - art.getLeft()));
        art.setTranslationY(p * ((bar - small) / 2f - art.getTop()));

        float textLeft = edge + small + dp(hero, 12), t = 1f - .2f * p;
        slide(name, titleLine.getLeft() + name.getLeft(), titleLine.getTop() + name.getTop(), textLeft, dp(hero, 12), t, p);
        slide(by, by.getLeft(), by.getTop(), textLeft, dp(hero, 34), t, p);

        float gone = 1f - Math.min(1f, p * 2f);
        love.setAlpha(gone);
        for (View v : fading) v.setAlpha(gone);
        float button = 1f + (dp(hero, 44) / (float) Math.max(1, toggle.getWidth()) - 1f) * p;
        toggle.setScaleX(button); toggle.setScaleY(button);
        toggle.setTranslationX(p * (width - edge - dp(hero, 22) - (controls.getLeft() + toggle.getLeft() + toggle.getWidth() / 2f)));
        toggle.setTranslationY(p * (bar / 2f - (controls.getTop() + toggle.getTop() + toggle.getHeight() / 2f)));
    }

    /** A centred line of text moved, as `p` goes to 1, to start at `x`, its top at `y`, `scale` its size. */
    private static void slide(TextView text, int left, int top, float x, float y, float scale, float p) {
        android.text.Layout layout = text.getLayout();
        float wide = layout == null ? 0f : layout.getLineWidth(0);
        int room = text.getWidth() - text.getPaddingLeft() - text.getPaddingRight();
        float start = text.getPaddingLeft() + Math.max(0f, (room - wide) / 2f);
        text.setPivotX(start); text.setPivotY(0f);
        text.setScaleX(scale); text.setScaleY(scale);
        text.setTranslationX(p * (x - left - start));
        text.setTranslationY(p * (y - top - text.getPaddingTop()));
    }

    /** One track of the list: cover (the playing bars over it while it plays), title, artist, ♡, and ✕ on one's own. */
    /**
     * The player's sheet takes the cover's colours: its card a gradient from the cover's top colour, through its bottom
     * one, into the sheet's own colour (so the list below stays readable), and the seek line the cover's liveliest colour.
     * The colours flow from the last cover's.
     */
    private static void tint(View box, ProfileMusicViews.SeekLine seek, Skin skin, Bitmap picture) {
        // the first cover can come before the sheet is up: it is tinted once the sheet is
        if (!box.isAttachedToWindow()) { box.post(() -> { if (box.isAttachedToWindow()) tint(box, seek, skin, picture); }); return; }
        if (!(box.getParent() instanceof View) || !(((View) box.getParent()).getBackground() instanceof GradientDrawable)) return;
        final GradientDrawable card = (GradientDrawable) ((View) box.getParent()).getBackground().mutate();
        int[] from = card.getColors() != null && card.getColors().length == 3 ? card.getColors() : new int[]{skin.card, skin.card, skin.card};
        int[] cover = coverColours(picture);
        boolean dark = Color.luminance(skin.card) < .5f;
        // little enough of the cover that the sheet's own text stays readable on it
        final int[] to = {Expressive.mix(skin.card, cover[0], dark ? .55f : .32f), Expressive.mix(skin.card, cover[1], dark ? .30f : .16f), skin.card};
        card.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        ValueAnimator flow = ValueAnimator.ofFloat(0f, 1f);
        flow.setDuration(Motion.time(600L));
        flow.setInterpolator(Motion.OUT);
        android.animation.ArgbEvaluator mix = new android.animation.ArgbEvaluator();
        flow.addUpdateListener(a -> {
            float f = (Float) a.getAnimatedValue();
            card.setColors(new int[]{(Integer) mix.evaluate(f, from[0], to[0]), (Integer) mix.evaluate(f, from[1], to[1]), (Integer) mix.evaluate(f, from[2], to[2])});
        });
        flow.start();
        seek.colour(cover[2]);
    }

    /** {the top half's colour, the bottom half's, the liveliest} of a cover, read from an 8×8 copy of it. */
    static int[] coverColours(Bitmap picture) {
        Bitmap small = Bitmap.createScaledBitmap(picture, 8, 8, true);
        long[][] sum = new long[2][3];
        float best = -1f;
        int lively = Accent.colour();
        float[] hsv = new float[3];
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            int c = small.getPixel(x, y), half = y < 4 ? 0 : 1;
            sum[half][0] += Color.red(c); sum[half][1] += Color.green(c); sum[half][2] += Color.blue(c);
            Color.colorToHSV(c, hsv);
            float score = hsv[1] * Math.min(hsv[2], 1f - Math.abs(hsv[2] - .7f));
            if (score > best) { best = score; lively = c; }
        }
        if (small != picture) small.recycle();
        int[] out = new int[3];
        for (int half = 0; half < 2; half++) {
            int c = Color.rgb((int) (sum[half][0] / 32), (int) (sum[half][1] / 32), (int) (sum[half][2] / 32));
            // a little more colour than the average has: averages come out grey
            Color.colorToHSV(c, hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.35f);
            out[half] = Color.HSVToColor(hsv);
        }
        // the liveliest colour, bright enough to show on the line
        Color.colorToHSV(lively, hsv);
        hsv[2] = Math.max(hsv[2], .75f);
        hsv[1] = Math.max(hsv[1], best < .08f ? 0f : .45f);
        out[2] = best < .08f ? Accent.colour() : Color.HSVToColor(hsv);
        return out;
    }

    private static View trackRow(final Activity activity, final Skin skin, final String uid, boolean own, final List<Track> list,
                                 final int at, List<Runnable> rowPaint, final Runnable refill) {
        final Track t = list.get(at);
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(row, 4), dp(row, 7), dp(row, 0), dp(row, 7));
        FrameLayout art = new FrameLayout(activity);
        ImageView picture = new ImageView(activity);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        round(picture, 10);
        cover(picture, t.id);
        art.addView(picture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final View shade = new View(activity);
        GradientDrawable dark = new GradientDrawable();
        dark.setCornerRadius(dp(row, 10));
        dark.setColor(0x80000000);
        shade.setBackground(dark);
        art.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final ProfileMusicViews.Bars bars = new ProfileMusicViews.Bars(activity, 0xFFFFFFFF);
        art.addView(bars, new FrameLayout.LayoutParams(dp(row, 18), dp(row, 16), Gravity.CENTER));
        row.addView(art, new LinearLayout.LayoutParams(dp(row, 48), dp(row, 48)));
        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(row, 14), 0, dp(row, 8), 0);
        final TextView a = new TextView(activity), b = new TextView(activity);
        a.setText(TextUtils.isEmpty(t.title) ? Text.t("Трек") : t.title);
        a.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        a.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        a.setSingleLine(true);
        a.setEllipsize(TextUtils.TruncateAt.END);
        b.setText(t.artist);
        b.setTextColor(skin.muted());
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(a);
        text.addView(b);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // the sound into one's own favourite sounds in TikTok, as TikTok's own heart on a sound does
        final ImageView heart = control(activity, saved.contains(t.id) ? "favorite_fill1" : "favorite", saved.contains(t.id) ? Accent.colour() : skin.muted());
        heart.setContentDescription(Text.t("В избранные звуки"));
        heart.setOnClickListener(v -> favourite(t.id, heart, skin));
        row.addView(heart);
        if (own) {
            ImageView remove = control(activity, "close", skin.muted());
            remove.setContentDescription(Text.t("Убрать трек"));
            remove.setOnClickListener(v -> {
                if (isFrom(uid) && now() != null && now().id.equals(t.id)) stop();
                remove(t.id);
                refill.run();
            });
            row.addView(remove);
        }
        rowPaint.add(() -> {
            boolean current = isFrom(uid) && now() != null && now().id.equals(t.id);
            bars.set(current && playing());
            bars.setVisibility(current ? View.VISIBLE : View.GONE);
            shade.setVisibility(current ? View.VISIBLE : View.GONE);
            a.setTextColor(current ? Expressive.mix(Accent.colour(), skin.text, .2f) : skin.text);
        });
        row.setOnClickListener(v -> {
            if (isFrom(uid) && now() != null && now().id.equals(t.id)) toggle(uid, list);
            else play(uid, list, at);
        });
        Motion.press(row);
        return row;
    }

    private static View addRow(final Activity activity, final Skin skin, View near, final Runnable refill) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(near, 4), dp(near, 8), dp(near, 4), dp(near, 8));
        ImageView plus = new ImageView(activity);
        plus.setImageDrawable(Symbols.drawable("add", Accent.colour()));
        plus.setPadding(dp(near, 13), dp(near, 13), dp(near, 13), dp(near, 13));
        GradientDrawable round = new GradientDrawable();
        round.setCornerRadius(dp(near, 10));
        round.setColor((Accent.colour() & 0xFFFFFF) | 0x26000000);
        plus.setBackground(round);
        row.addView(plus, new LinearLayout.LayoutParams(dp(near, 48), dp(near, 48)));
        TextView label = new TextView(activity);
        label.setText(Text.t("Добавить трек"));
        label.setTextColor(Expressive.mix(Accent.colour(), skin.text, .2f));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setPadding(dp(near, 14), 0, 0, 0);
        row.addView(label);
        row.setOnClickListener(v -> ProfileMusicPicker.open(activity, skin, refill));
        Motion.press(row);
        return row;
    }

    // ------------------------------------------------- TikTok's own favourite sounds

    /** Sounds put into the favourites from here this run: their hearts stay full. */
    private static final java.util.Set<String> saved = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    static Object chooseApi() throws Exception {
        return Class.forName("com.ss.android.ugc.aweme.choosemusic.api.ChooseMusicApi").getField("LIZ").get(null);
    }

    /** A TikTok task (X/0172) waited for, up to 20 s: its result, or an exception. Off the main thread. */
    static Object await(Object task) throws Exception {
        Class<?> type = Class.forName("X.0172");
        java.lang.reflect.Method completed = type.getMethod("LJIILIIL"), wait = type.getMethod("LJIJJ", long.class, java.util.concurrent.TimeUnit.class);
        long until = android.os.SystemClock.elapsedRealtime() + 20000;
        while (!(Boolean) completed.invoke(task)) {
            long left = until - android.os.SystemClock.elapsedRealtime();
            if (left <= 0) throw new java.io.IOException("timeout");
            wait.invoke(task, left, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        Object error = type.getMethod("LJIIJ").invoke(task);
        if (error != null) throw new java.io.IOException(String.valueOf(error));
        return type.getMethod("LJIIJJI").invoke(task);
    }

    /** ♡: TikTok's /aweme/v1/music/collect/ with action 1 (ChooseMusicApi.API.LJFF(music_id, music_ids, action)). */
    private static void favourite(final String id, final ImageView heart, final Skin skin) {
        if (saved.contains(id)) return;
        heart.setImageDrawable(Symbols.drawable("favorite_fill1", Accent.colour()));
        if (Motion.enabled(heart.getContext())) {
            heart.setScaleX(.6f); heart.setScaleY(.6f);
            heart.animate().scaleX(1f).scaleY(1f).setDuration(Motion.time(320L)).setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start();
        }
        Net.away("profile music favourite", () -> {
            boolean ok = false;
            try {
                Object api = chooseApi();
                Object answer = await(api.getClass().getMethod("LJFF", String.class, String.class, int.class).invoke(api, id, "", 1));
                Object code = NativeRead.field(answer, "status_code");
                ok = code == null || NativeRead.number(code) == 0;
            } catch (Throwable error) { Diary.note("profile music favourite: " + error); }
            final boolean done = ok;
            main.post(() -> {
                if (done) { saved.add(id); Screen.say(Text.t("Добавлено в избранные звуки")); }
                else { heart.setImageDrawable(Symbols.drawable("favorite", skin.muted())); Screen.say(Text.t("Не удалось добавить в избранное")); }
            });
        });
    }

    /** One's favourite sounds in TikTok (/aweme/v1/user/music/collect/, the first 50). Off the main thread. */
    static List<Track> favourites() throws Exception {
        Object api = chooseApi();
        Object list = await(api.getClass().getMethod("LIZLLL", int.class, int.class, String.class, int.class, String.class, String.class,
                String.class, Boolean.class, String.class, int.class).invoke(api, 0, 50, "", 0, "", "", "", Boolean.FALSE, "", 0));
        Object items = NativeRead.get(list, "getItems");
        List<Track> out = new ArrayList<>();
        if (items instanceof List) for (Object music : (List<?>) items) {
            Track t = trackOf(music);
            if (t != null) out.add(t);
        }
        return out;
    }

    /** A toggle pressed: it shrinks and springs back. */
    private static void pop(View v) {
        if (!Motion.enabled(v.getContext())) return;
        v.setScaleX(.7f);
        v.setScaleY(.7f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(Motion.time(300L)).setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start();
    }

    private static ImageView control(Context c, String symbol, int colour) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(Symbols.drawable(symbol, colour));
        int pad = Math.round(8 * c.getResources().getDisplayMetrics().density);
        v.setPadding(pad, pad, pad, pad);
        int size = Math.round(40 * c.getResources().getDisplayMetrics().density);
        v.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        Motion.press(v);
        return v;
    }

    static String time(int ms) {
        int s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    /** Black or white, whichever reads on `colour`. */
    private static int ink(int colour) {
        float light = (.299f * android.graphics.Color.red(colour) + .587f * android.graphics.Color.green(colour)
                + .114f * android.graphics.Color.blue(colour)) / 255f;
        return light > .62f ? 0xFF111111 : 0xFFFFFFFF;
    }

    private static int dp(View v, int value) { return Math.round(value * v.getResources().getDisplayMetrics().density); }
}
