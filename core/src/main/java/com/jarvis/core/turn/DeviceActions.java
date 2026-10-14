package com.jarvis.core.turn;

/**
 * The "hands". Implemented by the Android app; the core only decides WHICH
 * action a user asked for. Each method returns the sentence Jarvis should say.
 * Implementations must be safe to call from a background thread.
 */
public interface DeviceActions {

    String call(String contactOrNumber);

    String openApp(String appName);

    String setAlarm(int hour24, int minute);

    String setTimer(int seconds);

    /** e.g. "Battery is at 62 percent and charging." */
    String batteryStatus();
}
