package cat.ttcuz.framework;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.View;

/**
 * The glass island of lens.html (its main bar, mk()), fitted inside TikTok's own tab row, which stays where it is,
 * invisible, for routing, badges and accessibility. Android 13+: one RuntimeShader over a picture of what is behind
 * draws the CSS glass (.glass: blur, saturate, brightness, tint and its inset rim shadows), the rest pill, the lens's
 * shadow, and the liquid lens of the demo's first shader (FS) that grows under a finger: refraction with dispersion
 * through the demo's matte texture (buildBackdrop), the tabs under its middle turning the accent. Below that: a
 * frosted bar with the rest pill, no lens.
 */
final class LensBar extends View implements NavigationIsland.Bar, Choreographer.FrameCallback {
    /**
     * Its input is four bands of one picture, each as tall as this view: what is behind, the same blurred, the tabs
     * (the chosen one in the accent), and the tabs all in the accent (the demo's second texture). Lengths in dp.
     */
    static final String SHADER =
        "uniform shader content;uniform float uD;uniform float uH;uniform float uS;uniform float uGb;uniform float uDk;uniform float uG;uniform float uSc;"
      + "uniform float uCo;uniform float uOn;uniform float uRestA;uniform float uCell;uniform float uSharp;uniform float uMatRim;uniform float uDim;"
      + "uniform float4 uBar;uniform float4 uTint;uniform float4 uMat;uniform float4 uRest;uniform float4 uLens;uniform float3 uRim;"
      + "float sdf(float2 p,float2 h){float r=h.y;float2 q=abs(p)-h+r;return length(max(q,0.))+min(max(q.x,q.y),0.)-r;}"
      + "float2 at(float2 p,float band){return float2(p.x*uD,clamp(p.y*uD,.5,uH-.5)+band*uH);}"
      + "float4 tx(float2 c){return float4(content.eval(c));}"
      + "float3 sat(float3 c,float s){return mix(float3(dot(c,float3(.2126,.7152,.0722))),c,s);}"
      // .glass: blur(), saturate(1.5) and brightness() of what is behind, ::before the tint, ::after its inset shadows
      // painted last to first (the CSS list's first shadow is on top); premultiplied, nothing off the bar
      + "float4 glass(float2 p){"
      + " float2 q=p-uBar.xy;float d=sdf(q,uBar.zw);float a=1.-smoothstep(-.7,.7,d);if(a<=0.)return float4(0.);"
      + " float3 g=sat(tx(at(p,1.)).rgb,1.5)*uGb;g=mix(g,uTint.rgb,uTint.a);float ed=max(-d,0.);"

