package cat.ttcuz.framework;

import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import java.lang.ref.WeakReference;

/**
 * ttcuz blur engine. Two paths, both free of per-frame CPU pixel work, so the cost does not depend on the refresh rate:
 *
 *  - {@link #window}: a floating window (download island, sheets) is blurred by the compositor itself
 *    (Android 12+ cross-window blur). The app draws nothing extra; SurfaceFlinger blurs what is under the window
 *    on the GPU at display rate, rounded to the window's background outline.
 *  - {@link Backdrop}: a surface inside a window (the navigation island) records only the views that are behind it
 *    into a RenderNode at a quarter of the resolution and lets RenderThread blur that node with RenderEffect.
 *    Recording happens in pre-draw, i.e. only when the app is drawing a frame anyway, so an idle screen costs nothing
 *    and nothing ever schedules extra frames. Recording time is measured; a slow device first records every other
 *    frame and then falls back to the opaque tonal surface for the rest of the session.
 *
 * Below Android 12, with blur disabled by the system (battery saver, tunnel mode) or after any error, callers simply
 * get {@code false} and keep their opaque tonal surface.
 */
final class Blur {
    private Blur() {}

    static boolean gpu(){return Build.VERSION.SDK_INT>=31;}
    /** Liquid Glass needs a runtime shader (AGSL, Android 13+); below it the frosted glass is used. */
    static boolean shaders(){return Build.VERSION.SDK_INT>=33&&Ttcuz.is64();}

    /** Recording scale: frosted glass is captured and blurred at 1/4 of its size (16x cheaper); liquid glass at 1/2, sharp enough to refract. */
    static final int SCALE=4,LIQUID_SCALE=3;

    // ------------------------------------------------------------------ the glass style setting

    public static final String KEY_STYLE="glass_style";
    /** Plastic: a solid tonal surface, no glass and no blur at all (nothing recorded, nothing blurred). */
    static final int FROSTED=0,LIQUID=1,PLASTIC=2;
    private static volatile int style=-1;
    static int style(){
        int known=style;if(known>=0)return known;
        android.content.Context context=Ttcuz.context();if(context==null)return LIQUID;
        int value=LIQUID;try{value=context.getSharedPreferences(Ttcuz.PREFS,android.content.Context.MODE_PRIVATE).getInt(KEY_STYLE,LIQUID);}catch(Throwable ignored){}
        style=value==FROSTED||value==PLASTIC?value:LIQUID;return style;
    }
    static void setStyle(int value){
        style=value==FROSTED||value==PLASTIC?value:LIQUID;
        android.content.Context context=Ttcuz.context();
        if(context!=null)context.getSharedPreferences(Ttcuz.PREFS,android.content.Context.MODE_PRIVATE).edit().putInt(KEY_STYLE,style).apply();
    }
    static void reloadSettings(){style=-1;tint=-1;}

    // ------------------------------------------------------------------ the glass tint setting

    /** Auto: a tonal glass from the accent. Dark: smoked glass with light content. Light: white glass with dark content. */
    public static final String KEY_TINT="glass_tint";
    static final int TINT_AUTO=0,TINT_DARK=1,TINT_LIGHT=2;
    private static volatile int tint=-1;
    static int tint(){
        int known=tint;if(known>=0)return known;
        android.content.Context context=Ttcuz.context();if(context==null)return TINT_AUTO;
        int value=TINT_AUTO;try{value=context.getSharedPreferences(Ttcuz.PREFS,android.content.Context.MODE_PRIVATE).getInt(KEY_TINT,TINT_AUTO);}catch(Throwable ignored){}
        tint=value==TINT_DARK||value==TINT_LIGHT?value:TINT_AUTO;return tint;
    }
    static void setTint(int value){
        tint=value==TINT_DARK||value==TINT_LIGHT?value:TINT_AUTO;
        android.content.Context context=Ttcuz.context();
        if(context!=null)context.getSharedPreferences(Ttcuz.PREFS,android.content.Context.MODE_PRIVATE).edit().putInt(KEY_TINT,tint).apply();
    }
    /** Liquid Glass is what will actually be drawn: chosen and supported. */
    static boolean liquid(){return style()==LIQUID&&shaders()&&!broken&&!lensBroken;}
    /** No glass: the plastic style was chosen. */
    static boolean plain(){return style()==PLASTIC;}
    /** A shader a device refuses only turns Liquid Glass off; the frosted glass keeps working. */
    private static volatile boolean lensBroken;

