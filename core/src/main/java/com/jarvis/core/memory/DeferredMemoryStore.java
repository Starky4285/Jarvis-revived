package com.jarvis.core.memory;

import java.util.List;
import java.util.function.Supplier;

/**
 * A store that is opened on first use instead of at construction.
 *
 * The app's vault needs a Keystore unlock, the database decrypted and, once, a
 * migration; none of that should hold up the screen at launch. The app creates
 * this wrapper on the UI thread and opens it as the first job of the worker
 * thread that does all memory work, so the slow part never runs on the UI
 * thread and every later call finds it open.
 *
 * If opening throws or returns null, a {@link VolatileMemoryStore} is used:
 * Jarvis keeps working for the session and nothing is written to disk.
 */
public final class DeferredMemoryStore implements MemoryStore {

    private final Supplier<MemoryStore> opener;
    private volatile MemoryStore delegate;

    public DeferredMemoryStore(Supplier<MemoryStore> opener) {
        if (opener == null) throw new IllegalArgumentException("opener");
        this.opener = opener;
    }

    /** Opens the store if needed (once, even with several threads) and returns it. */
    public MemoryStore open() {
        MemoryStore d = delegate;
        if (d != null) return d;
        synchronized (this) {
            if (delegate == null) {
                MemoryStore opened;
                try {
                    opened = opener.get();
                } catch (RuntimeException e) {
                    opened = null;
                }
                delegate = opened != null ? opened : new VolatileMemoryStore();
            }
            return delegate;
        }
    }

    public boolean isOpen() {
        return delegate != null;
    }

    // ---- facts ----

    @Override
    public Fact activeFact(String key) {
        return open().activeFact(key);
    }

    @Override
    public List<Fact> activeFacts() {
        return open().activeFacts();
    }

    @Override
    public List<Fact> history(String key) {
        return open().history(key);
    }

    @Override
    public long insertFact(String key, String label, String value, String source, long now) {
        return open().insertFact(key, label, value, source, now);
    }

    @Override
    public void supersedeFact(long id, long now) {
        open().supersedeFact(id, now);
    }

    @Override
    public void deleteFactRow(long id) {
        open().deleteFactRow(id);
    }

    @Override
    public void reactivateFact(long id) {
        open().reactivateFact(id);
    }

    @Override
    public int deleteKey(String key) {
        return open().deleteKey(key);
    }

    // ---- protected lane ----

    @Override
    public void putProtected(String key, String label, String value, long now) {
        open().putProtected(key, label, value, now);
    }

    @Override
    public ProtectedItem getProtected(String key) {
        return open().getProtected(key);
    }

    @Override
    public List<ProtectedItem> listProtected() {
        return open().listProtected();
    }

    @Override
    public boolean deleteProtected(String key) {
        return open().deleteProtected(key);
    }

    // ---- episodes ----

    @Override
    public void appendEpisode(Episode.Role role, String text, long now) {
        open().appendEpisode(role, text, now);
    }

    @Override
    public List<Episode> recentEpisodes(int limit) {
        return open().recentEpisodes(limit);
    }

    @Override
    public void pruneEpisodes(int keep) {
        open().pruneEpisodes(keep);
    }

    // ---- everything ----

    @Override
    public void deleteAll() {
        open().deleteAll();
    }

    @Override
    public void inTransaction(Runnable block) {
        open().inTransaction(block);
    }
}
