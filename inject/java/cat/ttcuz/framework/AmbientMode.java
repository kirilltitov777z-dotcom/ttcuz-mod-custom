package cat.ttcuz.framework;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Ambient mode, as YouTube's: a video (or a slideshow's photo) that does not fill the screen has the black around it
 * lit by the picture itself -- a tiny copy of the current frame, stretched past the picture so its own colours and
 * shapes carry on into the bands above, below and beside it, fading into black toward the screen's edges.
 *
 * That is how YouTube does it: the frame drawn onto a very small canvas, scaled up and blurred, behind the player.
 * Here the frame is copied at 16 x 16 and scaled up with bilinear filtering -- a frame that small, stretched across
 * the screen, is already as soft as a heavy blur -- and drawn only where the picture is not. Each of its 256 colours
 * eases toward the newest copy on every frame drawn, so the glow flows with the video and never jumps.
 *
 * Where the picture is: a TextureView's or a photo's copy is the whole view, its black bars included, and they are
 * cut off; TikTok 47.7.3's SurfaceView copy holds the video alone, so its place is the video's size fitted into the view.
 *
 * Cheap: a copy twice a second (less while the picture stands still), 256 colours eased while they move, nothing at
 * all when the picture fills the screen, when TikTok is in the background, or with the switch off.
 */
final class AmbientMode {
    private AmbientMode() {}

    static final String KEY = "ambient_mode";
    private static Boolean on;
    private static final long EVERY = 500L, EASE = 600L;
    private static final int COPY = 32, TILE = 16;
    private static final String[] SCREENS = {"com.ss.android.ugc.aweme.main.MainActivity", "com.ss.android.ugc.aweme.detail.ui.DetailActivity"};

    /** Switched off for now, kept for later: no watching, no switch in the settings, whatever was saved. */
    static final boolean READY = false;

    static boolean isEnabled() {
        if (!READY) return false;
        if (on != null) return on;
        Context c = Ttcuz.context();
        return on = c == null || c.getSharedPreferences(Ttcuz.PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true);
    }

