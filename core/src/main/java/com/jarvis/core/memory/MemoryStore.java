package com.jarvis.core.memory;

import java.util.List;

/**
 * Storage contract. The app's implementation is the SQLCipher-encrypted vault;
 * {@link VolatileMemoryStore} keeps memory in RAM (tests, and the app's
 * fallback when secure storage fails). Stores are dumb: every decision (what
 * to save, overwrite, forget) is made by {@link MemoryEngine}.
 */
public interface MemoryStore {

    // ---- facts ----

    /** Active fact for a canonical key, or null. */
    Fact activeFact(String key);

    /** All active facts, newest first. */
    List<Fact> activeFacts();

    /** Every value ever stored for the key, newest first (active one included). */
    List<Fact> history(String key);

    /** Inserts a new ACTIVE fact and returns its id. Caller supersedes the old one first. */
    long insertFact(String key, String label, String value, String source, long now);

    /** Marks a fact as replaced (kept as history). */
    void supersedeFact(long id, long now);

    /** Deletes a single row (used by "never mind" right after a save). */
    void deleteFactRow(long id);

    /** Re-activates a previously superseded row (undo of an overwrite). */
    void reactivateFact(long id);

    /** Forgets a key completely, history included. Returns rows removed. */
    int deleteKey(String key);

    // ---- protected lane ----

    void putProtected(String key, String label, String value, long now);

    ProtectedItem getProtected(String key);

    List<ProtectedItem> listProtected();

    boolean deleteProtected(String key);

    // ---- episodes ----

    void appendEpisode(Episode.Role role, String text, long now);

    /** Most recent first. */
    List<Episode> recentEpisodes(int limit);

    /** Keeps only the newest {@code keep} episodes. */
    void pruneEpisodes(int keep);

    // ---- everything ----

    /** Wipes facts, protected items and episodes. */
    void deleteAll();

    /** Runs the block atomically (SQLite transaction). */
    void inTransaction(Runnable block);
}
