package cat.ttcuz.framework;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.Window;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The window's touch hook of the main screens: the features that must see a touch before TikTok does (opening a
 * video, the Reposts selection, the feed's buttons) and then the bottom island, which takes the touches that start on
 * its bar. Anything nobody takes goes on to TikTok untouched.
 */
final class IslandTouch {
    /**
     * window -> the hook put on it last; checked on every resume, since a callback set again drops it. Only that hook
     * acts: an older one still in the chain (wrapped by whoever set the new callback) just hands every touch on.
     */
    private static final Map<Window,java.lang.ref.WeakReference<Window.Callback>> hooked=new WeakHashMap<>();
    private static Window.Callback current(Window window){synchronized(hooked){java.lang.ref.WeakReference<Window.Callback> r=window==null?null:hooked.get(window);return r==null?null:r.get();}}
    private IslandTouch() {}

    static void hook(final Activity activity) {
        final Window window=activity.getWindow();if(window==null)return;
        final Window.Callback base=window.getCallback();if(base==null)return;
        // left and come back to, the window can have had its callback set anew: the hook was gone with it, and the
        // island took no hold or drag any more (taps still reached TikTok's own row under it)
        if(current(window)==base)return;
        final java.util.function.Consumer<MotionEvent> deliver=ev->base.dispatchTouchEvent(ev);
        // weakly, here and in the map: the hook holds the activity and so its window, the map's key
        final java.lang.ref.WeakReference<Window> mine=new java.lang.ref.WeakReference<>(window);
        Window.Callback hook=(Window.Callback)Proxy.newProxyInstance(base.getClass().getClassLoader(),new Class<?>[]{Window.Callback.class},(proxy,method,args)->{
            if(args!=null&&args.length==1&&args[0] instanceof MotionEvent&&method.getName().equals("dispatchTouchEvent")&&current(mine.get())==proxy) {
                try{VideoOpenTransition.touch(activity,(MotionEvent)args[0]);}
                catch(Throwable error){VideoOpenTransition.clear(activity);Diary.note("video touch: "+error);}
                // the Reposts tab's selection (hold a card); a taken touch never reaches TikTok
                try{if(RepostSelect.touch(activity,(MotionEvent)args[0],deliver))return Boolean.TRUE;}
                catch(Throwable error){RepostSelect.stop();Diary.note("repost select: "+error);}
                // swapped feed buttons, and a hold on one to choose what it does
                try{if(RailSwap.touch(activity,(MotionEvent)args[0],deliver))return Boolean.TRUE;}
                catch(Throwable error){Diary.note("rail swap touch: "+error);}
                // the feed's buttons: holds that do nothing, a second tap to follow, the avatar to the profile
                try{if(FeedButtons.touch(activity,(MotionEvent)args[0],deliver))return Boolean.TRUE;}
                catch(Throwable error){Diary.note("feed buttons: "+error);}
                // the island: a touch on its bar is its own (a chosen tab is then tapped on TikTok's row through base)
                try{if(NavigationIsland.touch(activity,(MotionEvent)args[0],base))return Boolean.TRUE;}
                catch(Throwable error){Diary.note("island touch: "+error);}
            }
            try{return method.invoke(base,args);}catch(InvocationTargetException x){throw x.getCause();}
        });
        synchronized(hooked){hooked.put(window,new java.lang.ref.WeakReference<>(hook));}
        window.setCallback(hook);
    }
}