    static void setEnabled(boolean value) {
        on = value;
        Context c = Ttcuz.context();
        if (c != null) c.getSharedPreferences(Ttcuz.PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply();
        if (!value) for (Glow glow : glows.values()) if (glow != null) glow.hideNow();
    }

    /** player frame -> its glow */
    private static final Map<View, Glow> glows = new WeakHashMap<>();
    private static final Map<View, Watch> watches = new WeakHashMap<>();
    private static boolean saidNoFrame;

    static void watch(Activity activity) {
        if (!READY) return;
        String name = activity.getClass().getName();
        boolean wanted = false;
        for (String screen : SCREENS) wanted |= screen.equals(name);
        if (!wanted) return;
        View root = activity.getWindow().getDecorView();
        Watch watch = watches.get(root);
        if (watch == null) { watch = new Watch(activity, root); watches.put(root, watch); }
        watch.attach();
    }

    static void stop(Activity activity) {
        Watch watch = watches.get(activity.getWindow().getDecorView());
        if (watch != null) watch.detach();
    }

    /** Finds the picture on screen (at most every 250 ms, on frames drawn anyway) and copies it every EVERY. */
    private static final class Watch implements ViewTreeObserver.OnPreDrawListener, Runnable {
        final WeakReference<Activity> activity;
        final WeakReference<View> root;
        WeakReference<ViewTreeObserver> observer = new WeakReference<>(null);
        WeakReference<View> surface = new WeakReference<>(null);
        WeakReference<Glow> current = new WeakReference<>(null);
        long nextLook;
        boolean sampling;

        Watch(Activity activity, View root) { this.activity = new WeakReference<>(activity); this.root = new WeakReference<>(root); }

        void attach() {
            View view = root.get();
            if (view == null) return;
            ViewTreeObserver now = view.getViewTreeObserver(), old = observer.get();
            if (now == old) return;
            if (old != null && old.isAlive()) old.removeOnPreDrawListener(this);
            if (!now.isAlive()) return;
            now.removeOnPreDrawListener(this);
            now.addOnPreDrawListener(this);
            observer = new WeakReference<>(now);
        }

        void detach() {
            ViewTreeObserver old = observer.get();
            if (old != null && old.isAlive()) old.removeOnPreDrawListener(this);
            observer = new WeakReference<>(null);
            View view = root.get();
            if (view != null) view.removeCallbacks(this);
            sampling = false;
        }

        @Override public boolean onPreDraw() {
            long now = android.os.SystemClock.uptimeMillis();
            if (now < nextLook) return true;
            nextLook = now + 250L;
            try { look(); } catch (Throwable error) { Diary.note("ambient: " + error); }
            return true;
        }

        private void look() {
            View view = root.get();
            if (view == null) return;
            boolean able = isEnabled() && view.hasWindowFocus();
            // a video first; a slideshow (photos, no video view) by its large picture
            View found = able ? playing(view, 0, false) : null;
            if (found == null && able && android.os.Build.VERSION.SDK_INT >= 28) found = playing(view, 0, true);
            // the one followed so far stays while it is as much on screen: no jumping between equal players
            View kept = surface.get();
            // (a video and a photo are not equals: back from another screen, the video's cover kept would win over it)
            if (found != null && kept != null && kept != found && kept.getVisibility() == View.VISIBLE && seen(kept) >= seen(found)
                    && (kept instanceof android.widget.ImageView) == (found instanceof android.widget.ImageView)) found = kept;
            Glow was = current.get();
            Glow glow = found == null ? null : glowOf(found, view.getHeight());
            // lost for a moment (a sheet over it, a frame mid-swipe) is not gone: kept, it fades after three looks without it
            if (glow == null) { surface = new WeakReference<>(null); if (was != null) was.miss(); return; }
            if (was != null && was != glow) was.fadeTo(0f);   // another video's page: that one goes, softly
            surface = new WeakReference<>(found);
            current = new WeakReference<>(glow);
            glow.follow(found);
            if (glow.failed >= 2 && found instanceof SurfaceView && android.os.Build.VERSION.SDK_INT >= 28) glow.cover = new WeakReference<>(playing(view, 0, true));
            if (!sampling) { sampling = true; view.post(this); }
        }

        /** One copy of the picture; the next in EVERY (longer while it stands still). */
        @Override public void run() {
            View view = root.get(), found = surface.get();
            Glow glow = current.get();
            Activity screen = activity.get();
            if (view == null || found == null || glow == null || screen == null || !view.hasWindowFocus() || !isEnabled() || !found.isAttachedToWindow()) { sampling = false; return; }
            glow.sample(screen, found);
            view.postDelayed(this, glow.still >= 4 ? 4 * EVERY : EVERY);
        }
    }

    /**
     * The video on screen: the largest SurfaceView or TextureView most of the screen wide and a third of it tall, at least
     * 60 % visible; with `photos`, the largest such ImageView instead (a slideshow's photo).
     */
    private static View playing(View view, int depth, boolean photos) {
        if (view == null || depth > 40 || view.getVisibility() != View.VISIBLE) return null;
        // most of the screen wide and a third of it tall: a search grid's small previews are no player
        boolean candidate = (photos ? view instanceof android.widget.ImageView : view instanceof SurfaceView || view instanceof TextureView)
                && view.getWidth() >= view.getRootView().getWidth() * .6f && view.getHeight() >= view.getRootView().getHeight() * .3f;
        if (candidate) return seen(view) > 0 ? view : null;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        View best = null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = playing(group.getChildAt(i), depth + 1, photos);
            // the most of it on screen: preloaded pages next to the playing one are views of the same size
            if (found != null && (best == null || seen(found) > seen(best))) best = found;
        }
        return best;
    }

    /** How much of the view is on screen (0 under 60 % of it either way). */
    private static long seen(View view) {
        Rect seen = new Rect();
        return view.getWidth() > 0 && view.isAttachedToWindow() && view.getGlobalVisibleRect(seen) && seen.height() >= view.getHeight() * .6f
                && seen.width() >= view.getWidth() * .6f ? (long) seen.width() * seen.height() : 0;
    }

