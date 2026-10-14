package com.jarvis.core.llm;

import com.jarvis.core.text.TextTools;

import java.util.regex.Pattern;

/**
 * Cleans raw model text into something safe and pleasant to SPEAK, or returns
 * "" so the caller can retry / fall back. Ported from the old Phi3Manager
 * guard, including the Patch 19 fix: empty output stays empty (it used to be
 * turned into "I'm here to help." and counted as success).
 */
public final class AnswerGuard {

    private static final Pattern SPECIAL = Pattern.compile("<\\|[^>]*\\|>|<\\|.*$");
    private static final Pattern PREFIX = Pattern.compile("(?i)^(?:assistant|jarvis|answer|response)\\s*[:\\-]\\s*");
    private static final Pattern MARKDOWN = Pattern.compile("[*_#`>]+");
    private static final Pattern SHORT_VALID = Pattern.compile(
            "(?i)^(yes|no|yep|nope|yeah|sure|okay|ok|hi|hello|hey|correct|right|true|false|thanks|thank you)[.!]$"
                    + "|^-?\\d+([.,]\\d+)?\\s*[.!]?$");
    private static final Pattern JSONISH = Pattern.compile("^\\s*[\\[{].*[\\]}]\\s*$", Pattern.DOTALL);

    private static final String[] JUNK_TAIL_MARKERS = {
            "\n\n", "(note:", " note:", "\nnote:", "\n- ", " as an ai language model",
            "<|", "user:", "\nuser", "instruction:"
    };

    private static final String[] INCOMPLETE_ENDINGS = {
            " and", " or", " but", " because", " including", " such as", " like", " with",
            " for", " to", " in", " of", " the", " a", " an", ","
    };

    private AnswerGuard() {
    }

    /** Returns a speakable answer or "" when the output is unusable. */
    public static String clean(String raw) {
        if (raw == null) return "";
        String text = raw;
        if (JSONISH.matcher(text).matches()) return "";

        String lower = TextTools.lower(text);
        int cut = -1;
        for (String marker : JUNK_TAIL_MARKERS) {
            int idx = lower.indexOf(marker);
            if (idx > 0 && (cut < 0 || idx < cut)) cut = idx;
        }
        if (cut > 0) text = text.substring(0, cut);

        text = SPECIAL.matcher(text).replaceAll(" ");
        text = MARKDOWN.matcher(text).replaceAll("");
        text = TextTools.collapseSpaces(text);
        text = PREFIX.matcher(text).replaceFirst("");
        text = text.replaceAll("[,;:\\-]+\\s*$", "").trim();

        if (!containsLetterOrDigit(text)) return "";
        if (TextTools.lower(text).contains("as an ai language model")) return "";

        if (!endsWithTerminal(text)) {
            int last = Math.max(text.lastIndexOf(". "), Math.max(text.lastIndexOf("? "), text.lastIndexOf("! ")));
            if (last >= 20) {
                text = text.substring(0, last + 1).trim();
            } else if (looksIncomplete(text)) {
                return "";
            } else {
                text = text + ".";
            }
        }
        return text;
    }

    /** Light cleaning for a streamed sentence chunk; "" means "don't speak this chunk". */
    public static String cleanPartial(String chunk) {
        if (chunk == null) return "";
        if (chunk.contains("<|") || JSONISH.matcher(chunk).matches()) return "";
        String text = MARKDOWN.matcher(chunk).replaceAll("");
        text = PREFIX.matcher(TextTools.collapseSpaces(text)).replaceFirst("");
        if (!containsLetterOrDigit(text)) return "";
        if (TextTools.lower(text).contains("as an ai language model")) return "";
        return text;
    }

    public static boolean isGood(String cleaned) {
        if (cleaned == null) return false;
        String s = cleaned.trim();
        if (s.isEmpty()) return false;
        if (SHORT_VALID.matcher(s).matches()) return true;
        return s.length() >= 8 && endsWithTerminal(s);
    }

    static boolean endsWithTerminal(String s) {
        String t = s.trim();
        return t.endsWith(".") || t.endsWith("?") || t.endsWith("!");
    }

    /** Only meaningful for text WITHOUT final punctuation ("...such as"). */
    static boolean looksIncomplete(String s) {
        String l = TextTools.lower(s).trim();
        for (String e : INCOMPLETE_ENDINGS) {
            if (l.endsWith(e)) return true;
        }
        return false;
    }

    private static boolean containsLetterOrDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetterOrDigit(s.charAt(i))) return true;
        }
        return false;
    }
}
