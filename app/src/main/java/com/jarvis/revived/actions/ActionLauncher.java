package com.jarvis.revived.actions;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.jarvis.revived.JarvisApp;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Starts the activity behind an action (dialer, clock, another app).
 *
 * Android blocks apps from opening screens while they are in the background.
 * When Jarvis' window is visible we launch directly; otherwise we post a
 * notification the user can tap. The spoken reply says which happened, so
 * Jarvis never claims an action that didn't run.
 */
public final class ActionLauncher {

    private static final String TAG = "ActionLauncher";
    private static final AtomicInteger NEXT_ID = new AtomicInteger(2000);

    private final Context context;
    private final BooleanSupplier uiVisible;
    private final Handler main = new Handler(Looper.getMainLooper());

    public ActionLauncher(Context context, BooleanSupplier uiVisible) {
        this.context = context.getApplicationContext();
        this.uiVisible = uiVisible;
    }

    /** @return true if launched now, false if a tap-to-run notification was posted instead. */
    public boolean launch(Intent intent, String tapText) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (uiVisible.getAsBoolean()) {
            main.post(() -> {
                try {
                    context.startActivity(intent);
                } catch (RuntimeException e) {
                    Log.e(TAG, "startActivity failed", e);
                }
            });
            return true;
        }
        int id = NEXT_ID.incrementAndGet();
        PendingIntent pi = PendingIntent.getActivity(context, id, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(context, JarvisApp.CHANNEL_ACTIONS)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Jarvis")
                .setContentText(tapText)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build();
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        try {
            if (nm != null) nm.notify(id, n);
        } catch (SecurityException e) {
            Log.e(TAG, "Notification permission missing", e);
        }
        return false;
    }
}
