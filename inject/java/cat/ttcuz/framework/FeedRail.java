package cat.ttcuz.framework;
import android.view.View;
import java.util.Map;
import java.util.WeakHashMap;
/** Native right-area slots only. No frame callbacks, tree walks or removed views. */
public final class FeedRail {
    public static final String[] KEYS={"rail_show_like","rail_show_comment","rail_show_fav","rail_show_share"};
    private static final Map<View,State> slots=new WeakHashMap<View,State>();
    /** The rails on screen now (for presence indicators drawn on them). */
    static java.util.List<View> views(){synchronized(slots){return new java.util.ArrayList<View>(slots.keySet());}}
    private static volatile boolean[] shown;
    private static final class State {final int kind;int wanted;java.lang.ref.WeakReference<Object> owner=new java.lang.ref.WeakReference<Object>(null);State(int k,int v){kind=k;wanted=v;}}
    private FeedRail(){}
    public static boolean shown(int kind){
        boolean[] current=shown;
        if(current==null){
            android.content.Context c=Ttcuz.context();if(c==null)return true;
            current=new boolean[]{true,true,true,true};
            try{android.content.SharedPreferences p=AccountAppearance.prefs(c);for(int i=0;i<4;i++)current[i]=p.getBoolean(KEYS[i],true);}
            catch(Throwable error){Diary.note("feed rail settings: "+error);}
            shown=current;
        }
        return kind<0 || kind>=current.length || current[kind];
    }
    public static void setShown(int kind,boolean value){
        if(kind<0 || kind>=4)return;
        android.content.Context c=Ttcuz.context();if(c==null)return;
        AccountAppearance.prefs(c).edit().putBoolean(KEYS[kind],value).apply();reloadSettings();
    }
    static void reloadSettings(){
        shown=null;
        java.util.List<View> views;
        synchronized(slots){views=new java.util.ArrayList<View>(slots.keySet());}
        for(View v:views)if(v!=null)v.post(()->apply(v));
    }
    public static void bindNative(Object component){
        if(component==null)return;
        String name=component.getClass().getName();int kind;
        if(name.equals("com.ss.android.ugc.aweme.feed.assem.digg.VideoDiggAssem"))kind=0;
        else if(name.equals("com.ss.android.ugc.aweme.feed.assem.videocomment.VideoCommentAssem"))kind=1;
        else if(name.equals("com.ss.android.ugc.aweme.feed.favorite.VideoFavoriteAssem"))kind=2;
        else if(name.equals("com.ss.android.ugc.aweme.feed.assem.share.VideoShareAssem"))kind=3;
        else return;
        try{
            Object content=component.getClass().getMethod("getContentView").invoke(component);
            if(!(content instanceof View))return;
            View v=(View)content;
            synchronized(slots){State state=slots.get(v);if(state==null)slots.put(v,state=new State(kind,v.getVisibility()));state.owner=new java.lang.ref.WeakReference<Object>(component);}
            apply(v);
            if(kind==0)StoryLikes.bind(component);
            if(kind==3)VideoActions.bindNative(component);
            if(kind==2)Repost.anchor(component);
        }catch(Throwable error){Diary.note("feed rail binding: "+error);}
    }
    /** Preserve TikTok's latest intended state, including server-hidden actions. */
    public static void setVisibility(View view,int visibility){
        if(view==null)return;
        if(Repost.visibility(view,visibility))return;
        if(FeedButtons.visibility(view,visibility))return;
        State state;
        synchronized(slots){state=slots.get(view);if(state!=null)state.wanted=visibility;}
        if(state==null){view.setVisibility(visibility);return;}
        int next=next(view,state);view.setVisibility(next);RailSwap.face(view,state.kind,next==View.INVISIBLE&&RailSwap.swapped(state.kind));
    }
    /** What a slot shows: hidden in the settings, made room for (a repost, a swapped button), or TikTok's own wish. */
    private static int next(View view,State state){
        if(state.kind==2&&Repost.replacing(view))return View.INVISIBLE;
        if(!shown(state.kind))return View.GONE;
        return state.wanted==View.VISIBLE&&RailSwap.swapped(state.kind)?View.INVISIBLE:state.wanted;
    }
    /** The component (TikTok's assem) a slot belongs to now, to read its video from. */
    static Object owner(View view){synchronized(slots){State state=slots.get(view);return state==null?null:state.owner.get();}}
    static int kindOf(View view){synchronized(slots){State state=slots.get(view);return state==null?-1:state.kind;}}
    /** The slot under a point of the screen, a swapped (invisible) one too; null when none is there. */
    static View slotAt(float x,float y){
        synchronized(slots){for(View v:slots.keySet())if(v!=null&&v.getVisibility()!=View.GONE&&v.getParent() instanceof View&&((View)v.getParent()).isShown()&&v.isAttachedToWindow()){
            v.getLocationOnScreen(at);if(x>=at[0]&&x<at[0]+v.getWidth()&&y>=at[1]&&y<at[1]+v.getHeight())return v;}}
        return null;
    }
    private static final int[] at=new int[2];
    /** The slot of a kind beside another one (the same video's rail), or null. */
    static View sibling(View near,int kind){
        synchronized(slots){for(Map.Entry<View,State> e:slots.entrySet()){View v=e.getKey();if(v!=null&&e.getValue().kind==kind&&v.getParent()==near.getParent())return v;}}
        return null;
    }
    /** Which shown rail button (0 like, 1 comments, 2 favourites, 3 share) is under a point of the screen, or -1. */
    static int kindAt(float x,float y){
        synchronized(slots){for(Map.Entry<View,State> e:slots.entrySet()){View v=e.getKey();if(v!=null&&FeedButtons.contains(v,x,y))return e.getValue().kind;}}
        return -1;
    }
    static void refreshFavorite(View view){if(view!=null)apply(view);}
    private static void apply(View view){
        State state;synchronized(slots){state=slots.get(view);}
        if(state==null)return;
        int next=next(view,state);
        if(view.getVisibility()!=next)view.setVisibility(next);
        RailSwap.face(view,state.kind,next==View.INVISIBLE&&RailSwap.swapped(state.kind));
    }
}
