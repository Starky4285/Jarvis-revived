package com.jarvis.core.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Small, dependency-free text helpers shared by memory and routing.
 * Nothing here tries to "understand" language; it only normalizes it.
 */
public final class TextTools {

    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final Pattern EDGE_PUNCT = Pattern.compile("^[\\s,.;:!?\"]+|[\\s,.;:!?\"]+$");
    private static final Pattern SPECIAL_TOKEN = Pattern.compile("<\\|[^>]{0,120}\\|>");
    private static final Pattern ROLE_MARKER = Pattern.compile("(?i)\\b(system|assistant|user|developer)\\s*:");

    /** Words that carry no meaning for matching memory keys against questions. */
    private static final Set<String> STOP_WORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "a", "an", "the", "my", "your", "our", "his", "her", "their", "its", "of", "is", "are", "was",
            "were", "be", "been", "am", "do", "does", "did", "to", "in", "on", "at", "for", "and", "or",
            "what", "whats", "who", "whos", "when", "where", "which", "how", "me", "i", "you", "it", "that",
            "this", "tell", "remember", "know", "please", "jarvis", "s", "about", "again", "now", "current"
    )));

    private TextTools() {
    }

    public static String collapseSpaces(String text) {
        if (text == null) return "";
        return MULTI_SPACE.matcher(text).replaceAll(" ").trim();
    }

    /** Trims whitespace and sentence punctuation at both ends. Keeps inner text and case. */
    public static String trimPunctuation(String text) {
        if (text == null) return "";
        String s = collapseSpaces(text);
        String prev;
        do {
            prev = s;
            s = EDGE_PUNCT.matcher(s).replaceAll("");
        } while (!s.equals(prev));
        return s;
    }

    public static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    /**
     * Very light English stemmer, only so "parked" ~ "park" and "birthdays" ~
     * "birthday" match. Deliberately conservative: short words are left alone.
     */
    public static String stem(String word) {
        String w = lower(word).replace("'", "");
        if (w.length() > 5 && w.endsWith("ing")) return w.substring(0, w.length() - 3);
        if (w.length() > 4 && w.endsWith("ied")) return w.substring(0, w.length() - 3) + "y";
        if (w.length() > 4 && w.endsWith("ed")) return w.substring(0, w.length() - 2);
        if (w.length() > 4 && w.endsWith("ies")) return w.substring(0, w.length() - 3) + "y";
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us")) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }

    /** Lowercased, stemmed content words (stop words removed), in order, de-duplicated. */
    public static List<String> contentTokens(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : lower(text).split("[^\\p{L}\\p{Nd}']+")) {
            String t = raw.replaceAll("^'+|'+$", "");
            if (t.endsWith("'s")) t = t.substring(0, t.length() - 2);
            if (t.isEmpty() || STOP_WORDS.contains(t)) continue;
            out.add(stem(t));
        }
        return new ArrayList<>(out);
    }

    public static boolean isStopWord(String word) {
        return STOP_WORDS.contains(lower(word));
    }

    /**
     * Turns the user's first-person words into second person for spoken replies:
     * "my sister's house" -> "your sister's house", "I parked" -> "you parked".
     */
    public static String flipPerson(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] words = text.split(" ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            String core = w.replaceAll("[,.;:!?]+$", "");
            String tail = w.substring(core.length());
            String lc = lower(core);
            String repl;
            switch (lc) {
                case "my": repl = "your"; break;
                case "mine": repl = "yours"; break;
                case "me": repl = "you"; break;
                case "myself": repl = "yourself"; break;
                case "i": repl = "you"; break;
                case "i'm": repl = "you're"; break;
                case "im": repl = "you're"; break;
                case "i've": repl = "you've"; break;
                case "i'll": repl = "you'll"; break;
                case "i'd": repl = "you'd"; break;
                case "am": repl = (i > 0 && lower(words[i - 1]).equals("i")) ? "are" : core; break;
                case "was": repl = (i > 0 && lower(words[i - 1]).equals("i")) ? "were" : core; break;
                default: repl = core;
            }
            if (sb.length() > 0) sb.append(' ');
            sb.append(repl).append(tail);
        }
        return sb.toString();
    }

    private static final Set<String> SMALL_WORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "and", "or", "of", "the", "in", "on", "at", "to", "a", "an", "de", "da", "bin", "van", "von"
    )));

    private static final Pattern CALENDAR_WORD = Pattern.compile(
            "(?i)\\b(january|february|march|april|may|june|july|august|september|october|november|december"
                    + "|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec"
                    + "|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b");

    /** "om namah shivaya" -> "Om Namah Shivaya", "aarav and diya" -> "Aarav and Diya". */
    public static String titleCase(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] words = text.split(" ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (sb.length() > 0) sb.append(' ');
            boolean keep = w.isEmpty() || !w.equals(lower(w)) || w.matches(".*\\d.*")
                    || (i > 0 && SMALL_WORDS.contains(w));
            if (keep) {
                sb.append(w);
            } else {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return sb.toString();
    }

    /** "5 may" -> "5 May", "every sunday" -> "every Sunday". Speech-to-text lowercases these. */
    public static String capitalizeCalendarWords(String text) {
        if (text == null || text.isEmpty()) return "";
        java.util.regex.Matcher m = CALENDAR_WORD.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String w = m.group(1);
            m.appendReplacement(sb, Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase(Locale.ROOT));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Makes stored text safe to place inside a model prompt as DATA: no chat
     * template tokens, no role markers, no braces/quotes, single line.
     */
    public static String sanitizeForPrompt(String text, int maxChars) {
        if (text == null) return "";
        String clean = SPECIAL_TOKEN.matcher(text).replaceAll(" ");
        clean = ROLE_MARKER.matcher(clean).replaceAll("$1 -");
        clean = clean.replaceAll("[\\r\\n]+", "; ")
                .replaceAll("\\p{Cntrl}", " ")
                .replace("{", "").replace("}", "")
                .replace("\"", "").replace("`", "")
                .replace("[", "(").replace("]", ")");
        clean = collapseSpaces(clean);
        if (maxChars > 0 && clean.length() > maxChars) {
            clean = clean.substring(0, maxChars).trim() + "…";
        }
        return clean;
    }
}
