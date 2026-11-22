package cat.ttcuz.framework;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.ref.WeakReference;

/** The crash-report question first, then eight short steps over the menu itself, each pointing at what to tap. */
final class Tutorial {
    private static final String DONE = "tutorial_done_v3";
    private static WeakReference<Coach> open = new WeakReference<Coach>(null);
    private static WeakReference<Activity> owner = new WeakReference<Activity>(null);
    private static final String[] TITLES = Text.TUTORIAL_TITLES;
    private static final String[] WORDS = Text.TUTORIAL_WORDS;
    private Tutorial() {}

    static void maybeShow(Activity activity) {
        String name = activity.getClass().getName();
        if (!"com.ss.android.ugc.aweme.main.MainActivity".equals(name)
                && !SettingsRow.SETTINGS_ACTIVITY.equals(name)
                && !(activity instanceof SettingsActivity)) return;
        if (finished(activity) && CrashConsent.asked(activity)) return;
        WeakReference<Activity> target = new WeakReference<Activity>(activity);
        activity.getWindow().getDecorView().postDelayed(() -> {
            Activity current=target.get();
            if (current == null || current != Screen.now() || current.isFinishing() || current.isDestroyed()
                    || !current.getWindow().getDecorView().hasWindowFocus()) return;
            if (!CrashConsent.asked(current)) { CrashConsent.show(current,() -> afterConsent(current)); return; }
            if (!finished(current)) offer(current);
        },900L);
    }

    private static boolean finished(Activity activity) {
        return activity.getSharedPreferences(Ttcuz.PREFS,Context.MODE_PRIVATE).getBoolean(DONE,false);
    }

    /** The question is answered; the walkthrough follows unless it was seen before. */
    private static void afterConsent(Activity activity) {
        if (finished(activity) || activity.isFinishing() || activity.isDestroyed()) return;
        offer(activity);
    }

    /** Asked, never pushed: "Not now" is final, and the tour stays in the mod's About section. */
    static void offer(Activity activity) {
        activity.getWindow().getDecorView().postDelayed(() -> {
            if (activity.isFinishing() || activity.isDestroyed() || finished(activity)) return;
            Popup.ask(activity, Text.t("Пройти обучение?"),
                    Text.t("Восемь коротких шагов: покажу, куда нажимать в меню мода. Обучение всегда можно открыть в «О проекте»."),
                    Text.t("Пройти"), () -> show(activity),
                    Text.t("Не сейчас"), () -> finish(activity),
                    null, null);
        }, 250L);
    }

    static void show(Activity activity) {
        if(!(activity instanceof SettingsActivity)) {
            activity.startActivity(new android.content.Intent(activity,SettingsActivity.class).putExtra("ttcuz_tutorial",true));
            return;
        }
        Coach previous=open.get();
        if (previous != null && previous.isAttachedToWindow()) return;
        if (activity.isFinishing() || activity.isDestroyed()) return;
        Coach coach=new Coach((SettingsActivity)activity);
        ((ViewGroup)activity.getWindow().getDecorView()).addView(coach,new ViewGroup.LayoutParams(-1,-1));
        open=new WeakReference<Coach>(coach); owner=new WeakReference<Activity>(activity);
        coach.go(0);
    }

    /** The tour is on screen in this activity: its touches and "back" are the tour's. */
    static boolean showing(Activity activity) {
        Coach coach=open.get();
        return coach!=null && owner.get()==activity && coach.isAttachedToWindow();
    }

    /** "Back" during the tour: one step back, or out of it from the first. */
    static boolean back(Activity activity) {
        if (!showing(activity)) return false;
        Coach coach=open.get();
        if (coach.step>0) coach.go(coach.step-1); else { finish(activity); coach.close(); }
        return true;
    }