    /**
     * The player frame the picture sits in -- the first frame layout above it that is most of the screen tall -- and its
     * glow, laid over it (under TikTok's buttons and captions, which are not in that frame) the first time.
     */
    private static Glow glowOf(View surface, int screen) {
        View frame = surface.getParent() instanceof View ? (View) surface.getParent() : null;
        for (int i = 0; frame != null && i < 6 && !(frame instanceof FrameLayout && frame.getHeight() >= screen * .7f); i++)
            frame = frame.getParent() instanceof View ? (View) frame.getParent() : null;
        if (!(frame instanceof FrameLayout) || frame.getHeight() < screen * .7f) {
            if (!saidNoFrame) { saidNoFrame = true; Diary.note("ambient: no player frame above " + surface.getClass().getName()); }
            return null;
        }
        Glow glow = glows.get(frame);
        if (glow != null && glow.getParent() == frame) return glow;
        glow = new Glow(frame.getContext());
        ((FrameLayout) frame).addView(glow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        glows.put(frame, glow);
        return glow;
    }

    /** The picture's own frame, stretched past it into the bands and fading out toward the screen's edges. */
    static final class Glow extends View {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG), fade = new Paint();
        /** where the picture's view is in the frame, and where the picture itself is */
        private final Rect view = new Rect(), picture = new Rect();
        private final Bitmap copy = Bitmap.createBitmap(COPY, COPY, Bitmap.Config.ARGB_8888);
        private final Bitmap tile = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888);
        private final int[] px = new int[COPY * COPY], target = new int[TILE * TILE], drawnPx = new int[TILE * TILE];
        private float[] shown;
        private boolean haveTarget, copying;
        private long lastFrame;
        private int misses;
        private LinearGradient[] shaders;
        int still, failed, tries;
        /** the picture over the video, read in its place when the video gives no copy */
        WeakReference<View> cover = new WeakReference<>(null);

        Glow(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setWillNotDraw(false);
            setVisibility(GONE);
        }

        private String said = "";
        /** What became of the copies, once each time it changes (logcat tag ttcuz). */
        private void say(String what) { if (!what.equals(said)) { said = what; Diary.note("ambient: " + what); } }

        void hideNow() { animate().cancel(); setAlpha(0f); setVisibility(GONE); misses = 0; }
        /** Gone only after three doubtful copies or looks in a row: one dark frame or lost look does not make it blink. */
        void miss() { if (++misses >= 3) fadeTo(0f); }
        void fadeTo(float a) {
            if (a > 0f && getVisibility() != VISIBLE) { setAlpha(0f); setVisibility(VISIBLE); }
            if (getVisibility() != VISIBLE) return;
            if (a == 0f && getAlpha() == 0f) { setVisibility(GONE); return; }
            if (getAlpha() == a) return;
            animate().cancel();
            animate().alpha(a).setDuration(Motion.time(350L)).withEndAction(a == 0f ? () -> setVisibility(GONE) : null).start();
        }

        /** Where the picture's view is now, in this frame's coordinates (its own scale included). */
        void follow(View surface) {
            RectF r = new RectF(0, 0, surface.getWidth(), surface.getHeight());
            if (!surface.getMatrix().isIdentity()) surface.getMatrix().mapRect(r);
            float x = 0, y = 0;
            View parent = (View) getParent();
            for (View v = surface; v != null && v != parent; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
                x += v.getLeft() - (v.getParent() instanceof View ? ((View) v.getParent()).getScrollX() : 0);
                y += v.getTop() - (v.getParent() instanceof View ? ((View) v.getParent()).getScrollY() : 0);
            }
            view.set(Math.round(r.left + x), Math.round(r.top + y), Math.round(r.right + x), Math.round(r.bottom + y));
        }