    /**
     * Liquid Glass as a slab of real glass, not frosting: the picture under it is barely softened and shows through
     * clear, slightly magnified. Toward the rim the slab curves down like a lens's edge (a quarter-circle profile), so
     * the picture there is refracted inward along the rounded rectangle's normal -- the bend grows steeply in the last
     * few pixels, as a real edge does -- and splits a little into its colours there. Light from the top left catches
     * the curved rim (and less of it the opposite rim), and a hairline marks the very edge. The flat middle costs one
     * read of the picture; only the rim reads three.
     */
    static final String GLASS_SHADER=
        "uniform shader content;uniform float2 size;uniform float inset;uniform float radius;uniform float bezel;uniform float thickness;uniform float zoom;uniform half4 tint;"
       +"float box(float2 p,float2 b,float r){float2 q=abs(p)-b+r;return length(max(q,0.0))+min(max(q.x,q.y),0.0)-r;}"
       +"half4 main(float2 xy){"
       +" float2 c=size*0.5;float2 b=c-inset;float2 p=xy-c;float inside=-box(p,b,radius);"
       +" if(inside<-0.5)return half4(0);"
       // the rounded rectangle's normal, from the nearest point of its inner rectangle: exact on sides and corners
       +" float2 core=max(b-radius,float2(0.0));float2 n=p-clamp(p,-core,core);n=n/max(length(n),1e-4);"
       // e: 1 at the rim, 0 a bezel in; the quarter-circle edge's slope, steep at the rim
       +" float e=1.0-clamp(inside/bezel,0.0,1.0);float slope=e*inversesqrt(max(1.0-e*e,0.03));"
       +" float shift=min(thickness*slope,bezel*1.6);"
       +" float2 uv=c+(xy-c)*zoom-n*shift;float2 lo=float2(0.5),hi=size-0.5;"
       +" half3 col;"
       +" if(e>0.0){float2 split=n*shift*0.1;"
       +"  col=half3(content.eval(clamp(uv+split,lo,hi)).r,content.eval(clamp(uv,lo,hi)).g,content.eval(clamp(uv-split,lo,hi)).b);}"
       +" else col=content.eval(clamp(uv,lo,hi)).rgb;"
       // the tint as a thin veil over clear glass
       +" col=mix(col,tint.rgb,tint.a*0.55);"
       // light on the curved rim: strong where it faces the top left, a softer bounce on the far side
       +" float rim=e*e*e;float2 sun=float2(-0.5,-0.866);"
       +" col+=half(rim*(0.50*max(dot(n,sun),0.0)+0.22*max(-dot(n,sun),0.0)+0.06));"
       // the hairline of the very edge
       +" col+=half(0.16*(1.0-smoothstep(0.0,1.6,inside)));"
       +" return half4(col,1.0)*half(clamp(inside+0.5,0.0,1.0));}";

    // ------------------------------------------------------------------ window blur (compositor)

