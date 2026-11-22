package cat.ttcuz.framework;
/** Account-owned author exclusions, bounded to 50 public UIDs. */
final class FeedBlacklist {
 static final String KEY="feed_blocked_authors",CARDS="feed_blocked_cards",WORDS="feed_blocked_words";
 private static volatile java.util.List<String> wordCache;
 private static volatile java.util.Map<Integer,String> cardCache;
 private static volatile java.util.Map<String,String> cached;
 static java.util.Map<String,String> items(){
  java.util.Map<String,String> known=cached;if(known!=null)return known;
  if(Ttcuz.context()==null)return java.util.Collections.emptyMap();
  java.util.Map<String,String> result=new java.util.LinkedHashMap<>();
  try{
   String raw=AccountAppearance.prefs(Ttcuz.context()).getString(KEY,"");
   if(raw.length()<=16384)for(String line:raw.split("\n")){int split=line.indexOf('\t');if(split>0 && result.size()<50){String uid=line.substring(0,split);if(Ids.uid(uid))result.put(uid,line.substring(split+1));}}
  }catch(Throwable ignored){}
  cached=java.util.Collections.unmodifiableMap(result);return cached;
 }
 static java.util.Map<Integer,String> cards(){java.util.Map<Integer,String> result=cardCache;if(result!=null)return result;result=new java.util.LinkedHashMap<>();if(Ttcuz.context()==null)return result;try{String raw=AccountAppearance.prefs(Ttcuz.context()).getString(CARDS,"");if(raw.length()<=8192)for(String line:raw.split("\n")){int split=line.indexOf('\t');if(split<=0||result.size()>=32)continue;int type=Integer.parseInt(line.substring(0,split));if(type>0&&type<=100000)result.put(type,line.substring(split+1));}}catch(Throwable ignored){}cardCache=java.util.Collections.unmodifiableMap(result);return cardCache;}
 static void addCard(Object post){long type=NativeRead.number(NativeRead.get(post,"getRecommendCardType"));if(type<=0||type>100000){Screen.say(Text.VIDEO_NOT_READY);return;}java.util.Map<Integer,String> list=new java.util.LinkedHashMap<>(cards());if(list.size()>=32&&!list.containsKey((int)type)){Screen.say(Text.BLOCK_LIMIT);return;}String label=NativeRead.string(NativeRead.get(post,"getDesc")).replace('\t',' ').replace('\n',' ');if(label.length()>80)label=label.substring(0,80);list.put((int)type,label.isEmpty()?Text.HIDE_SUGGESTIONS:label);storeCards(list);Screen.say(Text.HIDDEN_NEXT_PAGE);}
 static void removeCard(int type){java.util.Map<Integer,String> list=new java.util.LinkedHashMap<>(cards());list.remove(type);storeCards(list);}
 private static void storeCards(java.util.Map<Integer,String> list){StringBuilder raw=new StringBuilder();for(java.util.Map.Entry<Integer,String> item:list.entrySet())raw.append(item.getKey()).append('\t').append(item.getValue()).append('\n');AccountAppearance.prefs(Ttcuz.context()).edit().putString(CARDS,raw.toString()).apply();cardCache=null;}
 static void reload(){cached=null;cardCache=null;wordCache=null;}
 /** Words and #hashtags a video's description must not have, lower case, at most 50. */
 static java.util.List<String> words(){java.util.List<String> known=wordCache;if(known!=null)return known;java.util.List<String> result=new java.util.ArrayList<>();if(Ttcuz.context()==null)return result;try{String raw=AccountAppearance.prefs(Ttcuz.context()).getString(WORDS,"");if(raw.length()<=16384)for(String line:raw.split("\n")){String w=line.trim();if(!w.isEmpty()&&result.size()<50&&!result.contains(w))result.add(w);}}catch(Throwable ignored){}wordCache=java.util.Collections.unmodifiableList(result);return wordCache;}
 static void addWord(String word){String w=word==null?"":word.trim().toLowerCase(java.util.Locale.ROOT).replace('\n',' ');if(w.isEmpty()||w.length()>60)return;java.util.List<String> list=new java.util.ArrayList<>(words());if(list.contains(w))return;if(list.size()>=50){Screen.say(Text.WORDS_LIMIT);return;}list.add(w);storeWords(list);Screen.say(Text.HIDDEN_NEXT_PAGE);}
 static void removeWord(String word){java.util.List<String> list=new java.util.ArrayList<>(words());list.remove(word);storeWords(list);}
 private static void storeWords(java.util.List<String> list){AccountAppearance.prefs(Ttcuz.context()).edit().putString(WORDS,String.join("\n",list)).apply();wordCache=null;}
 static boolean saysBlockedWord(Object post){java.util.List<String> list=words();if(list.isEmpty())return false;String desc=NativeRead.string(NativeRead.get(post,"getDesc")).toLowerCase(java.util.Locale.ROOT);if(desc.isEmpty())return false;for(String w:list)if(desc.contains(w))return true;return false;}
 static boolean hides(Object post){if(!Feed.socialRepost(post)&&saysBlockedWord(post))return true;if(!Feed.socialRepost(post)&&cards().containsKey((int)NativeRead.number(NativeRead.get(post,"getRecommendCardType"))))return true;java.util.Map<String,String> values=items();return !values.isEmpty() && values.containsKey(NativeRead.uid(NativeRead.get(post,"getAuthor")));}
 static void addPost(Object post){
  Object author=NativeRead.get(post,"getAuthor");String uid=NativeRead.uid(author);
  if(!Ids.uid(uid)){Screen.say(Text.VIDEO_NOT_READY);return;}
  java.util.Map<String,String> list=new java.util.LinkedHashMap<>(items());if(list.size()>=50 && !list.containsKey(uid)){Screen.say(Text.BLOCK_LIMIT);return;}
  String name=NativeRead.string(NativeRead.get(author,"getNickname")).replace('\t',' ').replace('\n',' ');if(name.length()>80)name=name.substring(0,80);
  list.put(uid,name.length()==0?uid:name);store(list);Screen.say(Text.HIDDEN_NEXT_PAGE);
 }
 static void remove(String uid){java.util.Map<String,String> list=new java.util.LinkedHashMap<>(items());list.remove(uid);store(list);}
 static void clear(){store(new java.util.LinkedHashMap<String,String>());storeCards(new java.util.LinkedHashMap<Integer,String>());storeWords(new java.util.ArrayList<String>());}
 private static void store(java.util.Map<String,String> list){
  StringBuilder raw=new StringBuilder();for(java.util.Map.Entry<String,String> e:list.entrySet())raw.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
  AccountAppearance.prefs(Ttcuz.context()).edit().putString(KEY,raw.toString()).apply();reload();
 }
}
