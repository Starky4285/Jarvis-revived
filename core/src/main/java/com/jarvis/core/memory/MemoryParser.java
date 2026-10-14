package com.jarvis.core.memory;

import com.jarvis.core.text.TextTools;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic fast path: recognizes the common SHAPES of memory requests.
 *
 * It does not try to understand every sentence. Anything said after an
 * explicit "remember ..." is always kept — as a key/value fact when the shape
 * is obvious ("my X is Y"), otherwise as a free-form note that is still
 * searchable. Implicit statements without "remember" are only saved for a
 * short list of unambiguous shapes ("my name is", "my favourite X is", ...),
 * so chatter like "my phone is dying" is never stored.
 */
public final class MemoryParser {

    private static final int MAX_KEY_WORDS = 6;

    private static final Pattern ADDRESS = Pattern.compile("(?i)^(?:(?:hey|hi|ok|okay)\\s+)?jarvis[\\s,]+");
    private static final Pattern POLITE = Pattern.compile("(?i)^(?:please|can you|could you|will you|would you)\\s+");
    private static final Pattern CORRECTION_PREFIX =
            Pattern.compile("(?i)^(?:actually|no|nope|wait|sorry|oops|correction)[,]?\\s+");

    private static final Pattern FORGET_ALL = Pattern.compile(
            "(?i)^(?:forget|delete|erase|clear|wipe)\\s+(?:everything(?: you know)?(?: about me)?"
                    + "|all (?:of )?(?:my |your )?(?:memory|memories|data)|all you know(?: about me)?"
                    + "|my memory|your memory)$");

    private static final Pattern UNDO = Pattern.compile(
            "(?i)^(?:never ?mind|scratch that|forget that|undo(?: that)?|don'?t remember that|do not remember that|don'?t save that)$");

    private static final Pattern CORRECT_LAST = Pattern.compile(
            "(?i)^(?:no|nope|actually|wait|sorry|oops)[,]?\\s+(?:it'?s|it is|its|make it|change it to|i meant?|i said)\\s+(.+)$");

    private static final Pattern FORGET = Pattern.compile(
            "(?i)^(?:forget|delete|remove|erase|clear)\\s+(?:about\\s+|what i (?:said|told you) about\\s+)?(.+)$");

    private static final Pattern REMEMBER = Pattern.compile(
            "(?i)^(?:remember|note(?: down)?|save|store|keep in mind|don'?t forget|do not forget|make a note)"
                    + "(?:\\s+(?:that|this))?[:,]?\\s+(.+)$");

    private static final Pattern RECALL_HISTORY_A = Pattern.compile(
            "(?i)^what (?:was|were) my (?:old|previous|earlier|last|former) (.+)$");
    private static final Pattern RECALL_HISTORY_B = Pattern.compile(
            "(?i)^what (?:was|were) my (.+?) (?:before|earlier|previously)$");

    private static final Pattern LIST = Pattern.compile(
            "(?i)^(?:what do you (?:know|remember) about me|what have you (?:saved|remembered|stored)(?: about me)?"
                    + "|(?:list|show)(?: me)? (?:my |your )?memor(?:y|ies)|what(?:'s| is) in your memory)$");

    /** Ways of asking "what's my name" that don't follow the "what is my X" shape. */
    private static final Pattern WHO_AM_I = Pattern.compile(
            "(?i)^(?:who am i|do you know (?:me|who i am|my name)|what do you call me|tell me my name"
                    + "|do you remember (?:me|my name)|what(?:'s| is) my good name)$");
    private static final Pattern RECALL_MY = Pattern.compile(
            "(?i)^(?:what|who|when|where|which)(?:'s|'re| is| are| was| were)? my (.+)$");
    private static final Pattern RECALL_HOW_OLD = Pattern.compile(
            "(?i)^how old is my (.+)$");
    private static final Pattern RECALL_ASK = Pattern.compile(
            "(?i)^(?:do you (?:know|remember)|tell me|remind me(?: of| about)?|recall) (?:what )?(?:my )(.+?)(?: is| are)?$");
    private static final Pattern RECALL_TOLD = Pattern.compile(
            "(?i)^what did i (?:tell|say to) you about (?:my )?(.+)$");
    private static final Pattern RECALL_SOFT = Pattern.compile(
            "(?i)^(?:where|when|what|which|who) (?:did|do|have|was|am|were) i (.+)$");

