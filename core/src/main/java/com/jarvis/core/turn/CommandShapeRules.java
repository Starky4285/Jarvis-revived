package com.jarvis.core.turn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure-Java rules about the *shape* of an utterance (is it a stop request, is it
 * really two commands?). No Android imports so they can be unit-tested on the JVM.
 *
 * Carried over from Patch 19 of the old app. The old processor and AIBrain disagreed about what a
 * compound command is. The processor treated ANY " and " as compound ("what is the
 * difference between a cat and a dog"), which skipped CommandParser, fast facts,
 * the current-info guard and DecisionEngine. AIBrain only split before a verb. Both
 * now use {@link #splitCompound(String)}, so "compound" means "actually splits".
 */
public final class CommandShapeRules {

    private static final Pattern THEN_SPLIT = Pattern.compile("(?i)\\s+then\\s+");
    private static final Pattern AFTER_THAT_SPLIT = Pattern.compile("(?i)\\s+after that\\s+");
    private static final Pattern AND_VERB_SPLIT = Pattern.compile(
            "(?i)\\s+and\\s+(?=(what|who|why|how|when|where|explain|open|launch|call|send|play|search|remind)\\b)"
    );

    private static final String SPLIT_MARK = " &&& ";

    private static final Set<String> STOP_PHRASES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "stop",
            "cancel",
            "quiet",
            "enough",
            "stop it",
            "stop that",
            "stop talking",
            "stop speaking",
            "cancel that",
            "cancel it",
            "be quiet",
            "shut up",
            "silence",
            "hush",
            "that's enough",
            "thats enough",
            "that is enough",
            "okay stop",
            "ok stop"
    )));

    private static final Pattern LEADING_ADDRESS = Pattern.compile(
            "^(?:(?:hey|hi|ok|okay)\\s+)?jarvis[\\s,]+"
    );
    private static final Pattern TRAILING_ADDRESS = Pattern.compile("[\\s,]+jarvis$");
    private static final Pattern POLITE_WRAP = Pattern.compile("^(?:please\\s+)|(?:\\s+please)$|(?:\\s+now)$");
    private static final Pattern EDGE_PUNCT = Pattern.compile("^[\\p{Punct}\\s]+|[\\p{Punct}\\s&&[^']]+$");

    private CommandShapeRules() {
    }

    /**
     * Splits only where a second command really starts ("then", "after that",
     * or "and" followed by a command/question verb). Returns a single element
     * for ordinary sentences that merely contain "and".
     */
    public static List<String> splitCompound(String input) {
        List<String> list = new ArrayList<>();
        if (input == null || input.trim().isEmpty()) {
            return list;
        }

        String working = input.trim();
        working = THEN_SPLIT.matcher(working).replaceAll(SPLIT_MARK);
        working = AFTER_THAT_SPLIT.matcher(working).replaceAll(SPLIT_MARK);
        working = AND_VERB_SPLIT.matcher(working).replaceAll(SPLIT_MARK);

        for (String block : working.split("&&&")) {
            String trimmed = block.trim();
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return list;
    }

    public static boolean isCompoundCommand(String text) {
        return splitCompound(text).size() > 1;
    }

    /**
     * Short, whole-utterance stop requests. Matching is on the full normalized
     * utterance only, so "stop the music in ten minutes" or "how do I stop
     * smoking" are NOT treated as stop.
     */
    public static boolean isStopCommand(String text) {
        String normalized = normalizeForStop(text);
        return !normalized.isEmpty() && STOP_PHRASES.contains(normalized);
    }

    static String normalizeForStop(String text) {
        if (text == null) return "";

        String s = text.toLowerCase(Locale.ROOT).trim();
        s = EDGE_PUNCT.matcher(s).replaceAll("");
        s = LEADING_ADDRESS.matcher(s).replaceFirst("");
        s = TRAILING_ADDRESS.matcher(s).replaceFirst("");

        // Strip "please" / "now" wrappers repeatedly ("please stop now").
        String previous;
        do {
            previous = s;
            s = POLITE_WRAP.matcher(s).replaceAll("").trim();
        } while (!s.equals(previous));

        s = EDGE_PUNCT.matcher(s).replaceAll("");
        return s.replaceAll("\\s+", " ").trim();
    }
}
