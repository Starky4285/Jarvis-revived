package com.jarvis.core.memory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Relative day words are resolved when the memory is WRITTEN, so
 * "dentist appointment tomorrow" said on 14 Oct is stored as
 * "dentist appointment on Thursday 15 October" and still reads correctly a
 * week later. The original sentence is kept separately as the fact's source.
 */
public final class TemporalAnchor {

    private static final Pattern RELATIVE_DAY =
            Pattern.compile("(?i)\\b(day after tomorrow|tomorrow|today|tonight|yesterday)\\b");

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH);

    private TemporalAnchor() {
    }

    public static String anchor(String text, long nowMillis, ZoneId zone) {
        if (text == null || text.isEmpty()) return "";
        Matcher m = RELATIVE_DAY.matcher(text);
        if (!m.find()) return text;

        LocalDate today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate();
        StringBuffer sb = new StringBuffer();
        m.reset();
        while (m.find()) {
            String word = m.group(1).toLowerCase(Locale.ROOT);
            LocalDate date;
            switch (word) {
                case "day after tomorrow": date = today.plusDays(2); break;
                case "tomorrow": date = today.plusDays(1); break;
                case "yesterday": date = today.minusDays(1); break;
                default: date = today;
            }
            String prefix = word.equals("tonight") ? "on the night of " : "on ";
            // "due on tomorrow" style inputs already have "on" before the word.
            int start = m.start();
            boolean hasOn = start >= 3 && text.substring(start - 3, start).equalsIgnoreCase("on ");
            m.appendReplacement(sb, Matcher.quoteReplacement((hasOn ? "" : prefix) + FMT.format(date)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