    // ---- implicit (no "remember") shapes ----
    private static final Pattern NAME_IS = Pattern.compile("(?i)^(?:my name is|call me|i am called|i'm called|you can call me) (.+)$");
    private static final Pattern MY_X_IS_Y = Pattern.compile("(?i)^(?:my|our) (.+?) (?:is|are|was|=) (.+)$");
    private static final Pattern LIVE_IN = Pattern.compile("(?i)^i (?:live|stay) in (.+)$");
    private static final Pattern WORK_AT = Pattern.compile("(?i)^i work (?:at|for|in) (.+)$");
    private static final Pattern WORK_AS = Pattern.compile("(?i)^i work as (?:an? )?(.+)$");
    private static final Pattern ALLERGIC = Pattern.compile("(?i)^(?:i am|i'm) allergic to (.+)$");
    private static final Pattern SUBJECT_IS = Pattern.compile("(?i)^(?:the )?(.+?) (?:is|are) (.+)$");

    /** Implicit "my X is Y" is saved only when the key contains one of these words. */
    private static final List<String> IMPLICIT_KEY_WORDS = Arrays.asList(
            "name", "favorite", "birthday", "anniversary", "number", "email", "address",
            "blood", "hometown", "nickname", "password", "pin", "wifi", "username", "id"
    );

    /** Implicit values starting like this are states/chatter, not facts. */
    private static final List<String> NON_FACT_VALUE_STARTS = Arrays.asList(
            "not ", "no ", "very ", "so ", "too ", "really ", "going ", "getting ", "feeling ",
            "in ", "on ", "at ", "out ", "off ", "dead", "dying", "low", "broken", "hurting", "sick",
            "busy", "tired", "bad", "good", "fine", "ok", "okay", "late", "here", "there"
    );

    private MemoryParser() {
    }

    public static MemoryCommand parse(String utterance) {
        String t = clean(utterance);
        if (t.isEmpty()) return MemoryCommand.NONE;
        String l = TextTools.lower(t);

        if (FORGET_ALL.matcher(l).matches()) {
            return MemoryCommand.of(MemoryCommand.Type.FORGET_ALL, "", "", t);
        }
        if (UNDO.matcher(l).matches()) {
            return MemoryCommand.of(MemoryCommand.Type.UNDO_LAST, "", "", t);
        }

        Matcher m = CORRECT_LAST.matcher(t);
        if (m.matches()) {
            return MemoryCommand.of(MemoryCommand.Type.CORRECT_LAST, "", TextTools.trimPunctuation(m.group(1)), t);
        }

        m = FORGET.matcher(t);
        if (m.matches()) {
            String key = stripLeadingMy(m.group(1));
            if (!key.isEmpty() && !TextTools.lower(key).equals("it")) {
                return MemoryCommand.of(MemoryCommand.Type.FORGET, key, "", t);
            }
        }

        m = REMEMBER.matcher(t);
        if (m.matches()) {
            return parseBody(m.group(1), t, true, false);
        }

        if (LIST.matcher(l).matches()) {
            return MemoryCommand.of(MemoryCommand.Type.LIST, "", "", t);
        }

        m = RECALL_HISTORY_A.matcher(t);
        if (!m.matches()) m = RECALL_HISTORY_B.matcher(t);
        if (m.matches()) {
            return MemoryCommand.of(MemoryCommand.Type.RECALL_HISTORY, m.group(1), "", t);
        }

        m = RECALL_TOLD.matcher(t);
        if (m.matches()) {
            return MemoryCommand.of(MemoryCommand.Type.RECALL_SOFT, m.group(1), "", t);
        }

        if (WHO_AM_I.matcher(l).matches()) {
            return MemoryCommand.of(MemoryCommand.Type.RECALL, "name", "", t);
        }

        m = RECALL_HOW_OLD.matcher(t);
        if (m.matches()) {
            return MemoryCommand.of(MemoryCommand.Type.RECALL, m.group(1) + "'s age", "", t);
        }

        m = RECALL_MY.matcher(t);
        if (!m.matches()) m = RECALL_ASK.matcher(t);
        if (m.matches()) {
            return MemoryCommand.of(MemoryCommand.Type.RECALL, m.group(1), "", t);
        }

        m = RECALL_SOFT.matcher(t);
        if (m.matches()) {
            return MemoryCommand.of(MemoryCommand.Type.RECALL_SOFT, m.group(1), "", t);
        }

        // Corrections like "actually my favourite colour is green" are treated as
        // explicit: the user is clearly fixing something they told us.
        boolean correction = false;
        String body = t;
        Matcher cm = CORRECTION_PREFIX.matcher(body);
        if (cm.find()) {
            correction = true;
            body = body.substring(cm.end());
        }

        MemoryCommand implicit = parseImplicit(body, t, correction);
        if (implicit != null) return implicit;

        return MemoryCommand.NONE;
    }

