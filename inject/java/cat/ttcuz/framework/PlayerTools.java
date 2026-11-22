package cat.ttcuz.framework;
import android.content.Context;import java.lang.ref.WeakReference;
/** Native player manager operations; preserve normal playback and configure only hold acceleration. */
public final class PlayerTools {
 static final String SPEED="player_default_speed",STEP="player_seek_step",REVERSE="player_reverse_rate";
 private static WeakReference<Object> current=new WeakReference<Object>(null);
 /** The player of the video now playing, or null. */
 static Object player(){return current.get();}
 private static String aid="";private static Runnable rewind;private static final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
 static float speed(){try{float v=AccountAppearance.prefs(Ttcuz.context()).getFloat(SPEED,2f);return validSpeed(v)?v:2f;}catch(Throwable ignored){return 2f;}}
 static boolean validSpeed(float v){return !Float.isNaN(v)&&!Float.isInfinite(v)&&v>=.5f&&v<=3f;}
 static int step(){try{return Math.max(1,Math.min(60,AccountAppearance.prefs(Ttcuz.context()).getInt(STEP,10)));}catch(Throwable ignored){return 10;}}
 static float reverse(){try{float v=AccountAppearance.prefs(Ttcuz.context()).getFloat(REVERSE,1f);return validSpeed(v)?v:1f;}catch(Throwable ignored){return 1f;}}
 /** The video playing now (its Aweme), for what wants to know what is on screen: its sound, for one. */
 private static WeakReference<Object> post=new WeakReference<Object>(null);
 static Object post(){return post.get();}
 /**
  * TikTok's feed player manager, by version: 47.7.3 has it as X/03Yp (got by LJIILIIL(), tryPausePlay LJLI(Z),
  * resumePlay LJIILLIIL()), 47.2.41 as X/0qWW (LJIIL(), LJLJI(Z), LJIILLIIL()). {manager, pause method, resume method}.
  */
 private static final String[][] PLAYERS={{"X.03Yp","LJIILIIL","LJLI"},{"X.0qWW","LJIIL","LJLJI"}};
 static Object[] feedPlayer(){
  for(String[] known:PLAYERS)try{
   Class<?> type=Class.forName(known[0]);Object manager=type.getMethod(known[1]).invoke(null);if(manager==null)continue;
   return new Object[]{manager,manager.getClass().getMethod(known[2],boolean.class),manager.getClass().getMethod("LJIILLIIL")};
  }catch(Throwable ignored){}
  return null;
 }
 /** The feed's video paused through TikTok's own player manager: the profile music starts, or the lock covers TikTok. */
 static boolean pauseFeed(){
  try{Object[] player=feedPlayer();if(player==null){Diary.note("pause feed: no player");return false;}
   ((java.lang.reflect.Method)player[1]).invoke(player[0],false);return true;}
  catch(Throwable error){Diary.note("pause feed: "+error);return false;}
 }
 /** The feed's video played again. */
 static void resumeFeed(){
  try{Object[] player=feedPlayer();if(player!=null)((java.lang.reflect.Method)player[2]).invoke(player[0]);}
  catch(Throwable error){Diary.note("resume feed: "+error);}
 }
 public static void resumed(Object controller,String eventAid){
  try{
   Object post=NativeRead.get(controller,"LLJJIJIL");String next=NativeRead.aid(post);if(next.length()==0 || eventAid==null || !next.equals(eventAid))return;
   PlayerTools.post=new WeakReference<Object>(post);
   ProfileMusic.feedPlays();
   Object manager=NativeRead.get(controller,"getPlayerManager");if(manager==null)return;
   current=new WeakReference<Object>(manager);
   AdWords.playing(controller,post,next,manager);
   VideoResume.playing(next,manager);
   // behind the lock nothing plays: the feed starting its first video after the cover went up is paused again
   if(AppLock.covering())AppLock.still(true);
   if(next.equals(aid))return;
   aid=next;cancelSmooth();
  }catch(Throwable error){Diary.note("default speed: "+error);}
 }
 static void accountChanged(){aid="";current=new WeakReference<Object>(null);cancelSmooth();}
 static void setSpeed(float value){
  if(!validSpeed(value)){Screen.say(Text.SPEED_RANGE);return;}
  cancelSmooth();AccountAppearance.prefs(Ttcuz.context()).edit().putFloat(SPEED,value).apply();
  SpeedGesture.refreshRate();
 }
 /** The held video's speed: set where TikTok sets its own 2× (X.03Yp.LJIILIIL().LIZ(F), as EdgeSpeedupAssem does);
  *  the player seen last (current) only when that is missing -- it was often not known yet, and the hold stayed 2×. */
 static boolean applyHoldRate(){
  Object[] player=feedPlayer();Object manager=player!=null?player[0]:current.get();if(manager==null){Diary.note("hold speed: no player");return false;}
  try{manager.getClass().getMethod("LIZ",float.class).invoke(manager,speed());return true;}
  catch(Throwable error){try{NativeRead.floating(manager,"LIZ",speed());return true;}catch(Throwable again){Diary.note("hold speed: "+again);return false;}}
 }
 static void choose(Context c){choose(c,null);}
 static void choose(Context c,Runnable changed){
  new ModDialog.Builder(c).setItems(new String[]{"1×","1.5×","2×",Text.CUSTOM_SPEED},(dialog,index)->{
   if(index<3){setSpeed(new float[]{1f,1.5f,2f}[index]);if(changed!=null)changed.run();return;}
   Panel p=Panel.with(c,Skin.remembered(c),Text.PLAYER_SPEED);android.widget.EditText input=p.field("0.5–3×");input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);input.setText(Float.toString(speed()));
   p.primaryKeepOpen(Text.APPLY,()->{try{float v=Float.parseFloat(input.getText().toString().replace(',','.'));if(!validSpeed(v)){Screen.say(Text.SPEED_RANGE);return;}setSpeed(v);p.close();if(changed!=null)changed.run();}catch(Throwable ignored){Screen.say(Text.SPEED_RANGE);}});p.quiet(Text.CLOSE,null);p.show();
  }).show();
 }
 static void seek(boolean forward,boolean smooth){
  final Object manager=current.get();if(manager==null){Screen.say(Text.PLAYER_NOT_READY);return;}
  final long duration=NativeRead.number(NativeRead.get(manager,"getDuration")),position=NativeRead.number(NativeRead.get(manager,"getCurrentPosition"));
  if(duration<=0 || position<0){Screen.say(Text.PLAYER_NOT_READY);return;}
  final long target=Math.max(0,Math.min(duration-1,position+(forward?1:-1)*step()*1000L));cancelSmooth();
  if(!smooth){seekAt(manager,target,duration);return;}
  final String video=aid;final long began=android.os.SystemClock.uptimeMillis();final long length=Math.max(1,(long)(Math.abs(target-position)/reverse()));
  Runnable task=new Runnable(){public void run(){
   if(rewind!=this)return;
   if(!video.equals(aid)||current.get()!=manager){cancelSmooth();return;}
   long elapsed=Math.max(0,android.os.SystemClock.uptimeMillis()-began);double progress=Math.min(1d,(double)elapsed/length);
   seekAt(manager,position+(long)((target-position)*progress),duration);
   if(rewind!=this)return;if(progress>=1d)rewind=null;else handler.postDelayed(this,140L);
  }};rewind=task;handler.post(task);
 }

 static float percent(long position,long duration){return duration<=0?0f:Math.max(0f,Math.min(100f,100f*position/duration));}
 private static void seekAt(Object manager,long position,long duration){try{NativeRead.floating(manager,"seek",percent(position,duration));}catch(Throwable error){cancelSmooth();Diary.note("seek: "+error);Screen.say(Text.PLAYER_NOT_READY);}}
 static void cancelSmooth(){Runnable active=rewind;rewind=null;if(active!=null)handler.removeCallbacks(active);}
}