        void sample(Activity screen, View surface) {
            if (copying) return;
            try {
                if (surface instanceof TextureView) {
                    ((TextureView) surface).getBitmap(copy);
                    take(true);
                } else if (surface instanceof android.widget.ImageView && android.os.Build.VERSION.SDK_INT >= 28) {
                    photo(surface);
                } else if (surface instanceof SurfaceView && android.os.Build.VERSION.SDK_INT >= 26) {
                    // a surface that gave no frame twice running is asked again only now and then; its cover meanwhile
                    View over = cover.get();
                    if (failed >= 2 && over != null && ++tries % 10 != 0) cover(over);
                    else surfaceSample((SurfaceView) surface);
                }
            } catch (Throwable error) { copying = false; Diary.note("ambient sample: " + error); }
        }

        /**
         * A photo: TikTok keeps pictures in GPU memory, which a plain canvas cannot read -- drawn by the GPU straight at
         * 32 x 32, then read back.
         */
        private void photo(View picture) { draw(picture); take(true); }

        private void draw(View picture) {
            android.graphics.Picture recorded = new android.graphics.Picture();
            Canvas into = recorded.beginRecording(Math.max(1, picture.getWidth()), Math.max(1, picture.getHeight()));
            into.drawColor(Color.BLACK);
            picture.draw(into);
            recorded.endRecording();
            Bitmap small = Bitmap.createBitmap(recorded, COPY, COPY, Bitmap.Config.HARDWARE);
            Bitmap readable = small.copy(Bitmap.Config.ARGB_8888, false);
            small.recycle();
            new Canvas(copy).drawBitmap(readable, 0, 0, null);
            readable.recycle();
        }

        /** The picture TikTok lays over its video (the cover; a slideshow's photo), read when the video itself cannot be. */
        private void cover(View over) {
            if (android.os.Build.VERSION.SDK_INT < 28 || !over.isAttachedToWindow()) return;
            draw(over);
            // the cover lies where the video is: its place is the video's, and it is not cut (a dark picture was cut
            // to nothing and the glow went out); a cover TikTok has hidden reads black and leaves the last colours
            Rect fit = fitted();
            if (!fit.equals(picture)) { picture.set(fit); shaders = null; invalidate(); }
            if (!black()) take(false);
            else if (haveTarget) { misses = 0; fadeTo(1f); }   // back from another screen: the last colours come back
        }

        private boolean black() {
            copy.getPixels(px, 0, COPY, 0, 0, COPY, COPY);
            for (int c : px) if (Color.red(c) + Color.green(c) + Color.blue(c) > 24) return false;
            return true;
        }

        /**
         * TikTok's SurfaceView: the surface's own copy is the video alone, its letterbox is not in it -- and a copy of the
         * window does not hold the video either (it is a layer of its own under the window). So the picture's place is
         * the playing post's own size fitted into the view, centred, as the surface shows it.
         */
        private void surfaceSample(SurfaceView surface) {
            if (!surface.getHolder().getSurface().isValid()) { say("surface not valid"); return; }
            Rect fit = fitted();
            if (!fit.equals(picture)) { picture.set(fit); shaders = null; invalidate(); }
            copying = true;
            android.view.PixelCopy.request(surface, copy, result -> {
                copying = false;
                if (result == android.view.PixelCopy.SUCCESS) { failed = 0; take(false); return; }
                // TikTok plays some videos (opened from search, a profile) where the surface's copy has no frame (3)
                say("copy failed " + result);
                View over = cover.get();
                if (++failed >= 2 && over != null) cover(over);
            }, getHandler());
        }

        /** The playing post's size fitted into the video view, centred; the view itself when the size is not known. */
        private Rect fitted() {
            Object video = NativeRead.get(PlayerTools.post(), "getVideo");
            float vw = NativeRead.number(NativeRead.get(video, "getWidth")), vh = NativeRead.number(NativeRead.get(video, "getHeight"));
            int w = view.width(), h = view.height();
            if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return new Rect(view);
            float k = Math.min(w / vw, h / vh), pw = vw * k, ph = vh * k;
            // another post's size read for this one (see VideoCorners): the view rather than a wrong place
            if (pw < w * .75f && ph < h * .75f) return new Rect(view);
            return new Rect(Math.round(view.left + (w - pw) / 2f), Math.round(view.top + (h - ph) / 2f), Math.round(view.left + (w + pw) / 2f), Math.round(view.top + (h + ph) / 2f));
        }

