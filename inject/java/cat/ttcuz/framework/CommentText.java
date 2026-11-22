package cat.ttcuz.framework;

/** Read the current comment model, never a nickname or arbitrary row label. */
final class CommentText {
    private static final String MODEL = "com.ss.android.ugc.aweme.comment.model.Comment";
    private static final String CELL = "com.ss.android.ugc.aweme.commentv2.commentlist.powercell.BaseCommentCell";
    private CommentText() {}
    /** The uid of a comment's author, read from the same model as its text; null when not found. */
    /** class#name -> its no-argument method (or a miss): every comment on screen asks the same few. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> methods = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Object NONE = new Object();

    private static Object call(Object target, String name) throws Exception {
        if (target == null) return null;
        String key = target.getClass().getName() + "#" + name;
        Object m = methods.get(key);
        if (m == null) {
            try { java.lang.reflect.Method found = target.getClass().getMethod(name); found.setAccessible(true); m = found; }
            catch (NoSuchMethodException missing) { m = NONE; }
            if (methods.size() < 256) methods.put(key, m);
        }
        return m == NONE ? null : ((java.lang.reflect.Method) m).invoke(target);
    }

    static String author(Object listener) {
        try {
            // by its type: the cell's getter is "O6" in 47.2.41 and named otherwise in 47.7.3, where the author was
            // never found and nobody's comment colour was used
            Object model = model(listener, new java.util.IdentityHashMap<Object, Boolean>(), 0);
            if (model == null) return null;
            Object user = call(model, "getUser");
            Object uid = call(user, "getUid");
            return uid instanceof String && !((String) uid).isEmpty() ? (String) uid : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String from(Object listener) {
        Object model = model(listener, new java.util.IdentityHashMap<Object, Boolean>(), 0);
        try {
            Object body = call(model, "getText");
            return body instanceof String && ((String) body).trim().length() > 0 ? (String) body : null;
        } catch (Throwable ignored) { return null; }
    }

    /** The cell's Comment: the cell's own getter that returns one, else a field holding one (a few steps deep). */
    private static Object model(Object value, java.util.IdentityHashMap<Object,Boolean> seen, int depth) {
        if (value==null || depth>4 || seen.size()>128 || seen.put(value,Boolean.TRUE)!=null) return null;
        Class<?> type=value.getClass();
        if (MODEL.equals(type.getName())) return value;
        String name=type.getName();
        if (name.startsWith("java.") || name.startsWith("android.")
                || name.contains(".profile.model.User")) return null;
        for (Class<?> owner=type;owner!=null && owner!=Object.class;owner=owner.getSuperclass()) {
            if (CELL.equals(owner.getName())) {
                for (java.lang.reflect.Method method:owner.getDeclaredMethods()) {
                    if (method.getParameterTypes().length==0 && MODEL.equals(method.getReturnType().getName())) {
                        try {method.setAccessible(true);Object found=model(method.invoke(value),seen,depth+1);if(found!=null)return found;}
                        catch (Throwable ignored) { }
                    }
                }
            }
            for (java.lang.reflect.Field field:owner.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    Object found=model(field.get(value),seen,depth+1);
                    if (found!=null) return found;
                } catch (Throwable ignored) { }
            }
        }
        return null;
    }
}
