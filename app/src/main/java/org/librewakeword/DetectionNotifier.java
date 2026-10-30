package org.librewakeword;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.text.format.DateFormat;
import android.util.Log;

import org.librewakeword.client.WakewordEvent;

import java.util.concurrent.atomic.AtomicInteger;

public final class DetectionNotifier {
    public static final String ACTION_DETECTED = "org.librewakeword.ACTION_HOTWORD_DETECTED";
    public static final String EXTRA_COUNT = "count";
    public static final String EXTRA_TIME = "time";

    private static final String TAG = "HotwordVIS";
    private static final String CHANNEL_ID = "wakeword_activity_v2";
    private static final int NOTIFICATION_ID = 1001;
    private static final AtomicInteger count = new AtomicInteger(0);

    public static void ensureChannel(Context ctx) {
        try {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Wake word activity", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Confirmed wake-word detections");
            channel.setSound(null, null);
            channel.enableVibration(false);
            ctx.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        } catch (Throwable t) {
            Log.w(TAG, "ensureChannel failed", t);
        }
    }

    public static void fire(Context ctx, WakewordEvent event) {
        int n = count.incrementAndGet();
        String time = DateFormat.format("HH:mm:ss", System.currentTimeMillis()).toString();
        Log.i(TAG, "DetectionNotifier.fire count=" + n + " time=" + time);

        try {
            ensureChannel(ctx);
            String phrase = ModelConfig.load(ctx).phrase;
            Notification notification = new Notification.Builder(ctx, CHANNEL_ID)
                    .setContentTitle("\"" + phrase + "\" detected (#" + n + ")")
                    .setContentText("Confirmed at " + time)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setCategory(Notification.CATEGORY_EVENT)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .build();
            ctx.getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification);
        } catch (Throwable t) {
            Log.w(TAG, "notify failed (is POST_NOTIFICATIONS granted?)", t);
        }

        try {
            Intent intent = new Intent(ACTION_DETECTED);
            intent.setPackage(ctx.getPackageName());
            intent.putExtra(EXTRA_COUNT, n);
            intent.putExtra(EXTRA_TIME, time);
            ctx.sendBroadcast(intent);
        } catch (Throwable t) {
            Log.w(TAG, "broadcast failed", t);
        }

    }

    private DetectionNotifier() {}
}