        /** The picture inside the copy: its black rows and columns cut off (in copy cells); null for an all-black copy. */
        private Rect cut() {
            copy.getPixels(px, 0, COPY, 0, 0, COPY, COPY);
            int top = 0, bottom = COPY - 1, left = 0, right = COPY - 1;
            while (top < bottom && dark(top, true, 0, COPY - 1)) top++;
            while (bottom > top && dark(bottom, true, 0, COPY - 1)) bottom--;
            while (left < right && dark(left, false, top, bottom)) left++;
            while (right > left && dark(right, false, top, bottom)) right--;
            // TikTok centres a picture: its bars are the same on both sides. Dark on one side only is the picture
            // itself (a night sky), never a bar to light over
            if (Math.abs(top - (COPY - 1 - bottom)) > 2) { top = 0; bottom = COPY - 1; }
            if (Math.abs(left - (COPY - 1 - right)) > 2) { left = 0; right = COPY - 1; }
            return bottom - top < 2 || right - left < 2 ? null : new Rect(left, top, right + 1, bottom + 1);
        }

        private boolean dark(int line, boolean row, int from, int to) {
            int bright = 0, all = 0;
            for (int i = from; i <= to; i++) {
                int c = row ? px[line * COPY + i] : px[i * COPY + line];
                if (Color.red(c) + Color.green(c) + Color.blue(c) > 36) bright++;
                all++;
            }
            return bright * 10 < all;
        }

        /** The picture's place in the frame, from its cells in the copy of the view. */
        private void place(Rect cells) {
            int w = view.width(), h = view.height();
            Rect next = new Rect(view.left + cells.left * w / COPY, view.top + cells.top * h / COPY,
                    view.left + cells.right * w / COPY, view.top + cells.bottom * h / COPY);
            if (!next.equals(picture)) { picture.set(next); shaders = null; invalidate(); }
        }