      + " g=mix(g,float3(1.),uRim.z*smoothstep(-2.,2.,sdf(q+float2(0.,1.),uBar.zw)));"
      + " g=mix(g,float3(1.),uRim.y*smoothstep(-2.,2.,sdf(q-float2(0.,1.5),uBar.zw)));"
      + " g=mix(g,float3(1.),uRim.x*(1.-smoothstep(.4,1.2,ed)));"
      + " return float4(g*a,a);}"
      // the tabs; near the lens's middle, as it grows, the accent ones (the demo's txm)
      + "float4 tabs(float2 p,float k){float4 u=tx(at(p,2.));"
      + " if(k>0.){k*=1.-smoothstep(uCell*.22,uCell*.6,abs(p.x-uLens.x));u=mix(u,tx(at(p,3.)),k);}return u*uDim;}"
      // buildBackdrop: what the lens bends. The bar there is matte: a soft copy, the sharp picture over it, a tint, a
      // .8 px rim, then the tabs; off the bar the picture as it is
      + "float3 scene(float2 c,float k){"
      + " float3 col=tx(at(c,0.)).rgb;float d=sdf(c-uBar.xy,uBar.zw);float a=1.-smoothstep(-.7,.7,d);"
      + " if(a>0.){float3 m=mix(tx(at(c,1.)).rgb,col,uSharp);m=mix(m,uMat.rgb,uMat.a);"
      + "  m=mix(m,float3(1.),uMatRim*(1.-smoothstep(.6,1.4,max(-d,0.))));col=mix(col,m,a);}"
      + " float4 u=tabs(c,k);return col*(1.-u.a)+u.rgb;}"
      + "half4 main(float2 fc){"
      + " if(fc.y>=uH)return half4(0.);"
      + " float2 f=fc/uD;float4 o=glass(f);float4 u=tabs(f,0.);o=o*(1.-u.a)+u;"
      // the lens box, scaled about its middle as the demo's CSS scales it: everything inside is in its own units
      + " float2 p=(f-uLens.xy)/uSc;float d=sdf(p,uLens.zw);float inl=1.-smoothstep(-.7,.7,d);"
      + " if(uCo>0.&&inl>0.){"
      + "  float2 e=float2(.5,0.);"
      + "  float2 n=normalize(float2(sdf(p+e.xy,uLens.zw)-sdf(p-e.xy,uLens.zw),sdf(p+e.yx,uLens.zw)-sdf(p-e.yx,uLens.zw))+1e-5);"
      + "  float R=uLens.w*.95;float t=clamp(-d/R,0.,1.);float w=1.-t;float hh=sqrt(max(1.-w*w,.0001));"
      + "  float slope=min(w/hh,3.5)*.55*uG;float3 N=normalize(float3(n*slope,1.));float3 I=float3(0.,0.,-1.);float thick=20.*uS*uG;"
      + "  float2 c=uLens.xy+p*mix(1.,.88,uG);"
      + "  float3 rr=refract(I,N,1./1.50),rg=refract(I,N,1./1.53),rb=refract(I,N,1./1.56);"
      + "  float2 oR=rr.xy/-rr.z*thick,oG=rg.xy/-rg.z*thick,oB=rb.xy/-rb.z*thick;"
      + "  float tk=smoothstep(.15,.9,clamp(uG,0.,1.));"
      + "  float3 col=float3(scene(c+oR,tk).r,scene(c+oG,tk).g,scene(c+oB,tk).b);"
      + "  float gk=clamp(uG,0.,1.);col=mix(col,mix(float3(1.),float3(.05),uDk),.10*gk);col*=1.+.04*gk*(1.-uDk);"
      + "  float3 hc=float3(1.)*(1.-.2*uDk);float ed=max(-d,0.);"
      + "  float2 l1=normalize(float2(-.55,-.83)),l2=-l1;"
      + "  float d1=max(dot(n,l1),0.),d2=max(dot(n,l2),0.);"
      + "  float dir=pow(d1,1.4)+.75*pow(d2,1.4);"
      + "  float ring=.28+dir;float core=1.-smoothstep(0.,1.4,ed);float halo=exp(-ed/(5.5*uS))*(1.-core);"
      + "  col+=hc*(core*ring*1.25+halo*dir*.5)*gk;col+=float3(1.)*pow(1.-N.z,2.)*.14*gk;"
      + "  col*=1.-.10*gk*(1.-smoothstep(0.,7.*uS,ed))*(1.-dir);"
      + "  col*=1.-.20*gk*(1.-smoothstep(3.*uS,12.*uS,ed))*(1.-core)*(1.-.55*dir);"
      + "  float k=inl*uCo;o=o*(1.-k)+float4(col,1.)*k;"
      + " }"
      // .lens.on's shadow (0 8px 18px .28) outside the lens, then .l-rest inside it
      + " float ds=sdf((f-uLens.xy-float2(0.,8.*uS))/uSc,uLens.zw)*uSc;float sh=.28*uOn*(1.-smoothstep(-18.*uS,18.*uS,ds))*(1.-inl);"
      + " o=o*(1.-sh)+float4(0.,0.,0.,sh);"
      + " float ra=uRest.a*uRestA*inl;o=o*(1.-ra)+float4(uRest.rgb*ra,ra);"
      + " return half4(o);}";

    /** Google's Material Symbols (Rounded) per tab role; the chosen tab gets the filled one. */
    private static final String[] ICONS = {"home", "group", "add", "chat_bubble", "person"};

