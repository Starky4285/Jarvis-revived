package com.jarvis.core.memory;

import com.jarvis.core.text.TextTools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Picks the few remembered facts worth putting in the model prompt for this
 * question, within a hard character budget (Phi-3 on a phone has ~1k tokens).
 *
 * Rules: protected items never; the user's name always (if known); then facts
 * whose words overlap the question; newest first on ties. Output is sanitized
 * data, one fact per line, meant to sit inside a fenced block in the prompt.
 */
public final class PromptMemory {

    public static final int DEFAULT_BUDGET_CHARS = 400;
    private static final int MAX_VALUE_CHARS = 120;

    private PromptMemory() {
    }

    public static String contextFor(String question, MemoryStore store, int budgetChars) {
        List<Fact> facts = store.activeFacts();
        if (facts.isEmpty()) return "";

        Set<String> q = new HashSet<>(TextTools.contentTokens(question));
        List<Scored> scored = new ArrayList<>();
        for (Fact f : facts) {
            int score;
            if (f.key.equals("name") || f.key.equals("nickname")) {
                score = 100;
            } else {
                score = 0;
                for (String t : TextTools.contentTokens(f.key + " " + f.value)) {
                    if (q.contains(t)) score += 10;
                }
            }
            if (score > 0) scored.add(new Scored(f, score));
        }
        scored.sort(Comparator.<Scored>comparingInt(s -> -s.score)
                .thenComparingLong(s -> -s.fact.createdAt));

        StringBuilder sb = new StringBuilder();
        for (Scored s : scored) {
            String line = "- " + lineFor(s.fact);
            if (sb.length() + line.length() + 1 > budgetChars) break;
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }

    private static String lineFor(Fact f) {
        if (f.isNote()) {
            return "user said: " + TextTools.sanitizeForPrompt(f.value, MAX_VALUE_CHARS);
        }
        return "user's " + TextTools.sanitizeForPrompt(f.label, 40) + ": "
                + TextTools.sanitizeForPrompt(f.value, MAX_VALUE_CHARS);
    }

    private static final class Scored {
        final Fact fact;
        final int score;

        Scored(Fact fact, int score) {
            this.fact = fact;
            this.score = score;
        }
    }
}
