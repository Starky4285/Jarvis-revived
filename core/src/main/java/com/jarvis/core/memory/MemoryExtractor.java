package com.jarvis.core.memory;

import com.jarvis.core.llm.ChatPrompt;
import com.jarvis.core.text.TextTools;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Optional model-assisted step: when the user says "remember ..." and the
 * sentence has no obvious "my X is Y" shape, the on-device model may suggest a
 * short key/value. The model only PROPOSES; {@link #validate} decides, and the
 * engine still says back what it saved so a wrong guess is corrected by voice.
 */
public interface MemoryExtractor {

    /** Returns the model's raw JSON text, or "" when unavailable. Called off the main thread. */
    String propose(String sentence);

    /** Prompt the app sends to the model. Kept here so it is versioned with the validator. */
    static ChatPrompt buildPrompt(String sentence) {
        String safe = TextTools.sanitizeForPrompt(sentence, 240);
        return ChatPrompt.builder()
                .system("Extract one personal fact from the user's sentence. "
                        + "Reply ONLY with JSON: {\"key\": \"short noun phrase\", \"value\": \"the fact\"}. "
                        + "Use the user's words. If there is no clear fact, reply {\"key\": \"\", \"value\": \"\"}.")
                .user(safe)
                .build();
    }

    /** GBNF: the model can only answer with {"key": "...", "value": "..."}. */
    String GRAMMAR = "root ::= \"{\" ws \"\\\"key\\\":\" ws key ws \",\" ws \"\\\"value\\\":\" ws val ws \"}\"\n"
            + "key ::= \"\\\"\" ch{0,40} \"\\\"\"\n"
            + "val ::= \"\\\"\" ch{0,160} \"\\\"\"\n"
            + "ch ::= [^\"\\\\\\x00-\\x1f]\n"
            + "ws ::= \" \"?\n";

    /** A checked proposal. */
    final class Proposal {
        public final String key;
        public final String value;

        Proposal(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    Pattern KEY_FIELD = Pattern.compile("\"key\"\\s*:\\s*\"([^\"]{0,80})\"");
    Pattern VALUE_FIELD = Pattern.compile("\"value\"\\s*:\\s*\"([^\"]{0,240})\"");
    Pattern INSTRUCTION_LIKE = Pattern.compile(
            "(?i)(ignore (all |the )?(previous|above)|system prompt|you are now|<\\||assistant:|system:|```)");

    /**
     * Parses and validates the model output. Returns null when the proposal is
     * missing, too long, instruction-like, or not grounded in the sentence.
     */
    static Proposal validate(String modelOutput, String sentence) {
        if (modelOutput == null || sentence == null) return null;
        Matcher km = KEY_FIELD.matcher(modelOutput);
        Matcher vm = VALUE_FIELD.matcher(modelOutput);
        if (!km.find() || !vm.find()) return null;

        String key = TextTools.trimPunctuation(km.group(1));
        String value = TextTools.trimPunctuation(vm.group(1));
        if (key.isEmpty() || value.isEmpty()) return null;
        if (key.split(" ").length > 6 || value.length() > 200) return null;
        if (INSTRUCTION_LIKE.matcher(key).find() || INSTRUCTION_LIKE.matcher(value).find()) return null;

        // Grounding: every content word of the value must appear in the sentence,
        // so the model cannot invent facts the user never said.
        String ls = " " + TextTools.lower(sentence) + " ";
        for (String token : TextTools.contentTokens(value)) {
            if (!ls.contains(token)) return null;
        }
        return new Proposal(key, value);
    }
}
