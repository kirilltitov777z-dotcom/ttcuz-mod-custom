package cat.ttcuz.framework;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * "Ловить удалённые в фоне": TikTok kept running while it is not on screen, so its message connection stays up and an
 * unsend is still caught (DeletedMessages) and every message still copied (MessageArchive) while one scrolls another app
 * or the phone is locked. Android lets an app run in the background only with a notification of its own; it is the
 * quietest kind (no sound, no icon in the status bar where the system allows). Off by default; switched off, it stops.
 *
 * Android 15 gives such a service six hours a day; when they run out it stops, and it starts again the next time
 * TikTok is opened.
 */
public final class MessageKeeper extends Service {
    static final String KEY = "ayu_keep_in_background";
    private static final String CHANNEL = "ttcuz_keeper";
    private static final int ID = 0x7e0c;

    static boolean enabled() {
        Context c = Ttcuz.context();
        return c != null && c.getSharedPreferences(Ttcuz.PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false);
    }

    static void set(Context context, boolean on) {
        context.getSharedPreferences(Ttcuz.PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply();
        if (on) ensure(context); else context.stopService(new Intent(context, MessageKeeper.class));
    }

    /**
     * Started while TikTok is on screen (a resumed screen): Android refuses a foreground service started from the
     * background. Nothing when it is off or deleted messages are not kept.
     */
    static void ensure(Context context) {
        if (context == null || !enabled() || !DeletedMessages.enabled()) return;
        try {
            Intent intent = new Intent(context, MessageKeeper.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent); else context.startService(intent);
        } catch (Throwable error) { Diary.note("message keeper: " + error); }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!enabled() || !DeletedMessages.enabled()) { stopSelf(); return START_NOT_STICKY; }
        try {
            Notification note = notification();
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, note, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(ID, note);
        } catch (Throwable error) {
            // Android said no (started from the background, or out of time today): nothing to keep up
            Diary.note("message keeper: " + error);
            stopSelf();
            return START_NOT_STICKY;
        }
        MessageArchive.start(this);
        MessageArchive.copy(this);
        return START_STICKY;
    }

    /** Android 15: the day's time for a data-sync service is up. */
    public void onTimeout(int startId, int type) { stopSelf(); }

    @Override public IBinder onBind(Intent intent) { return null; }

    private Notification notification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && manager != null && manager.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, Text.t("Удалённые сообщения"), NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent tap = open == null ? null : PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        builder.setSmallIcon(getApplicationInfo().icon)
                .setContentTitle(Text.t("ttcuz ловит удалённые сообщения"))
                .setContentText(Text.t("Выключить можно в настройках мода"))
                .setOngoing(true)
                .setShowWhen(false);
        if (Build.VERSION.SDK_INT < 26) builder.setPriority(Notification.PRIORITY_MIN);
        if (tap != null) builder.setContentIntent(tap);
        return builder.build();
    }
}