    /**
     * Blur everything below a floating window, clipped to a rounded rect of {@code cornerPx}.
     * Returns whether the compositor blur is active now; the listener keeps it right if the system toggles blur.
     */
    static boolean window(final Window window,final int radiusPx,final float cornerPx,final Runnable onChange){
        if(window==null)return false;
        GradientDrawable shape=new GradientDrawable();shape.setColor(0);shape.setCornerRadius(cornerPx);
        // The window background's outline is what the compositor rounds the blur region to.
        window.setBackgroundDrawable(shape);
        if(!gpu())return false;
        if(plain()){try{window.setBackgroundBlurRadius(0);}catch(Throwable ignored){}return false;}
        try{
            WindowManager manager=window.getWindowManager();
            boolean enabled=manager!=null&&manager.isCrossWindowBlurEnabled();
            window.setBackgroundBlurRadius(enabled?radiusPx:0);
            if(manager!=null&&onChange!=null){
                final WeakReference<Window> held=new WeakReference<>(window);
                final View decor=window.getDecorView();
                final java.util.function.Consumer<Boolean> listener=on->{Window w=held.get();if(w==null)return;try{w.setBackgroundBlurRadius(on?radiusPx:0);}catch(Throwable ignored){}onChange.run();};
                manager.addCrossWindowBlurEnabledListener(decor.getContext().getMainExecutor(),listener);
                decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
                    public void onViewAttachedToWindow(View v){}
                    public void onViewDetachedFromWindow(View v){try{manager.removeCrossWindowBlurEnabledListener(listener);}catch(Throwable ignored){}v.removeOnAttachStateChangeListener(this);}
                });
            }
            return enabled;
        }catch(Throwable error){Diary.note("blur window: "+error);return false;}
    }

    /** Change the blur strength of a window already set up by {@link #window} (cheap: applied by RenderThread). */
    static void windowRadius(Window window,int radiusPx){
        if(window==null||!gpu())return;
        try{WindowManager manager=window.getWindowManager();window.setBackgroundBlurRadius(!plain()&&manager!=null&&manager.isCrossWindowBlurEnabled()?Math.max(0,radiusPx):0);}catch(Throwable ignored){}
    }

    static boolean windowEnabled(Window window){
        if(window==null||!gpu()||plain())return false;
        try{WindowManager manager=window.getWindowManager();return manager!=null&&manager.isCrossWindowBlurEnabled();}catch(Throwable ignored){return false;}
    }

    // ------------------------------------------------------------------ in-window backdrop (RenderNode + RenderEffect)

    /** Set while a backdrop records, so a drawable can skip painting itself into its own backdrop. */
    static boolean capturing;
    /** Once anything goes wrong the engine stays off for the session: no retry loops, no log spam. */
    private static boolean broken;

    static final class Backdrop {
        private final Object node;
        private float radius=-1f,corner=-1f;
        private int width,height,skip,frame,scale=SCALE,tint;
        /** Host px captured beyond each edge for Liquid Glass, so the bevel has something outside to bend in. */
        private float margin;
        private boolean liquid;
        private Object shader;
        /** Plain: what is behind at 1/{@code s} of its size with no effect of its own, for a shader that takes it as input (the island's glass). */
        private int plainScale;
        void plain(int s){plainScale=Math.max(1,s);}
        private long spent;private int samples;
        private boolean ready,off;
        private final int[] host=new int[2],at=new int[2];
        private final RectF area=new RectF();
        private final View[] chain=new View[48];

        private Backdrop(Object node){this.node=node;}

        /** Null where the engine cannot run; then the caller keeps its opaque surface. */
        static Backdrop create(){
            if(!gpu()||broken)return null;
            try{return new Backdrop(new android.graphics.RenderNode("ttcuz-backdrop"));}
            catch(Throwable error){broken=true;Diary.note("blur: "+error);return null;}
        }

        boolean ready(){return ready&&!off&&!broken;}

        /**
         * Record what lies behind {@code host} inside {@code rect} (host coordinates). Call from pre-draw only.
         * Returns whether a backdrop is available to draw this frame.
         */
        private long recordedAt;
        private static final long MIN_GAP=40_000_000L;
        boolean record(View host,RectF rect,float radiusPx){return record(host,rect,radiusPx,0f,false,0);}

        /**
         * As above; {@code glass} picks Liquid Glass (refraction shader, corner {@code cornerPx}, tinted with {@code tintArgb})
         * over the frosted blur. The effect is rebuilt only when one of those or the size changes, never per frame.
         */
        boolean record(View host,RectF rect,float radiusPx,float cornerPx,boolean glass,int tintArgb){
            if(off||broken||!gpu())return false;
            glass&=shaders()&&!lensBroken;
            if(skip>0&&(frame++%(skip+1))!=0)return ready;
            // What is behind the glass is blurred anyway: about 30 refreshes a second look the same as one per frame,
            // and on a 90/120 Hz screen they are a third or a quarter of the GPU work. A change of size or style
            // still records at once.
            long start=SystemClock.elapsedRealtimeNanos();
            if(ready&&start-recordedAt<MIN_GAP&&radiusPx==radius&&glass==liquid)return ready;
            // while another screen or a dialog opens over this one the window loses focus: the last picture is kept
            // instead of walking a view tree that is in the middle of a transition
            if(ready&&!host.hasWindowFocus()&&radiusPx==radius&&glass==liquid)return ready;
            recordedAt=start;
            try{
                android.graphics.RenderNode n=(android.graphics.RenderNode)node;
                int s=plainScale>0?plainScale:glass?LIQUID_SCALE:SCALE;
                // A two-pixel margin keeps the light pre-blur clean at the rim (the bevel samples inward, never outside).
                float m=glass?2f*s:0f;
                int w=Math.max(1,(int)Math.ceil((rect.width()+2*m)/s)),h=Math.max(1,(int)Math.ceil((rect.height()+2*m)/s));
                boolean resized=w!=width||h!=height||s!=scale||m!=margin;
                if(resized){n.setPosition(0,0,w,h);width=w;height=h;scale=s;margin=m;}
                if(resized||radiusPx!=radius||glass!=liquid||(glass&&(cornerPx!=corner||tintArgb!=tint))){
                    radius=radiusPx;liquid=glass;corner=cornerPx;tint=tintArgb;
                    n.setRenderEffect(plainScale>0?null:glass?liquidEffect(w,h,m/s,Math.min(rect.width(),rect.height())/s,cornerPx/s,radiusPx/s,tintArgb):blurEffect(Math.max(.5f,radiusPx/s)));
                }
                host.getLocationInWindow(this.host);
                area.set(this.host[0]+rect.left-margin,this.host[1]+rect.top-margin,this.host[0]+rect.right+margin,this.host[1]+rect.bottom+margin);
                int depth=0;View v=host;
                while(v!=null&&depth<chain.length){chain[depth++]=v;Object p=v.getParent();v=p instanceof View?(View)p:null;}
                if(depth<2||v!=null){ready=false;return false;}
                Canvas canvas=n.beginRecording(w,h);
                capturing=true;
                try{
                    canvas.scale(1f/scale,1f/scale);
                    View root=chain[depth-1];root.getLocationInWindow(at);
                    canvas.translate(at[0]-area.left,at[1]-area.top);
                    behind(canvas,depth-1);
                }finally{capturing=false;n.endRecording();}
                ready=true;
            }catch(Throwable error){
                broken=true;ready=false;Diary.note("blur: "+error);return false;
            }finally{for(int i=0;i<chain.length&&chain[i]!=null;i++)chain[i]=null;}
            budget(SystemClock.elapsedRealtimeNanos()-start);
            return ready;
        }

        /**
         * Keep the recording under ~2.5 ms on average by recording less often -- every 2nd, 3rd, up to every 5th frame --
         * and back to every frame once it is cheap again. It is never switched off: a burst of fast tab switches once
         * cost 3 ms a frame and the glass was gone until TikTok restarted.
         */
        private void budget(long nanos){
            spent+=nanos;if(++samples<30)return;
            long average=spent/samples;spent=0;samples=0;
            if(average>2_500_000L){if(skip<4){skip++;Diary.note("blur: recording every "+(skip+1)+" frames, "+average/1000+" us each");}}
            else if(average<800_000L&&skip>0)skip--;
        }

        /** Draws, at chain[level] (a group whose child chain[level-1] leads to the host), its background and the children below that path. */
        private void behind(Canvas canvas,int level){
            View group=chain[level];
            Drawable background=group.getBackground();
            // The host's own background is the surface being blurred: never part of its backdrop.
            if(background!=null&&level>0){
                int s=canvas.save();canvas.translate(group.getScrollX(),group.getScrollY());background.draw(canvas);canvas.restoreToCount(s);
            }
            if(level==0||!(group instanceof ViewGroup))return;
            ViewGroup parent=(ViewGroup)group;View next=chain[level-1];
            int count=parent.getChildCount();
            for(int i=0;i<count;i++){
                View child=parent.getChildAt(i);
                if(child==next){
                    int s=canvas.save();place(canvas,parent,child);behind(canvas,level-1);canvas.restoreToCount(s);
                    return;
                }
                if(child.getVisibility()!=View.VISIBLE||child.getAlpha()<.01f||!overlaps(child))continue;
                int s=canvas.save();place(canvas,parent,child);
                if(child.getAlpha()<.99f)canvas.saveLayerAlpha(0,0,child.getWidth(),child.getHeight(),Math.round(child.getAlpha()*255));
                picture(canvas,child,0);
                canvas.restoreToCount(s);
            }
        }

        /**
         * What a view shows under the glass: the video and the pictures, never the words over them -- the caption,
         * the author, the description sit at the bottom of the video, and the glass is to show the video's own bottom.
         * A video in a SurfaceView cannot be drawn, so its last frame is copied (PixelCopy).
         */
        private void picture(Canvas canvas,View view,int depth){
            if(view instanceof android.widget.TextView)return;
            if(view instanceof SurfaceView){frame(canvas,(SurfaceView)view);return;}
            if(!(view instanceof ViewGroup)||depth>14){view.draw(canvas);return;}
            Drawable background=view.getBackground();
            if(background!=null){int s=canvas.save();canvas.translate(view.getScrollX(),view.getScrollY());background.draw(canvas);canvas.restoreToCount(s);}
            ViewGroup group=(ViewGroup)view;
            for(int i=0,n=group.getChildCount();i<n;i++){
                View child=group.getChildAt(i);
                if(child.getVisibility()!=View.VISIBLE||child.getAlpha()<.01f||!overlaps(child))continue;
                int s=canvas.save();place(canvas,group,child);
                if(child.getAlpha()<.99f)canvas.saveLayerAlpha(0,0,child.getWidth(),child.getHeight(),Math.round(child.getAlpha()*255));
                picture(canvas,child,depth+1);
                canvas.restoreToCount(s);
            }
        }

        /** the video's last frame under the glass, copied small; two bitmaps, so one is drawn while the other fills */
        private final android.graphics.Bitmap[] frames=new android.graphics.Bitmap[2];
        private final android.graphics.Rect[] framed={new android.graphics.Rect(),new android.graphics.Rect()};
        private int shown=-1;private boolean copying;
        /** When the copy in flight was asked for, and which one it is: an answer that never came is given up after half a second. */
        private long copyingSince;private int copyNumber;

        private void frame(Canvas canvas,SurfaceView view){
            if(Build.VERSION.SDK_INT<26||view.getWidth()<=0||view.getHeight()<=0)return;
            view.getLocationInWindow(at);
            android.graphics.Rect want=new android.graphics.Rect(Math.max(0,Math.round(area.left)-at[0]),Math.max(0,Math.round(area.top)-at[1]),
                    Math.min(view.getWidth(),Math.round(area.right)-at[0]),Math.min(view.getHeight(),Math.round(area.bottom)-at[1]));
            if(want.width()<=0||want.height()<=0)return;
            if(shown>=0&&frames[shown]!=null&&!frames[shown].isRecycled())canvas.drawBitmap(frames[shown],null,framed[shown],null);
            // a copy asked for as the surface went away (the app to the background, the video changed) can go
            // unanswered: "copying" then stayed on, no copy was asked again and the glass kept the old frame for good
            if(copying&&SystemClock.uptimeMillis()-copyingSince>500)copying=false;
            if(copying||!view.getHolder().getSurface().isValid())return;
            final int next=shown==0?1:0;
            int w=Math.max(1,want.width()/Math.max(1,scale)),h=Math.max(1,want.height()/Math.max(1,scale));
            if(frames[next]==null||frames[next].getWidth()!=w||frames[next].getHeight()!=h)frames[next]=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);
            final android.graphics.Rect from=new android.graphics.Rect(want);copying=true;copyingSince=SystemClock.uptimeMillis();
            final int number=++copyNumber;
            try{
                android.view.PixelCopy.request(view,from,frames[next],result->{
                    // a late answer to a copy given up on: the newer one owns the flag and the bitmaps
                    if(number!=copyNumber)return;
                    copying=false;
                    if(result==android.view.PixelCopy.SUCCESS){framed[next].set(from);shown=next;}
                },new android.os.Handler(android.os.Looper.getMainLooper()));
            }catch(Throwable error){copying=false;}
        }

        private static void place(Canvas canvas,ViewGroup parent,View child){
            canvas.translate(child.getLeft()-parent.getScrollX(),child.getTop()-parent.getScrollY());
            android.graphics.Matrix matrix=child.getMatrix();if(!matrix.isIdentity())canvas.concat(matrix);
        }

        private boolean overlaps(View child){
            child.getLocationInWindow(at);
            return at[0]<area.right&&at[0]+child.getWidth()>area.left&&at[1]<area.bottom&&at[1]+child.getHeight()>area.top;
        }

        /** Paint the blurred backdrop into {@code rect} (host coordinates), clipped to {@code clip}. */
        void draw(Canvas canvas,RectF rect,Path clip){
            if(!ready()||!canvas.isHardwareAccelerated())return;
            try{
                android.graphics.RenderNode n=(android.graphics.RenderNode)node;if(!n.hasDisplayList())return;
                int s=canvas.save();canvas.clipPath(clip);canvas.translate(rect.left-margin,rect.top-margin);canvas.scale(scale,scale);canvas.drawRenderNode(n);canvas.restoreToCount(s);
            }catch(Throwable error){broken=true;ready=false;Diary.note("blur: "+error);}
        }

        boolean liquid(){return ready()&&liquid;}

        /** The plain recording at the host's scale, its top left at the canvas origin (see {@link #plain}). */
        void drawPlain(Canvas canvas){
            try{android.graphics.RenderNode n=(android.graphics.RenderNode)node;int s=canvas.save();canvas.scale(scale,scale);canvas.drawRenderNode(n);canvas.restoreToCount(s);}
            catch(Throwable error){broken=true;ready=false;Diary.note("blur: "+error);}
        }

        /** A video (a SurfaceView) was behind at the last recording: its frames only reach the glass when it is recorded again. */
        boolean video(){return shown>=0;}

        private static android.graphics.RenderEffect blurEffect(float r){return android.graphics.RenderEffect.createBlurEffect(r,r,android.graphics.Shader.TileMode.CLAMP);}

        /** A light pre-blur (the glass is not perfectly clear), then the lens shader on top of it. */
        /** Bevel width as a share of the glass's short side, and the slab thickness as a share of the bevel. */
        static final float BEVEL=.42f,THICKNESS=.75f;
        /** The slab is a lens as a whole too: what is under it shows this much smaller-to-larger (1/zoom magnification). */
        static final float ZOOM=.88f;

        private android.graphics.RenderEffect liquidEffect(int w,int h,float insetPx,float shortSide,float cornerPx,float softPx,int tintArgb){
            android.graphics.RuntimeShader lens=(android.graphics.RuntimeShader)shader;
            if(lens==null){
                try{lens=new android.graphics.RuntimeShader(GLASS_SHADER);shader=lens;}
                catch(Throwable error){
                    lensBroken=true;liquid=false;Diary.note("liquid glass: "+error);return blurEffect(Math.max(.5f,softPx*2f));
                }
            }
            // a thick rim, a third of the short side, that bends the picture by up to one and a half bezels at the edge,
            // and the whole slab magnifying (zoom under 1): glass you see is glass, not a sheet of film
            float r=Math.min(cornerPx,shortSide/2f),bezel=Math.max(4f,shortSide*.34f);
            lens.setFloatUniform("size",w,h);lens.setFloatUniform("inset",insetPx);lens.setFloatUniform("radius",r);
            lens.setFloatUniform("bezel",bezel);lens.setFloatUniform("thickness",bezel*.6f);lens.setFloatUniform("zoom",.93f);
            lens.setFloatUniform("tint",((tintArgb>>16)&255)/255f,((tintArgb>>8)&255)/255f,(tintArgb&255)/255f,((tintArgb>>>24)&255)/255f);
            // real glass is clear: the picture gets only a breath of softness (the capture is already at a third)
            float soft=Math.max(1f,softPx*1.2f);
            android.graphics.RenderEffect inner=android.graphics.RenderEffect.createBlurEffect(soft,soft,android.graphics.Shader.TileMode.CLAMP);
            return android.graphics.RenderEffect.createChainEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(lens,"content"),inner);
        }

        void release(){ready=false;try{((android.graphics.RenderNode)node).discardDisplayList();}catch(Throwable ignored){}}
    }
}
