package com.jarvis.core.turn;

/**
 * The "hands". Implemented by the Android app; the core only decides WHICH
 * action a user asked for. Each method returns the sentence Jarvis should say.
 * Implementations must be safe to call from a background thread.
 */
public interface DeviceActions {

    /** Looks up the contact or number. Never places a call. */
    CallTarget findCallTarget(String contactOrNumber);

    /** Places the call (or opens the dialer). Only called after the user said yes. */
    String call(CallTarget target);

    String openApp(String appName);

    String setAlarm(int hour24, int minute);

    String setTimer(int seconds);

    /** e.g. "Battery is at 62 percent and charging." */
    String batteryStatus();

    /**
     * Opens directions (or turn-by-turn navigation) in the maps app.
     *
     * @param query      what the maps app should search for (a saved address, or the user's words)
     * @param spokenName how to say it back ("Shiva temple")
     * @param mode       "driving", "walking", "two_wheeler", "transit" or "" for the app's default
     */
    String directions(String query, String spokenName, String mode, boolean navigate);
}
