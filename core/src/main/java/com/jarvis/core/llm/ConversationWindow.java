package com.jarvis.core.llm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The last few exchanges, so follow-ups like "and in Hindi?" make sense.
 * Bounded by turns AND characters because the phone model's context is small.
 */
public final class ConversationWindow {

    public static final class Turn {
        public final String user;
        public final String assistant;

        Turn(String user, String assistant) {
            this.user = user;
            this.assistant = assistant;
        }
    }

    private final int maxTurns;
    private final int maxChars;
    private final Deque<Turn> turns = new ArrayDeque<>();

    public ConversationWindow(int maxTurns, int maxChars) {
        this.maxTurns = maxTurns;
        this.maxChars = maxChars;
    }

    public synchronized void add(String user, String assistant) {
        if (user == null || assistant == null || user.isEmpty() || assistant.isEmpty()) return;
        turns.addLast(new Turn(user, assistant));
        while (turns.size() > maxTurns) turns.removeFirst();
    }

    public synchronized void clear() {
        turns.clear();
    }

    /** Oldest first, trimmed from the oldest end until it fits the character budget. */
    public synchronized List<Turn> recent() {
        List<Turn> out = new ArrayList<>(turns);
        int chars = 0;
        for (Turn t : out) chars += t.user.length() + t.assistant.length();
        while (!out.isEmpty() && chars > maxChars) {
            Turn first = out.remove(0);
            chars -= first.user.length() + first.assistant.length();
        }
        return out;
    }
}
