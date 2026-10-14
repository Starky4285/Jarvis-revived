package com.jarvis.core.memory;

/** What a single utterance asks the memory system to do. Produced by {@link MemoryParser}. */
public final class MemoryCommand {

    public enum Type {
        /** Not a memory request. The router moves on. */
        NONE,
        /** Save a key/value fact (or a free-form note). */
        REMEMBER,
        /** "no, it's blue" — replace the value of the last thing saved. */
        CORRECT_LAST,
        /** "never mind" / "scratch that" right after a save. */
        UNDO_LAST,
        /** "forget my wifi password". */
        FORGET,
        /** "forget everything" — needs a spoken yes. */
        FORGET_ALL,
        /** "what's my wife's name" — a miss is answered explicitly. */
        RECALL,
        /** "where did I park" — searched softly; a miss falls through to the model. */
        RECALL_SOFT,
        /** "what was my old address". */
        RECALL_HISTORY,
        /** "what do you know about me". */
        LIST
    }

    public final Type type;
    /** The user's own words for the thing, e.g. "wife's name". */
    public final String keyPhrase;
    public final String value;
    /** Original text used for soft search. */
    public final String query;
    /** True when the user said "remember"/"note"/"save" explicitly. */
    public final boolean explicit;
    /** True when phrased as a correction ("actually my ..."). */
    public final boolean correction;
    /** True when the body could not be split into key/value and is kept as a note. */
    public final boolean note;

    private MemoryCommand(Type type, String keyPhrase, String value, String query,
                          boolean explicit, boolean correction, boolean note) {
        this.type = type;
        this.keyPhrase = keyPhrase == null ? "" : keyPhrase;
        this.value = value == null ? "" : value;
        this.query = query == null ? "" : query;
        this.explicit = explicit;
        this.correction = correction;
        this.note = note;
    }

    public static final MemoryCommand NONE = new MemoryCommand(Type.NONE, "", "", "", false, false, false);

    public static MemoryCommand remember(String keyPhrase, String value, String source,
                                         boolean explicit, boolean correction) {
        return new MemoryCommand(Type.REMEMBER, keyPhrase, value, source, explicit, correction, false);
    }

    public static MemoryCommand note(String text, String source) {
        return new MemoryCommand(Type.REMEMBER, "", text, source, true, false, true);
    }

    public static MemoryCommand of(Type type, String keyPhrase, String value, String query) {
        return new MemoryCommand(type, keyPhrase, value, query, false, false, false);
    }

    @Override
    public String toString() {
        return type + "{key='" + keyPhrase + "', value='" + value + "'" + (note ? ", note" : "") + "}";
    }
}
