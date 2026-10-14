package com.jarvis.core.memory;

/**
 * One remembered fact: "wife name" = "Priya".
 *
 * A key has at most one ACTIVE fact. Older values stay as history
 * (supersededAt > 0) until the user asks to forget the key.
 */
public final class Fact {

    public final long id;
    /** Canonical lookup key, e.g. "wife name". See {@link KeyNormalizer}. */
    public final String key;
    /** How the user phrased it, first person, e.g. "wife's name". Used in replies. */
    public final String label;
    public final String value;
    /** The utterance this came from (for the Memory screen / audit). */
    public final String source;
    public final long createdAt;
    /** 0 while active; time it was replaced otherwise. */
    public final long supersededAt;

    public Fact(long id, String key, String label, String value, String source,
                long createdAt, long supersededAt) {
        this.id = id;
        this.key = key;
        this.label = label;
        this.value = value;
        this.source = source;
        this.createdAt = createdAt;
        this.supersededAt = supersededAt;
    }

    public boolean isActive() {
        return supersededAt == 0L;
    }

    /** Free-form notes ("remember I parked on level 3") use keys starting with this prefix. */
    public static final String NOTE_PREFIX = "note ";

    public boolean isNote() {
        return key.startsWith(NOTE_PREFIX);
    }

    @Override
    public String toString() {
        return key + "=" + value + (isActive() ? "" : " (old)");
    }
}