    /** Body of an explicit "remember ...". Never returns NONE. */
    static MemoryCommand parseBody(String rawBody, String source, boolean explicit, boolean correction) {
        String body = TextTools.trimPunctuation(rawBody);
        body = body.replaceFirst("(?i)^that\\s+", "");

        Matcher m = NAME_IS.matcher(body);
        if (m.matches()) {
            return MemoryCommand.remember("name", TextTools.trimPunctuation(m.group(1)), source, explicit, correction);
        }

        m = MY_X_IS_Y.matcher(body);
        if (m.matches() && wordCount(m.group(1)) <= MAX_KEY_WORDS) {
            return MemoryCommand.remember(m.group(1), TextTools.trimPunctuation(m.group(2)), source, explicit, correction);
        }

        MemoryCommand special = parseSpecialShapes(body, source, explicit, correction);
        if (special != null) return special;

        m = SUBJECT_IS.matcher(body);
        if (m.matches()) {
            String subject = m.group(1);
            String ls = TextTools.lower(subject);
            boolean personal = ls.matches("(?:i|i'm|we|you|he|she|they|it|this|that|there|here)(?:\\s.*)?");
            if (!personal && wordCount(subject) <= 4) {
                return MemoryCommand.remember(subject, TextTools.trimPunctuation(m.group(2)), source, explicit, correction);
            }
        }

        return MemoryCommand.note(body, source);
    }

    private static MemoryCommand parseImplicit(String body, String source, boolean correction) {
        String b = TextTools.trimPunctuation(body);
        if (b.endsWith("?")) return null;

        Matcher m = NAME_IS.matcher(b);
        if (m.matches()) {
            return MemoryCommand.remember("name", TextTools.trimPunctuation(m.group(1)), source, false, correction);
        }

        MemoryCommand special = parseSpecialShapes(b, source, false, correction);
        if (special != null) return special;

        m = MY_X_IS_Y.matcher(b);
        if (m.matches()) {
            String keyPhrase = m.group(1);
            String value = TextTools.trimPunctuation(m.group(2));
            if (wordCount(keyPhrase) > MAX_KEY_WORDS || value.isEmpty()) return null;

            String lv = TextTools.lower(value) + " ";
            for (String start : NON_FACT_VALUE_STARTS) {
                if (lv.startsWith(start.endsWith(" ") ? start : start + " ")) return null;
            }

            String canonical = KeyNormalizer.canonical(keyPhrase);
            boolean keyAllowed = correction;
            for (String w : IMPLICIT_KEY_WORDS) {
                if ((" " + canonical + " ").contains(" " + w + " ")) {
                    keyAllowed = true;
                    break;
                }
            }
            if (keyAllowed) {
                return MemoryCommand.remember(keyPhrase, value, source, false, correction);
            }
        }
        return null;
    }

    private static MemoryCommand parseSpecialShapes(String body, String source, boolean explicit, boolean correction) {
        Matcher m = LIVE_IN.matcher(body);
        if (m.matches()) return MemoryCommand.remember("home city", TextTools.trimPunctuation(m.group(1)), source, explicit, correction);
        m = WORK_AS.matcher(body);
        if (m.matches()) return MemoryCommand.remember("job", TextTools.trimPunctuation(m.group(1)), source, explicit, correction);
        m = WORK_AT.matcher(body);
        if (m.matches()) return MemoryCommand.remember("workplace", TextTools.trimPunctuation(m.group(1)), source, explicit, correction);
        m = ALLERGIC.matcher(body);
        if (m.matches()) return MemoryCommand.remember("allergy", TextTools.trimPunctuation(m.group(1)), source, explicit, correction);
        return null;
    }

    private static String clean(String utterance) {
        String t = TextTools.trimPunctuation(utterance);
        t = ADDRESS.matcher(t).replaceFirst("");
        t = POLITE.matcher(t).replaceFirst("");
        return TextTools.trimPunctuation(t);
    }

    private static String stripLeadingMy(String phrase) {
        return TextTools.trimPunctuation(phrase).replaceFirst("(?i)^(?:my|the)\\s+", "");
    }

    private static int wordCount(String s) {
        String t = TextTools.collapseSpaces(s);
        return t.isEmpty() ? 0 : t.split(" ").length;
    }
}
