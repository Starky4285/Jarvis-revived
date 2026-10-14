package com.jarvis.revived;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;

import com.jarvis.revived.assistant.AssistantController;

/** Creates notification channels and the single {@link AssistantController}. */
public final class JarvisApp extends Application {

    public static final String CHANNEL_LISTENING = "listening";
    public static final String CHANNEL_ACTIONS = "actions";

    private AssistantController controller;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        controller = new AssistantController(this);
    }

    public AssistantController controller() {
        return controller;
    }

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel listening = new NotificationChannel(CHANNEL_LISTENING,
                getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
        listening.setShowBadge(false);
        NotificationChannel actions = new NotificationChannel(CHANNEL_ACTIONS,
                getString(R.string.notification_actions), NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(listening);
        nm.createNotificationChannel(actions);
    }
}
