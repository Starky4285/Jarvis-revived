package com.jarvis.core.memory;

/** One conversation turn, kept as a rolling log for "what did I tell you about X?". */
public final class Episode {

    public enum Role { USER, ASSISTANT }

    public final long id;
    public final Role role;
    public final String text;
    public final long timestamp;

    public Episode(long id, Role role, String text, long timestamp) {
        this.id = id;
        this.role = role;
        this.text = text;
        this.timestamp = timestamp;
    }
}