    /** What each step points at: a home tile (its group), a row's label or a button's description; null: nothing. */
    private static Object target(int step) {
        switch (step) {
            case 1: return 1;
            case 2: return 2;
            case 3: return 3;
            case 4: return 7;
            case 5: return Text.t("Фейк огонёк");
            case 6: return 8;
            case 7: return Text.t("Поиск по настройкам");
            default: return null;
        }
    }

    /** The view a step points at, its row when it is a label inside one; null while it is not built yet. */
    private static View find(View view, Object what, View skip) {
        if (view==skip || view.getVisibility()!=View.VISIBLE) return null;
        boolean hit = what instanceof Integer ? what.equals(view.getTag())
                : what.equals(view.getContentDescription() == null ? null : view.getContentDescription().toString())
                  || (view instanceof TextView && what.equals(((TextView)view).getText().toString()));
        if (hit) {
            View row=view;
            for (int up=0; up<3 && !row.isClickable() && row.getParent() instanceof View; up++) row=(View)row.getParent();
            return row.isClickable() ? row : view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) { View found=find(group.getChildAt(i),what,skip); if (found!=null) return found; }
        }
        return null;
    }

    /**
     * The tour over the menu itself: the screen dimmed except for the thing to tap, which a ring pulses around, and a
     * card with the words beside it. The cut-out follows its target every frame, so it stays on it through the page's
     * own slide-in and any rebuild. A tap on the lit spot is "Next".
     */
    private static final class Coach extends FrameLayout {
        private final SettingsActivity screen;
        private final float d;
        private final Paint dim=new Paint(Paint.ANTI_ALIAS_FLAG), ring=new Paint(Paint.ANTI_ALIAS_FLAG), dot=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shade=new Path();
        private final RectF hole=new RectF(), aim=new RectF(), ringAt=new RectF();
        private final int[] at=new int[2], mine=new int[2];
        private final LinearLayout card;
        private final View[] bars=new View[TITLES.length];
        private final android.widget.ImageView picture;
        private final TextView title, body, counter, next, back;
        private final ValueAnimator clock=ValueAnimator.ofFloat(0f,1f);
        private WeakReference<View> pointed=new WeakReference<View>(null);
        private float pulse, shown;
        private boolean aimed;
        int step;

        Coach(SettingsActivity screen) {
            super(screen);
            this.screen=screen;
            d=screen.getResources().getDisplayMetrics().density;
            Skin skin=Skin.remembered(screen);
            int accent=Accent.colour();
            setWillNotDraw(false); setClickable(true); setFocusable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            dim.setColor(0xB8000000);
            ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(3*d); ring.setColor(accent);
            dot.setColor(0xFFFFFFFF);

            card=new LinearLayout(screen); card.setOrientation(LinearLayout.VERTICAL);
            int pad=Math.round(20*d); card.setPadding(pad,Math.round(16*d),pad,Math.round(12*d));
            GradientDrawable face=new GradientDrawable(); face.setColor(skin.card); face.setCornerRadius(24*d);
            card.setBackground(face); card.setElevation(12*d); card.setClickable(true);
            LinearLayout progress=new LinearLayout(screen);
            for (int i=0;i<bars.length;i++) {
                View bar=new View(screen); bars[i]=bar;
                LinearLayout.LayoutParams barAt=new LinearLayout.LayoutParams(0,Math.round(3*d),1f);
                if (i>0) barAt.leftMargin=Math.round(4*d);
                progress.addView(bar,barAt);
            }
            card.addView(progress,new LinearLayout.LayoutParams(-1,-2));
            LinearLayout head=new LinearLayout(screen); head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(0,Math.round(14*d),0,Math.round(6*d));
            picture=new android.widget.ImageView(screen);
            head.addView(picture,new LinearLayout.LayoutParams(Math.round(28*d),Math.round(28*d)));
            title=new TextView(screen); title.setTextColor(skin.text); title.setTextSize(20);
            title.setTypeface(Typeface.create("sans-serif-medium",0)); title.setPadding(Math.round(10*d),0,0,0);
            title.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            head.addView(title,new LinearLayout.LayoutParams(0,-2,1f));
            counter=new TextView(screen); counter.setTextColor(skin.muted()); counter.setTextSize(12);
            head.addView(counter);
            card.addView(head);
            body=new TextView(screen); body.setTextColor(skin.muted()); body.setTextSize(15); body.setLineSpacing(0,1.2f);
            card.addView(body);
            LinearLayout actions=new LinearLayout(screen); actions.setGravity(Gravity.CENTER_VERTICAL);
            actions.setPadding(0,Math.round(12*d),0,0);
            TextView skip=button(Text.TUTORIAL_SKIP,skin.muted(),0);
            skip.setOnClickListener(v -> { finish(screen); close(); });
            actions.addView(skip);
            actions.addView(new View(screen),new LinearLayout.LayoutParams(0,1,1f));
            back=button(Text.TUTORIAL_BACK,skin.text,0);
            back.setOnClickListener(v -> { if (step>0) go(step-1); });
            actions.addView(back);
            next=button(Text.TUTORIAL_NEXT,Color.luminance(accent)>0.5f?0xFF000000:0xFFFFFFFF,accent);
            next.setOnClickListener(v -> advance());
            LinearLayout.LayoutParams nextAt=new LinearLayout.LayoutParams(-2,-2); nextAt.leftMargin=Math.round(8*d);
            actions.addView(next,nextAt);
            card.addView(actions);
            int side=Math.round(16*d);
            LayoutParams cardAt=new LayoutParams(-1,-2); cardAt.leftMargin=side; cardAt.rightMargin=side;
            addView(card,cardAt);

            clock.setDuration(1400L); clock.setRepeatCount(ValueAnimator.INFINITE);
            clock.addUpdateListener(a -> { pulse=(float)a.getAnimatedValue(); follow(); invalidate(); });
        }

        private TextView button(String words,int colour,int fill) {
            TextView button=new TextView(getContext()); button.setText(words); button.setTextColor(colour); button.setTextSize(15);
            button.setTypeface(Typeface.create("sans-serif-medium",0)); button.setGravity(Gravity.CENTER);
            button.setMinHeight(Math.round(44*d)); button.setPadding(Math.round(16*d),0,Math.round(16*d),0);
            if (fill!=0) { GradientDrawable pill=new GradientDrawable(); pill.setColor(fill); pill.setCornerRadius(22*d); button.setBackground(pill); }
            return button;
        }

        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); clock.start(); requestFocus(); }
        @Override protected void onDetachedFromWindow() { clock.cancel(); super.onDetachedFromWindow(); }

        void advance() {
            if (step==TITLES.length-1) { finish(screen); close(); }
            else go(step+1);
        }

        void close() {
            clock.cancel();
            if (getParent() instanceof ViewGroup) ((ViewGroup)getParent()).removeView(this);
            if (open.get()==this) { open.clear(); owner.clear(); }
        }

        void go(int to) {
            int from=step; step=to;
            screen.tutorialStep(step);
            int accent=Accent.colour();
            counter.setText((step+1)+" / "+TITLES.length);
            for (int i=0;i<bars.length;i++) {
                GradientDrawable fill=new GradientDrawable(); fill.setCornerRadius(2*d);
                fill.setColor(i<=step?accent:(Skin.remembered(screen).text&0xFFFFFF)|0x26000000);
                bars[i].setBackground(fill);
            }
            android.graphics.drawable.Drawable glyph=SettingsGlyph.make(Text.TUTORIAL_ICONS[step],accent);
            picture.setImageDrawable(glyph); picture.setVisibility(glyph==null?View.GONE:View.VISIBLE);
            title.setText(TITLES[step]); body.setText(WORDS[step]);
            next.setText(step==TITLES.length-1?Text.TUTORIAL_DONE:Text.TUTORIAL_NEXT);
            back.setVisibility(step==0?View.GONE:View.VISIBLE);
            pointed.clear(); aimed=false;
            if (Motion.enabled(screen) && from!=to) { card.setAlpha(0f); card.setTranslationX((to>from?1:-1)*24*d); }
            card.animate().alpha(1f).translationX(0f).setDuration(220L).start();
            invalidate();
        }

        /** Where the target is now, and the card on the side of it with more room. */
        private void follow() {
            Object what=target(step);
            if (what==null) { aimed=false; placeCard(); return; }
            View view=pointed.get();
            if (view==null || !view.isAttachedToWindow()) {
                view=find(getRootView(),what,this);
                if (view==null || view.getWidth()==0) return;
                pointed=new WeakReference<View>(view);
                int room=Math.round(200*d);
                view.requestRectangleOnScreen(new android.graphics.Rect(0,-room,view.getWidth(),view.getHeight()+room),true);
            }
            view.getLocationInWindow(at); getLocationInWindow(mine);
            float pad=6*d, x=at[0]-mine[0], y=at[1]-mine[1];
            aim.set(x-pad,y-pad,x+view.getWidth()+pad,y+view.getHeight()+pad);
            if (!aimed) { hole.set(aim.centerX(),aim.centerY(),aim.centerX(),aim.centerY()); aimed=true; }
            float k=.25f;
            hole.set(hole.left+(aim.left-hole.left)*k,hole.top+(aim.top-hole.top)*k,hole.right+(aim.right-hole.right)*k,hole.bottom+(aim.bottom-hole.bottom)*k);
            placeCard();
        }

        private void placeCard() {
            float gap=16*d, h=card.getHeight(), y;
            if (!aimed) y=(getHeight()-h)/2f;
            else {
                // under the target when it is in the top half, over it otherwise
                y=aim.centerY()<getHeight()/2f?hole.bottom+gap:hole.top-gap-h;
                y=Math.max(gap,Math.min(getHeight()-h-gap,y));
            }
            if (Math.abs(card.getY()-y)>.5f) card.setY(y);
        }

        @Override protected void onDraw(Canvas canvas) {
            shade.reset(); shade.setFillType(Path.FillType.EVEN_ODD);
            shade.addRect(0,0,getWidth(),getHeight(),Path.Direction.CW);
            if (aimed) {
                float r=Math.min(22*d,hole.height()/2f);
                shade.addRoundRect(hole,r,r,Path.Direction.CW);
                canvas.drawPath(shade,dim);
                float grow=pulse*14*d;
                ringAt.set(hole.left-grow,hole.top-grow,hole.right+grow,hole.bottom+grow);
                ring.setAlpha(Math.round(255*(1f-pulse)));
                canvas.drawRoundRect(ringAt,r+grow,r+grow,ring);
                // a finger's tap: a dot that presses in and lifts, in the middle of what to tap
                float press=pulse<.5f?pulse*2f:(1f-pulse)*2f;
                dot.setAlpha(Math.round(110+90*press));
                canvas.drawCircle(hole.centerX(),hole.centerY(),(10+6*press)*d,dot);
            } else canvas.drawPath(shade,dim);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked()==MotionEvent.ACTION_UP && aimed && hole.contains(event.getX(),event.getY())) advance();
            return true;
        }

        @Override public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.getKeyCode()==KeyEvent.KEYCODE_BACK) {
                if (event.getAction()==KeyEvent.ACTION_UP) back(screen);
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
    }

    private static void finish(Activity activity) {
        activity.getSharedPreferences(Ttcuz.PREFS,Context.MODE_PRIVATE).edit().putBoolean(DONE,true).apply();
    }

    static void paused(Activity activity) {
        CrashConsent.paused(activity);
        if (owner.get()!=activity) return;
        Coach coach=open.get();
        if (coach!=null) coach.close();
        open.clear(); owner.clear();
    }
}
