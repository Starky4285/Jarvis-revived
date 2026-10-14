package com.jarvis.core.memory;

import com.jarvis.core.text.TextTools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns how a user names a thing ("my wife's name", "fav colour", "mobile no")
 * into one canonical key ("wife name", "favorite color", "phone number").
 *
 * The synonym table is a dictionary, not a gate: unknown phrases are still
 * valid keys, they just keep their own words.
 */
public final class KeyNormalizer {

    /** Multi-word phrases first, applied on the space-joined lowercase key. */
    private static final Map<String, String> PHRASES = new LinkedHashMap<>();
    /** Single words. */
    private static final Map<String, String> WORDS = new LinkedHashMap<>();

    static {
        PHRASES.put("date of birth", "birthday");
        PHRASES.put("birth date", "birthday");
        PHRASES.put("bday", "birthday");
        PHRASES.put("mobile number", "phone number");
        PHRASES.put("mobile no", "phone number");
        PHRASES.put("cell number", "phone number");
        PHRASES.put("contact number", "phone number");
        PHRASES.put("phone no", "phone number");
        PHRASES.put("wi fi", "wifi");
        PHRASES.put("e mail", "email");
        PHRASES.put("email id", "email");
        PHRASES.put("email address", "email");
        PHRASES.put("home town", "hometown");
        PHRASES.put("nick name", "nickname");

        WORDS.put("favourite", "favorite");
        WORDS.put("fav", "favorite");
        WORDS.put("fave", "favorite");
        WORDS.put("colour", "color");
        WORDS.put("mom", "mother");
        WORDS.put("mum", "mother");
        WORDS.put("amma", "mother");
        WORDS.put("dad", "father");
        WORDS.put("nanna", "father");
        WORDS.put("hubby", "husband");
        WORDS.put("wifey", "wife");
        WORDS.put("bro", "brother");
        WORDS.put("sis", "sister");
        WORDS.put("dob", "birthday");
        WORDS.put("passcode", "password");
        WORDS.put("pwd", "password");
    }

    private KeyNormalizer() {
    }

    /**
     * Canonical key for a user phrase. Possessives and articles are removed,
     * case is lowered, synonyms are unified. Word order is kept.
     */
    public static String canonical(String phrase) {
        String s = TextTools.lower(TextTools.trimPunctuation(phrase));
        s = s.replaceAll("[’`]", "'");
        s = s.replaceAll("'s\\b", "").replaceAll("s'\\b", "s");
        s = s.replaceAll("[^\\p{L}\\p{Nd} ]+", " ");
        s = TextTools.collapseSpaces(s);

        List<String> kept = new ArrayList<>();
        for (String w : s.split(" ")) {
            if (w.isEmpty()) continue;
            if (w.equals("my") || w.equals("the") || w.equals("a") || w.equals("an")
                    || w.equals("our") || w.equals("current")) {
                continue;
            }
            kept.add(w);
        }
        s = String.join(" ", kept);

        for (Map.Entry<String, String> e : PHRASES.entrySet()) {
            s = (" " + s + " ").replace(" " + e.getKey() + " ", " " + e.getValue() + " ").trim();
        }

        List<String> out = new ArrayList<>();
        for (String w : s.split(" ")) {
            if (w.isEmpty()) continue;
            String mapped = WORDS.get(w);
            out.add(mapped == null ? w : mapped);
        }
        return String.join(" ", out);
    }

    /** Stemmed content tokens of a key, for fuzzy matching against questions. */
    public static List<String> tokens(String key) {
        return TextTools.contentTokens(canonical(key));
    }
}
