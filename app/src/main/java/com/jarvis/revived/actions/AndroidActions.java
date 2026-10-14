package com.jarvis.revived.actions;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.BatteryManager;
import android.provider.AlarmClock;
import android.provider.ContactsContract;

import com.jarvis.core.text.TextTools;
import com.jarvis.core.turn.DeviceActions;

import java.util.List;
import java.util.Locale;

/**
 * The "hands": calls, apps, alarms, timers, battery. Only ever invoked by the
 * local intent parser, never by model output.
 */
public final class AndroidActions implements DeviceActions {

    private final Context context;
    private final ActionLauncher launcher;

    public AndroidActions(Context context, ActionLauncher launcher) {
        this.context = context.getApplicationContext();
        this.launcher = launcher;
    }

    // ------------------------------------------------------------------ call

    @Override
    public String call(String who) {
        String target = TextTools.trimPunctuation(who);
        String digits = target.replaceAll("[^0-9+]", "");
        String number;
        String name;

        if (digits.length() >= 3 && digits.length() >= target.replaceAll("\\s", "").length() - 1) {
            number = digits;
            name = target;
        } else {
            if (!granted(Manifest.permission.READ_CONTACTS)) {
                return "I need contacts permission to find " + target + ". You can allow it from Jarvis' settings.";
            }
            String[] match = findContact(target);
            if (match == null) {
                return "I couldn't find " + target + " in your contacts.";
            }
            name = match[0];
            number = match[1];
        }

        boolean direct = granted(Manifest.permission.CALL_PHONE);
        Intent intent = new Intent(direct ? Intent.ACTION_CALL : Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)));
        boolean now = launcher.launch(intent, (direct ? "Tap to call " : "Tap to dial ") + name);
        if (!now) return "Tap the notification to call " + name + ".";
        return direct ? "Calling " + name + "." : "Opening the dialer for " + name + ".";
    }

    /** Returns {displayName, number} for the best contact match, or null. */
    private String[] findContact(String wanted) {
        String w = TextTools.lower(wanted);
        String[] best = null;
        int bestScore = 0;
        String[] cols = {ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER};
        try (Cursor c = context.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                cols, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                new String[]{"%" + wanted + "%"}, null)) {
            if (c == null) return null;
            while (c.moveToNext()) {
                String name = c.getString(0);
                String num = c.getString(1);
                if (name == null || num == null) continue;
                String n = TextTools.lower(name);
                int score = n.equals(w) ? 3 : n.startsWith(w) ? 2 : 1;
                if (score > bestScore) {
                    bestScore = score;
                    best = new String[]{name, num};
                }
            }
        } catch (SecurityException e) {
            return null;
        }
        return best;
    }

    // -------------------------------------------------------------- open app

    @Override
    public String openApp(String appName) {
        String w = normalize(appName);
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);

        ResolveInfo best = null;
        int bestScore = 0;
        String bestLabel = "";
        for (ResolveInfo ri : apps) {
            String label = String.valueOf(ri.loadLabel(pm));
            String l = normalize(label);
            int score = l.equals(w) ? 4 : l.startsWith(w) ? 3 : l.contains(w) ? 2 : w.contains(l) && l.length() >= 4 ? 1 : 0;
            if (score > bestScore) {
                bestScore = score;
                best = ri;
                bestLabel = label;
            }
        }
        if (best == null) {
            return "I couldn't find an app called " + appName + ".";
        }
        Intent launch = pm.getLaunchIntentForPackage(best.activityInfo.packageName);
        if (launch == null) {
            return "I found " + bestLabel + " but couldn't open it.";
        }
        boolean now = launcher.launch(launch, "Tap to open " + bestLabel);
        return now ? "Opening " + bestLabel + "." : "Tap the notification to open " + bestLabel + ".";
    }

    private static String normalize(String s) {
        return TextTools.lower(s).replaceAll("[^\\p{L}\\p{Nd}]+", "");
    }

    // ---------------------------------------------------------- alarm, timer

    @Override
    public String setAlarm(int hour24, int minute) {
        Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour24)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        if (intent.resolveActivity(context.getPackageManager()) == null) {
            return "I couldn't find a clock app for alarms.";
        }
        String when = formatTime(hour24, minute);
        boolean now = launcher.launch(intent, "Tap to set an alarm for " + when);
        return now ? "Alarm set for " + when + "." : "Tap the notification to set the alarm for " + when + ".";
    }

    @Override
    public String setTimer(int seconds) {
        Intent intent = new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        if (intent.resolveActivity(context.getPackageManager()) == null) {
            return "I couldn't find a clock app for timers.";
        }
        String length = formatDuration(seconds);
        boolean now = launcher.launch(intent, "Tap to start a " + length + " timer");
        return now ? "Timer set for " + length + "." : "Tap the notification to start the " + length + " timer.";
    }

    // --------------------------------------------------------------- battery

    @Override
    public String batteryStatus() {
        BatteryManager bm = context.getSystemService(BatteryManager.class);
        int pct = bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        Intent sticky = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int status = sticky == null ? -1 : sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        if (pct < 0) return "I couldn't read the battery level.";
        return "Battery is at " + pct + " percent" + (charging ? " and charging." : ".");
    }

    // ---------------------------------------------------------------- helpers

    private boolean granted(String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    static String formatTime(int h, int m) {
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return String.format(Locale.ENGLISH, "%d:%02d %s", h12, m, h < 12 ? "AM" : "PM");
    }

    static String formatDuration(int seconds) {
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append(h == 1 ? " hour" : " hours");
        if (m > 0) sb.append(sb.length() > 0 ? " " : "").append(m).append(m == 1 ? " minute" : " minutes");
        if (s > 0) sb.append(sb.length() > 0 ? " " : "").append(s).append(s == 1 ? " second" : " seconds");
        return sb.toString();
    }
}