        /**
         * A new copy taken: with `whole`, the copy is the whole view and the picture is cut out of it; otherwise it is
         * the picture alone (a SurfaceView's video) and its place is the fitted one.
         */
        private void take(boolean whole) {
            Rect cells;
            if (whole) {
                cells = cut();
                // too dark to tell where it is: what is shown stays (counted as a miss, a dark scene blinked the glow)
                if (cells == null) { if (!haveTarget || picture.isEmpty()) miss(); return; }
                place(cells);
            } else {
                copy.getPixels(px, 0, COPY, 0, 0, COPY, COPY);
                cells = new Rect(0, 0, COPY, COPY);
            }
            View frame = (View) getParent();
            boolean bands = frame != null && !picture.isEmpty() && (picture.top > frame.getHeight() * .03f || frame.getHeight() - picture.bottom > frame.getHeight() * .03f
                    || picture.left > frame.getWidth() * .03f || frame.getWidth() - picture.right > frame.getWidth() * .03f);
            if (!bands) { say("no bands " + picture + " in " + (frame == null ? "-" : frame.getWidth() + "x" + frame.getHeight())); miss(); return; }
            say("lit " + picture);
            // the picture's own cells, averaged down to the tile
            boolean changed = !haveTarget;
            for (int ty = 0; ty < TILE; ty++) for (int tx = 0; tx < TILE; tx++) {
                int x0 = cells.left + tx * cells.width() / TILE, x1 = Math.max(x0 + 1, cells.left + (tx + 1) * cells.width() / TILE);
                int y0 = cells.top + ty * cells.height() / TILE, y1 = Math.max(y0 + 1, cells.top + (ty + 1) * cells.height() / TILE);
                int r = 0, g = 0, b = 0, n = 0;
                for (int y = y0; y < y1 && y < COPY; y++) for (int x = x0; x < x1 && x < COPY; x++) { int c = px[y * COPY + x]; r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++; }
                n = Math.max(1, n);
                int c = Color.rgb(r / n, g / n, b / n), i = ty * TILE + tx;
                if (!changed && Math.abs(Color.red(c) - Color.red(target[i])) + Math.abs(Color.green(c) - Color.green(target[i])) + Math.abs(Color.blue(c) - Color.blue(target[i])) > 12) changed = true;
                target[i] = c;
            }
            misses = 0;
            fadeTo(1f);
            if (!changed) { still++; return; }
            still = 0;
            if (shown == null) { shown = new float[TILE * TILE * 3]; for (int i = 0; i < TILE * TILE; i++) { shown[3 * i] = Color.red(target[i]); shown[3 * i + 1] = Color.green(target[i]); shown[3 * i + 2] = Color.blue(target[i]); } }
            haveTarget = true;
            lastFrame = 0;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            if (!haveTarget || shown == null || picture.isEmpty()) return;
            long now = android.os.SystemClock.uptimeMillis();
            float dt = lastFrame == 0 ? 16f : Math.min(100f, now - lastFrame);
            lastFrame = now;
            // each colour eases toward the newest copy: about two thirds of the way in a third of EASE, at any frame rate
            float k = 1f - (float) Math.exp(-dt / Math.max(1f, Motion.time(EASE) / 3f));
            boolean moving = false;
            for (int i = 0; i < TILE * TILE; i++) {
                int t = target[i];
                float r = ease(3 * i, Color.red(t), k), g = ease(3 * i + 1, Color.green(t), k), b = ease(3 * i + 2, Color.blue(t), k);
                moving |= Math.abs(r - Color.red(t)) + Math.abs(g - Color.green(t)) + Math.abs(b - Color.blue(t)) > 1.5f;
                drawnPx[i] = Color.rgb(Math.round(r), Math.round(g), Math.round(b));
            }
            tile.setPixels(drawnPx, 0, TILE, 0, 0, TILE, TILE);

            int w = getWidth(), h = getHeight(), save = canvas.save();
            if (android.os.Build.VERSION.SDK_INT >= 26) canvas.clipOutRect(picture);
            else canvas.clipRect(new RectF(picture), android.graphics.Region.Op.DIFFERENCE);
            // the frame stretched past the picture over the whole player and a little wider: its top runs up into the
            // band above, its bottom down into the band below, its sides out to the sides
            RectF over = new RectF(-w * .08f, Math.min(0, picture.top - picture.height() * .6f), w * 1.08f, Math.max(h, picture.bottom + picture.height() * .6f));
            paint.setAlpha(215);
            canvas.drawBitmap(tile, null, over, paint);
            // and into black toward the screen's edges
            if (shaders == null) shaders = gradients(w, h);
            for (int i = 0; i < 4; i++) {
                if (shaders[i] == null) continue;
                fade.setShader(shaders[i]);
                if (i == 0) canvas.drawRect(0, 0, w, picture.top, fade);
                else if (i == 1) canvas.drawRect(0, picture.bottom, w, h, fade);
                else if (i == 2) canvas.drawRect(0, picture.top, picture.left, picture.bottom, fade);
                else canvas.drawRect(picture.right, picture.top, w, picture.bottom, fade);
            }
            canvas.restoreToCount(save);
            if (moving) postInvalidateOnAnimation(); else lastFrame = 0;
        }

        private float ease(int at, float to, float k) { float v = shown[at] + (to - shown[at]) * k; shown[at] = v; return v; }

        /** Clear at the picture, black at the screen's edge, for each band there is. */
        private LinearGradient[] gradients(int w, int h) {
            int clear = 0x00000000, black = 0xF0000000;
            LinearGradient[] out = new LinearGradient[4];
            if (picture.top > 0) out[0] = new LinearGradient(0, picture.top, 0, 0, clear, black, Shader.TileMode.CLAMP);
            if (picture.bottom < h) out[1] = new LinearGradient(0, picture.bottom, 0, h, clear, black, Shader.TileMode.CLAMP);
            if (picture.left > 0) out[2] = new LinearGradient(picture.left, 0, 0, 0, clear, black, Shader.TileMode.CLAMP);
            if (picture.right < w) out[3] = new LinearGradient(picture.right, 0, w, 0, clear, black, Shader.TileMode.CLAMP);
            return out;
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { super.onSizeChanged(w, h, ow, oh); shaders = null; }
    }
}
