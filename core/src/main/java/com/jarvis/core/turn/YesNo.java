package com.jarvis.core.turn;

import com.jarvis.core.text.TextTools;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Short spoken answers to "… — yes or no?". Whole-utterance match only. */
public final class YesNo {

    private static final Set<String> YES = new HashSet<>(Arrays.asList(
            "yes", "yeah", "yep", "yup", "ya", "yes please", "sure", "ok", "okay", "ok go", "confirm", "confirmed",
            "do it", "go ahead", "go", "call", "call him", "call her", "call them", "yes call", "right", "correct",
            "haan", "han", "ha", "avunu", "yes jarvis", "ok jarvis", "okay jarvis"));

    private static final Set<String> NO = new HashSet<>(Arrays.asList(
            "no", "nope", "nah", "no thanks", "no thank you", "cancel", "cancel it", "cancel that", "don't",
            "do not", "dont", "stop", "wait", "leave it", "not now", "never mind", "nevermind", "wrong",
            "wrong person", "no jarvis", "vaddu", "nahi", "na"));

    private YesNo() {
    }

    public static boolean isYes(String text) {
        return YES.contains(normalize(text));
    }

    public static boolean isNo(String text) {
        return NO.contains(normalize(text));
    }

    private static String normalize(String text) {
        return TextTools.lower(TextTools.trimPunctuation(text)).replaceAll("[^\\p{L}\\p{Nd}' ]+", " ")
                .replaceAll("\\s+", " ").trim();
    }
}
