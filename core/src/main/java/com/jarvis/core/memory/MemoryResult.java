package com.jarvis.core.memory;

/** Outcome of {@link MemoryEngine#handle}. {@link #handled()} false means "not a memory turn". */
public final class MemoryResult {

    public enum Kind {
        NOT_MEMORY,
        SAVED,
        UPDATED,
        ALREADY_KNOWN,
        PROTECTED_SAVED,
        /** Encryption was unavailable; the secret was NOT stored anywhere. */
        PROTECTED_FAILED,
        RECALLED,
        PROTECTED_RECALL,
        RECALL_MISS,
        HISTORY,
        LISTED,
        FORGOTTEN,
        FORGET_MISS,
        FORGET_ALL_CONFIRM,
        FORGOT_ALL,
        FORGET_ALL_CANCELLED,
        UNDONE,
        NOTHING_TO_UNDO
    }

    public final Kind kind;
    /** What Jarvis should say. Never contains a protected value. */
    public final String reply;
    /** The fact involved, when there is one (null for protected items). */
    public final Fact fact;

    MemoryResult(Kind kind, String reply, Fact fact) {
        this.kind = kind;
        this.reply = reply == null ? "" : reply;
        this.fact = fact;
    }

    static final MemoryResult NOT_MEMORY = new MemoryResult(Kind.NOT_MEMORY, "", null);

    public boolean handled() {
        return kind != Kind.NOT_MEMORY;
    }

    @Override
    public String toString() {
        return kind + ": " + reply;
    }
}
