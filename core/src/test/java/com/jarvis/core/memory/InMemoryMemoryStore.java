package com.jarvis.core.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test double with the same semantics as the Android SQLite store. Test-only on purpose. */
class InMemoryMemoryStore implements MemoryStore {

    private final List<Fact> facts = new ArrayList<>();
    private final Map<String, ProtectedItem> secrets = new LinkedHashMap<>();
    private final List<Episode> episodes = new ArrayList<>();
    private long nextId = 1;

    @Override
    public Fact activeFact(String key) {
        for (Fact f : facts) if (f.key.equals(key) && f.isActive()) return f;
        return null;
    }

    @Override
    public List<Fact> activeFacts() {
        List<Fact> out = new ArrayList<>();
        for (Fact f : facts) if (f.isActive()) out.add(f);
        out.sort(Comparator.comparingLong((Fact f) -> -f.createdAt).thenComparingLong(f -> -f.id));
        return out;
    }

    @Override
    public List<Fact> history(String key) {
        List<Fact> out = new ArrayList<>();
        for (Fact f : facts) if (f.key.equals(key)) out.add(f);
        out.sort(Comparator.comparingLong((Fact f) -> -f.createdAt).thenComparingLong(f -> -f.id));
        return out;
    }

    @Override
    public long insertFact(String key, String label, String value, String source, long now) {
        long id = nextId++;
        facts.add(new Fact(id, key, label, value, source, now, 0L));
        return id;
    }

    @Override
    public void supersedeFact(long id, long now) {
        replace(id, f -> new Fact(f.id, f.key, f.label, f.value, f.source, f.createdAt, now));
    }

    @Override
    public void deleteFactRow(long id) {
        facts.removeIf(f -> f.id == id);
    }

    @Override
    public void reactivateFact(long id) {
        replace(id, f -> new Fact(f.id, f.key, f.label, f.value, f.source, f.createdAt, 0L));
    }

    @Override
    public int deleteKey(String key) {
        int before = facts.size();
        facts.removeIf(f -> f.key.equals(key));
        return before - facts.size();
    }

    @Override
    public void putProtected(String key, String label, String value, long now) {
        secrets.put(key, new ProtectedItem(key, label, value, now));
    }

    @Override
    public ProtectedItem getProtected(String key) {
        return secrets.get(key);
    }

    @Override
    public List<ProtectedItem> listProtected() {
        return new ArrayList<>(secrets.values());
    }

    @Override
    public boolean deleteProtected(String key) {
        return secrets.remove(key) != null;
    }

    @Override
    public void appendEpisode(Episode.Role role, String text, long now) {
        episodes.add(new Episode(nextId++, role, text, now));
    }

    @Override
    public List<Episode> recentEpisodes(int limit) {
        List<Episode> out = new ArrayList<>();
        for (int i = episodes.size() - 1; i >= 0 && out.size() < limit; i--) out.add(episodes.get(i));
        return out;
    }

    @Override
    public void pruneEpisodes(int keep) {
        while (episodes.size() > keep) episodes.remove(0);
    }

    @Override
    public void deleteAll() {
        facts.clear();
        secrets.clear();
        episodes.clear();
    }

    @Override
    public void inTransaction(Runnable block) {
        block.run();
    }

    List<Episode> allEpisodes() {
        return episodes;
    }

    private void replace(long id, java.util.function.UnaryOperator<Fact> op) {
        for (int i = 0; i < facts.size(); i++) {
            if (facts.get(i).id == id) {
                facts.set(i, op.apply(facts.get(i)));
                return;
            }
        }
    }
}
