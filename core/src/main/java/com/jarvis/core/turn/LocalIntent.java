package com.jarvis.core.turn;

import com.jarvis.core.text.TextTools;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Device commands Jarvis handles itself, without the model. Patterns are
 * anchored to the whole utterance so a sentence that merely mentions "call" or
 * "open" is never executed. The model is never allowed to trigger these.
 */
public final class LocalIntent {

    public enum Type { NONE, CALL, OPEN_APP, ALARM, TIMER, TIME, DATE, BATTERY }

    public final Type type;
    /** Contact name / number for CALL, app name for OPEN_APP, label for ALARM/TIMER. */
    public final String target;
    /** ALARM: hour 0-23. */
    public final int hour;
    /** ALARM: minute. */
    public final int minute;
    /** TIMER: total seconds. */
    public final int seconds;

    private LocalIntent(Type type, String target, int hour, int minute, int seconds) {
        this.type = type;
        this.target = target == null ? "" : target;
        this.hour = hour;
        this.minute = minute;
        this.seconds = seconds;
    }

    public static final LocalIntent NONE = new LocalIntent(Type.NONE, "", -1, -1, 0);

    private static final Pattern LEAD = Pattern.compile(
            "(?i)^(?:(?:hey|hi|ok|okay)\\s+)?(?:jarvis[\\s,]+)?(?:please\\s+|can you\\s+|could you\\s+)?");

    private static final Pattern CALL = Pattern.compile(
            "(?i)^(?:call|dial|phone|ring|make a call to|place a call to)\\s+(.+?)(?:\\s+(?:now|please))?$");
    private static final Pattern OPEN = Pattern.compile(
            "(?i)^(?:open|launch|start)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?(?:\\s+(?:now|please))?$");
    private static final Pattern ALARM = Pattern.compile(
            "(?i)^(?:set\\s+(?:an?\\s+)?alarm|alarm|wake me(?: up)?)\\s+(?:for|at)?\\s*"
                    + "(\\d{1,2})(?:[:.\\s](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?|in the morning|in the evening|at night|tonight)?$");
    private static final Pattern TIMER = Pattern.compile(
            "(?i)^(?:set\\s+(?:a\\s+)?timer|timer|start\\s+a\\s+timer)\\s+(?:for\\s+)?(.+)$");
    private static final Pattern TIMER_PART = Pattern.compile(
            "(?i)(\\d+|an?|one|two|three|four|five|ten|fifteen|twenty|thirty|half an?)\\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?)");
    private static final Pattern TIME = Pattern.compile(
            "(?i)^(?:what(?:'s| is)? the time(?: now)?|what time is it(?: now)?|tell me the time|time please|current time)$");
    private static final Pattern DATE = Pattern.compile(
            "(?i)^(?:what(?:'s| is)? (?:the |today's )?date(?: today)?|what day is (?:it|today)|today's date|what is today)$");
    private static final Pattern BATTERY = Pattern.compile(
            "(?i)^(?:what(?:'s| is)? (?:my |the )?battery(?: level| percentage| status)?|how much (?:battery|charge)(?: is left| do i have)?|battery (?:level|status|percentage))$");

    public static LocalIntent parse(String utterance) {
        String t = TextTools.trimPunctuation(utterance);
        t = TextTools.trimPunctuation(LEAD.matcher(t).replaceFirst(""));
        if (t.isEmpty()) return NONE;

        if (TIME.matcher(t).matches()) return new LocalIntent(Type.TIME, "", -1, -1, 0);
        if (DATE.matcher(t).matches()) return new LocalIntent(Type.DATE, "", -1, -1, 0);
        if (BATTERY.matcher(t).matches()) return new LocalIntent(Type.BATTERY, "", -1, -1, 0);

        Matcher m = ALARM.matcher(t);
        if (m.matches()) {
            int h = Integer.parseInt(m.group(1));
            int min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            String mer = m.group(3) == null ? "" : TextTools.lower(m.group(3)).replace(".", "");
            if (h > 23 || min > 59) return NONE;
            boolean pm = mer.startsWith("pm") || mer.contains("evening") || mer.contains("night");
            boolean am = mer.startsWith("am") || mer.contains("morning");
            if (pm && h < 12) h += 12;
            if (am && h == 12) h = 0;
            return new LocalIntent(Type.ALARM, "", h, min, 0);
        }

        m = TIMER.matcher(t);
        if (m.matches()) {
            int secs = parseDuration(m.group(1));
            if (secs > 0) return new LocalIntent(Type.TIMER, "", -1, -1, secs);
        }

        m = CALL.matcher(t);
        if (m.matches()) {
            String who = TextTools.trimPunctuation(m.group(1));
            String lw = TextTools.lower(who);
            // "call me Stark" is a naming request (memory), not a phone call.
            if (lw.equals("me") || lw.startsWith("me ")) return NONE;
            return new LocalIntent(Type.CALL, who, -1, -1, 0);
        }

        m = OPEN.matcher(t);
        if (m.matches()) {
            String app = TextTools.trimPunctuation(m.group(1));
            if (app.split(" ").length <= 4) {
                return new LocalIntent(Type.OPEN_APP, app, -1, -1, 0);
            }
        }
        return NONE;
    }

    static int parseDuration(String text) {
        Matcher m = TIMER_PART.matcher(text);
        int total = 0;
        while (m.find()) {
            String n = TextTools.lower(m.group(1));
            String unit = TextTools.lower(m.group(2));
            double value;
            switch (n) {
                case "a": case "an": case "one": value = 1; break;
                case "two": value = 2; break;
                case "three": value = 3; break;
                case "four": value = 4; break;
                case "five": value = 5; break;
                case "ten": value = 10; break;
                case "fifteen": value = 15; break;
                case "twenty": value = 20; break;
                case "thirty": value = 30; break;
                case "half a": case "half an": value = 0.5; break;
                default: value = Integer.parseInt(n);
            }
            int mult = unit.startsWith("h") ? 3600 : unit.startsWith("m") ? 60 : 1;
            total += (int) Math.round(value * mult);
        }
        return total;
    }

    @Override
    public String toString() {
        return type + "{" + target + (hour >= 0 ? " " + hour + ":" + minute : "") + (seconds > 0 ? " " + seconds + "s" : "") + "}";
    }
}
