package com.jarvis.revived.assistant;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.jarvis.revived.JarvisApp;
import com.jarvis.revived.R;
import com.jarvis.revived.ui.MainActivity;

/**
 * Foreground service that exists only while hands-free mode is on. It keeps the
 * process (and microphone access) alive with the screen off and shows a
 * notification with a Stop button. The conversation itself lives in
 * {@link AssistantController}.
 */
public final class AssistantService extends Service {

    private static final String TAG = "AssistantService";
    private static final int NOTIFICATION_ID = 1;
    public static final String ACTION_STOP = "com.jarvis.revived.STOP";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            controller().stopAll();
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
        } catch (RuntimeException e) {
            // Android 14 refuses a microphone service without the permission / from the background.
            Log.e(TAG, "startForeground refused", e);
            controller().stopAll();
            stopSelf();
            return START_NOT_STICKY;
        }

        // Not sticky: after a crash or kill, hands-free must be restarted by the user, never silently.
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private AssistantController controller() {
        return ((JarvisApp) getApplication()).controller();
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, AssistantService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, JarvisApp.CHANNEL_LISTENING)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_listening))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                        getString(R.string.stop), stop).build())
                .build();
    }
}