    final LensMotion mo = new LensMotion();
    private final float dp;
    private boolean dark, looping, placed, ticking;
    /** The glass from clear (0) to frosted (1): the demo's slider. */
    private float glass = .7f;
    private int[] roles = {0, 1, 2, 3, 4};
    private String[] names = new String[5], badges = new String[5];
    private long last;
    /** Where the island may draw (TikTok's row, px of this view), the bar in it, and a scratch rect. */
    private final RectF room = new RectF(), bar = new RectF(), r = new RectF();

    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG),
            badge = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), cut = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics fm = new Paint.FontMetrics(), bm = new Paint.FontMetrics();
    private final Path barPath = new Path();

    /** Android 13+: the backdrop, its blurred band, the four-band picture the shader reads, and the shader. */
    private final Blur.Backdrop backdrop;
    private Object scene, soft, shader;
    private boolean gpu;
    private int sized, tabsFor, uniformsFor;
    private float softFor = -1f;

    LensBar(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;
        text.setTypeface(Build.VERSION.SDK_INT >= 28 ? Typeface.create(Typeface.DEFAULT, 600, false) : Typeface.DEFAULT_BOLD);
        text.setTextSize(10f); text.setTextAlign(Paint.Align.CENTER);
        badge.setTypeface(Build.VERSION.SDK_INT >= 28 ? Typeface.create(Typeface.DEFAULT, 700, false) : Typeface.DEFAULT_BOLD);
        badge.setTextAlign(Paint.Align.CENTER);
        cut.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        backdrop = Blur.Backdrop.create();
        if (backdrop != null && Blur.shaders()) {
            try {
                shader = new android.graphics.RuntimeShader(SHADER);
                scene = new android.graphics.RenderNode("ttcuz-island"); soft = new android.graphics.RenderNode("ttcuz-island-soft");
                backdrop.plain(1); gpu = true;
            } catch (Throwable error) { Diary.note("island glass: " + error); }
        }
        setWillNotDraw(false);
    }

    // ------------------------------------------------------------------ NavigationIsland.Bar

    @Override public void place(float left, float top, float right, float bottom) {
        if (room.left == left && room.top == top && room.right == right && room.bottom == bottom) return;
        room.set(left, top, right, bottom);
        layoutBar(); invalidate();
    }

    @Override public void tabs(int[] roles, String[] names, int create, int chosen) {
        this.roles = roles; this.names = names; badges = new String[roles.length];
        mo.n = roles.length; mo.create = create; if (chosen >= 0) mo.sel = chosen;
        placed = true; mo.settle(); tabsFor = 0; invalidate();
    }

    @Override public void badges(String[] now) { badges = now; tabsFor = 0; invalidate(); }

    @Override public void select(int tab) { int was = mo.sel; mo.follow(tab); if (mo.sel != was) kick(); }

    @Override public void settings(boolean dark, boolean labels, float glass) {
        boolean first = !placed;
        if (this.dark != dark || this.glass != glass) { this.dark = dark; this.glass = glass; tabsFor = 0; uniformsFor = 0; invalidate(); }
        if (mo.labels != labels) { mo.labels = labels; if (first) mo.names = labels ? 1f : 0f; else kick(); }
    }

    @Override public boolean hit(float x, float y) { return !bar.isEmpty() && x > bar.left && x < bar.right && y > bar.top && y < bar.bottom; }

    private float along(float x) { return (x - bar.left) / dp; }

    @Override public int down(float x) { int tab = mo.down(along(x)); kick(); return tab; }
    @Override public void move(float x) { mo.move(along(x)); kick(); }
    @Override public int up() { int tab = mo.up(); kick(); return tab; }
    @Override public void cancel() { mo.cancel(); kick(); }
    @Override public int tabAt(float x) { return bar.isEmpty() || mo.n <= 0 ? -1 : mo.near(along(x)); }

    /** Anti-burn: the tabs' brightness now and where it eases to. */
    private float dimNow = 1f, dimTo = 1f;
    @Override public void dim(float level) { if (level != dimTo) { dimTo = level; kick(); } }

    /** From the window's pre-draw: what is behind is recorded again (at most ~25 times a second, see Blur.Backdrop). */
    @Override public void capture() {
        if (backdrop == null || getWidth() <= 0 || getHeight() <= 0 || bar.isEmpty()) return;
        if (gpu) { r.set(0, 0, getWidth(), getHeight()); backdrop.record(this, r, 0f); }
        else backdrop.record(this, bar, (6 + 14 * glass) * dp);
        // a video's frames reach the glass only when it is recorded again: about 25 redraws a second while one plays behind
        if (backdrop.video() && !ticking) { ticking = true; postOnAnimationDelayed(tick, 40); }
    }

    private final Runnable tick = new Runnable() { public void run() { ticking = false; if (isShown()) invalidate(); } };

    // ------------------------------------------------------------------ motion

    private void kick() {
        invalidate();
        if (looping) return;
        looping = true; last = 0L;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long now) {
        float dt = last == 0L ? .016f : (now - last) / 1e9f; last = now;
        // the lens a touch quicker than the demo's own timing (a quarter quicker was too quick)
        boolean busy = mo.step(dt * 1.1f);
        // anti-burn eases in and out (about a third of a second), never jumps
        if (Math.abs(dimTo - dimNow) > .002f) { dimNow += (dimTo - dimNow) * Math.min(1f, dt / .1f); busy = true; } else dimNow = dimTo;
        int b = mo.buzz; mo.buzz = 0;
        if (b > 0) performHapticFeedback(b >= 10 ? HapticFeedbackConstants.VIRTUAL_KEY : b >= 8 ? HapticFeedbackConstants.KEYBOARD_TAP : HapticFeedbackConstants.CLOCK_TICK);
        invalidate();
        if (busy && isAttachedToWindow()) Choreographer.getInstance().postFrameCallback(this); else looping = false;
    }

    /**
     * Back from the background: the system dropped the app's drawing memory meanwhile, and the island's pictures
     * (recorded only when something changed) came back empty, the bar gone until a tab was pressed. Everything is
     * recorded afresh.
     */
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            sized = 0; tabsFor = 0; uniformsFor = 0; softFor = -1f;
            // a frame or a tick booked just before the app went to the background can be dropped with it, and the
            // flag that says "booked" then stayed on for good: no booking was made again, the lens no longer moved
            // when held and the glass kept its last picture. Back on screen, both are booked afresh
            Choreographer.getInstance().removeFrameCallback(this); looping = false;
            removeCallbacks(tick); ticking = false;
            kick();
        }
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Choreographer.getInstance().removeFrameCallback(this); looping = false; removeCallbacks(tick); ticking = false;
        if (backdrop != null) backdrop.release();
    }

    // ------------------------------------------------------------------ drawing

    /** The bar over TikTok's row: its height, the seek bar's width. */
    private void layoutBar() {
        // as the island before: the whole of TikTok's row (the room is given as wide as TikTok's seek bar), only a
        // row taller than 56 dp giving up to 6 dp at its foot
        float h = room.height() - Math.min(6 * dp, Math.max(0f, room.height() - 56 * dp));
        if (h < 24 * dp || room.width() <= 120 * dp) { bar.setEmpty(); return; }
        bar.set(room.left, room.top, room.right, room.top + h);
        float w = bar.width() / dp, hd = h / dp;
        if (mo.width != w || mo.height != hd) { mo.width = w; mo.height = hd; mo.settle(); tabsFor = 0; uniformsFor = 0; }
    }

    /**
     * Half the lens's width (dp) centred at cx (dp): the demo's, unless that would take it onto the bar's round ends --
     * by Home and Profile it stood past them and was cut. It keeps to its tab's middle and narrows instead, its side as
     * far in from the bar's end as its top is from the bar's top (3 dp when swollen and scaled up).
     */
    private float lensHalf(float cx, float o) {
        float s = mo.unit(), scale = 1 + .14f * o, margin = (6 - 3 * o) * s;
        float room = Math.min(cx - bar.left / dp, bar.right / dp - cx) - margin;
        return Math.max(4 * s, Math.min(mo.lensWidth() / 2f, room / scale));
    }

    /** The lens box's top in dp from the bar's (the demo's IN - g * EXD, both scaled with the bar). */
    private float lensTop() { float s = mo.unit(); return 6 * s - mo.g * 9 * s; }

    @Override protected void onDraw(Canvas canvas) {
        if (!placed || bar.isEmpty()) return;
        if (gpu && canvas.isHardwareAccelerated() && backdrop.ready()) { try { liquid(canvas); return; } catch (Throwable error) { gpu = false; Diary.note("island glass: " + error); } }
        frosted(canvas);
    }

    /** What the tabs' picture depends on: it is drawn again only when this changes. */
    private int tabsKey() {
        int k = mo.shown() + 7 * mo.sel; k = 31 * k + Math.round(mo.squash * 100); k = 31 * k + Math.round(mo.names * 100);
        k = 31 * k + (dark ? 1 : 0); k = 31 * k + Accent.colour();
        return k == 0 ? 1 : k;
    }

    private void liquid(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        android.graphics.RenderNode x = (android.graphics.RenderNode) scene, s = (android.graphics.RenderNode) soft;
        android.graphics.RuntimeShader sh = (android.graphics.RuntimeShader) shader;
        // the system drops the pictures of nodes drawn into a window while it is away (another app over it, the screen
        // off, memory trimmed) without the window's visibility changing: the island then drew nothing until a tab moved
        if (!s.hasDisplayList()) sized = 0;
        if (!x.hasDisplayList()) tabsFor = 0;
        if (sized != w * 31 + h) {
            sized = w * 31 + h;
            x.setPosition(0, 0, w, 4 * h);
            s.setPosition(0, 0, (w + 3) / 4, (h + 3) / 4);
            Canvas c = s.beginRecording();
            try { c.scale(.25f, .25f); backdrop.drawPlain(c); } finally { s.endRecording(); }
            softFor = -1f; tabsFor = 0; uniformsFor = 0;
        }
        // CSS blur(6..20px) is a Gaussian of that deviation; RenderEffect takes a radius (sigma = .577 r + .5), at a quarter of the size
        float sigma = (6 + 14 * glass) * dp / 4f, radius = Math.max(.5f, (sigma - .5f) / .57735f);
        if (radius != softFor) { softFor = radius; s.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP)); }
        int key = tabsKey();
        if (key != tabsFor) {
            tabsFor = key;
            Canvas c = x.beginRecording();
            try {
                int b = c.save(); c.clipRect(0, 0, w, h); backdrop.drawPlain(c); c.restoreToCount(b);
                // each band kept to its own rows: the quarter-size blur, scaled back up, ran 1-3 px into the tabs' band,
                // and the shader read it at the island's top edge as a dark line across the screen
                int k = c.save(); c.translate(0, h); c.clipRect(0, 0, w, h); c.scale(4f, 4f); c.drawRenderNode(s); c.restoreToCount(k);
                k = c.save(); c.translate(0, 2 * h); c.clipRect(0, 0, w, h); drawTabs(c, false); c.restoreToCount(k);
                k = c.save(); c.translate(0, 3 * h); c.clipRect(0, 0, w, h); drawTabs(c, true); c.restoreToCount(k);
            } finally { x.endRecording(); }
        }
        float o = mo.grown(), top = lensTop(), lh = mo.height - 2 * top, cx = bar.left / dp + mo.x, cy = bar.centerY() / dp;
        float half = lensHalf(cx, o);
        int u = Math.round(mo.g * 1000); u = 31 * u + Math.round(cx * 100); u = 31 * u + Math.round(mo.shade * 100); u = 31 * u + key; u = 31 * u + Math.round(bar.width()); u = 31 * u + Math.round(dimNow * 1000); u = 31 * u + Math.round(half * 100);
        if (u != uniformsFor) {
            uniformsFor = u == 0 ? 1 : u;
            float co = Math.min(1f, o / .6f); co = o > .0005f ? co * co * (3 - 2 * co) : 0f;
            float on = mo.shade * mo.shade * (3 - 2 * mo.shade);
            float a = dark ? .28f + .5f * glass : .30f + .55f * glass, matte = dark ? .22f + .47f * glass : .2f + .43f * glass;
            sh.setFloatUniform("uD", dp); sh.setFloatUniform("uH", h); sh.setFloatUniform("uS", mo.unit());
            sh.setFloatUniform("uBar", bar.centerX() / dp, cy, bar.width() / dp / 2f, mo.height / 2f);
            if (dark) sh.setFloatUniform("uTint", 24f / 255f, 24f / 255f, 30f / 255f, a); else sh.setFloatUniform("uTint", 1f, 1f, 1f, a);
            if (dark) sh.setFloatUniform("uMat", 22f / 255f, 22f / 255f, 28f / 255f, matte); else sh.setFloatUniform("uMat", 1f, 1f, 1f, matte);
            sh.setFloatUniform("uSharp", .9f - .5f * glass); sh.setFloatUniform("uMatRim", dark ? .18f : .6f);
            // the long bar plain: only its faint .8 dp edge, none of the demo's shine along its top and bottom (the
            // lens keeps its own highlights)
            if (dark) sh.setFloatUniform("uRim", .14f, 0f, 0f); else sh.setFloatUniform("uRim", .3f, 0f, 0f);
            // .l-rest, tinted with the accent (the demo's is grey): the chosen tab's pill says whose colours these are
            int rest = Expressive.mix(dark ? 0xFFFFFFFF : 0xFF32323E, Accent.colour(), .45f);
            sh.setFloatUniform("uRest", android.graphics.Color.red(rest) / 255f, android.graphics.Color.green(rest) / 255f, android.graphics.Color.blue(rest) / 255f, dark ? .2f : .26f);
            sh.setFloatUniform("uGb", dark ? .8f : 1.1f); sh.setFloatUniform("uDk", dark ? 1f : 0f);
            sh.setFloatUniform("uG", Math.min(mo.g, 1.1f)); sh.setFloatUniform("uSc", 1 + .14f * o);
            sh.setFloatUniform("uCo", co); sh.setFloatUniform("uOn", on); sh.setFloatUniform("uRestA", 1 - o);
            sh.setFloatUniform("uCell", mo.cell()); sh.setFloatUniform("uDim", dimNow);
            sh.setFloatUniform("uLens", cx, cy, half, lh / 2f);
            // shortcut: RenderEffect keeps a copy of the uniforms, so a frame where something moved needs a new (tiny) effect; the shader is reused. A HardwareBuffer input (one Paint shader, uniforms in place) would avoid even that if it ever shows in a profile
            x.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(sh, "content"));
        }
        int k = canvas.save(); canvas.clipRect(0, 0, w, h); canvas.drawRenderNode(x); canvas.restoreToCount(k);
    }

    /** Below Android 13: the demo's CSS bar (.glass): frosted backdrop, tint, a 0.8 dp rim, the tabs and the rest pill, no lens. */
    private void frosted(Canvas canvas) {
        float rad = bar.height() / 2f;
        barPath.reset(); barPath.addRoundRect(bar, rad, rad, Path.Direction.CW);
        if (backdrop != null && backdrop.ready()) backdrop.draw(canvas, bar, barPath);
        int a = Math.round(255 * (dark ? .28f + .5f * glass : .30f + .55f * glass));
        fill.setStyle(Paint.Style.FILL);
        fill.setColor((a << 24) | (dark ? 0x18181E : 0xFFFFFF));
        canvas.drawPath(barPath, fill);
        fill.setStyle(Paint.Style.STROKE); fill.setStrokeWidth(.8f * dp);
        fill.setColor(dark ? 0x38FFFFFF : 0x8CFFFFFF);
        canvas.drawPath(barPath, fill);
        fill.setStyle(Paint.Style.FILL);
        int dimmed = dimNow < 1f ? canvas.saveLayerAlpha(bar.left, bar.top, bar.right, bar.bottom, Math.round(255 * dimNow)) : -1;
        drawTabs(canvas, false);
        if (dimmed >= 0) canvas.restoreToCount(dimmed);
        float top = lensTop() * dp, cx = bar.left + mo.x * dp, half = lensHalf(cx / dp, mo.grown()) * dp;
        r.set(cx - half, bar.top + top, cx + half, bar.bottom - top);
        fill.setColor((Expressive.mix(dark ? 0xFFFFFFFF : 0xFF32323E, Accent.colour(), .45f) & 0xFFFFFF) | (dark ? 0x33000000 : 0x42000000));
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill);
    }

    /** The tabs, with names and without, crossfaded as the demo crossfades its two bars. */
    private void drawTabs(Canvas c, boolean accent) {
        float m = mo.names;
        int k = c.save(); c.translate(bar.left, bar.top); c.scale(dp, dp);
        if (m >= 1f) tabSet(c, accent, true);
        else if (m <= 0f) tabSet(c, accent, false);
        else {
            int l = c.saveLayerAlpha(0, 0, bar.width() / dp, mo.height, Math.round(255 * m)); tabSet(c, accent, true); c.restoreToCount(l);
            l = c.saveLayerAlpha(0, 0, bar.width() / dp, mo.height, Math.round(255 * (1 - m))); tabSet(c, accent, false); c.restoreToCount(l);
        }
        c.restoreToCount(k);
    }

    /**
     * One bar's tabs, in dp from the bar's corner, laid out as the demo's .tab: the icon (24 with names, 31 without,
     * smaller when the row is lower), 2 below it the name in a 12 line, the pair centred; the create button as a
     * rounded block with Google's plus cut through it; TikTok's unread marks. {@code accent}: every tab in the accent.
     */
    private void tabSet(Canvas c, boolean accent, boolean labels) {
        int colour = dark ? 0xFFF2F2F7 : 0xFF17171F, shown = mo.shown(), lit = Accent.colour();
        float h = mo.height, cell = mo.cell();
        float is = labels ? Math.min(24, h - 26) : Math.min(31, h * .46f), ps = Math.min(48, h * .72f);
        float blk = is + 2 + 12, t0 = (h - blk) / 2, icy = labels ? t0 + is / 2 : h / 2, lby = t0 + is + 2 + 6;
        text.getFontMetrics(fm);
        for (int i = 0; i < roles.length; i++) {
            float cx = mo.pad() + i * cell + cell / 2;
            if (i == mo.create) {
                // ICP's create button: a 22 x 16 block (of 24), 5 round, the plus cut out of it
                float sz = ps * mo.squash, bw = sz * 22 / 24, bh = sz * 16 / 24;
                int layer = c.saveLayer(cx - bw, h / 2 - bh, cx + bw, h / 2 + bh, null);
                ink.setColor(colour); r.set(cx - bw / 2, h / 2 - bh / 2, cx + bw / 2, h / 2 + bh / 2);
                c.drawRoundRect(r, sz * 5 / 24, sz * 5 / 24, ink);
                glyph(c, "add", cx, h / 2, bh * .95f, cut);
                c.restoreToCount(layer);
                continue;
            }
            boolean on = accent || i == shown;
            ink.setColor(on ? lit : colour);
            int role = Math.max(0, Math.min(4, roles[i]));
            glyph(c, i == mo.sel ? ICONS[role] + "_fill1" : ICONS[role], cx, icy, is, ink);
            if (labels && names[i] != null) { text.setColor(on ? lit : colour); c.drawText(names[i], cx, lby - (fm.ascent + fm.descent) / 2, text); }
            String count = badges[i];
            if (count == null) continue;
            if (count.isEmpty()) {
                // .dot: 11 across, its edge 4 right of the icon and 3 below it, ringed 1.5 in the bar's colour
                float dx = cx + is / 2 + 4 - 5.5f, dy = icy + is / 2 + 3 - 5.5f;
                badge.setColor(dark ? 0xFF2B2B31 : 0xFFE8E8EC); c.drawCircle(dx, dy, 5.5f, badge);
                badge.setColor(lit); c.drawCircle(dx, dy, 4f, badge);
                continue;
            }
            badge.setTextSize(10f); badge.getFontMetrics(bm);
            float bw = Math.max(16, badge.measureText(count) + 8), bx = cx + is / 2 - 5, by = icy - is / 2 - 6;
            badge.setColor(dark ? 0xFF2B2B31 : 0xFFE8E8EC); r.set(bx - 1.5f, by - 1.5f, bx + bw + 1.5f, by + 17.5f); c.drawRoundRect(r, 9.5f, 9.5f, badge);
            badge.setColor(lit); r.set(bx, by, bx + bw, by + 16); c.drawRoundRect(r, 8, 8, badge);
            badge.setColor(light(lit) ? 0xFF111111 : 0xFFFFFFFF); c.drawText(count, r.centerX(), by + 8.5f - (bm.ascent + bm.descent) / 2, badge);
        }
    }

    private void glyph(Canvas c, String name, float cx, float cy, float size, Paint paint) {
        Path p = Symbols.path(name);
        if (p == null) return;
        int k = c.save(); c.translate(cx - size / 2, cy - size / 2); c.scale(size / 24f, size / 24f); c.drawPath(p, paint); c.restoreToCount(k);
    }

    private static boolean light(int c) {
        return (.299f * android.graphics.Color.red(c) + .587f * android.graphics.Color.green(c) + .114f * android.graphics.Color.blue(c)) / 255f > .62f;
    }
}
